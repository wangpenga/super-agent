package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReactAgent 执行器 - 调用链路第5层分支 A（OPEN_CHAT 模式）
 * <p>
 * <b>适用场景：</b>开放式提问模式（ChatQueryMode.OPEN_CHAT）
 * <p>
 * <b>核心逻辑：</b>
 * 将问题直接交给 Alibaba Cloud AI 的 ReactAgent（基于 LangGraph 的 ReAct Agent），
 * Agent 内部自主决定：
 * <ul>
 *   <li>是否需要调用工具（联网搜索、计算器等）</li>
 *   <li>多步推理（Thought → Action → Observation → ... → Final Answer）</li>
 *   <li>何时结束并给出最终答案</li>
 * </ul>
 * <p>
 * <b>ReAct Agent 的流式输出：</b>
 * <pre>
 * reactAgent.stream(agentQuestion, config)
 *   → 返回 NodeOutput 流，每个 NodeOutput 可能包含不同的 OutputType：
 *     ├─ AGENT_MODEL_STREAMING: LLM 流式输出的增量文本块 → 直接推给客户端
 *     ├─ AGENT_MODEL_FINISHED: LLM 单次调用的完整结果 → 如果已有流式输出则跳过（去重）
 *     ├─ TOOL_CALL_START/TOOL_CALL_RESULT: 工具调用开始/结果
 *     └─ AGENT_COMPLETED: Agent 执行完成
 * </pre>
 * <p>
 * <b>文本提取策略：</b>
 * <ul>
 *   <li>优先取 message.getText()</li>
 *   <li>其次取 originData（可能是 Message 或 String）</li>
 *   <li>AGENT_MODEL_FINISHED 的完整文本在已有流式输出的情况下跳过（避免重复）</li>
 * </ul>
 * <p>
 * <b>下游链路：</b>
 * 本执行器产生的文本块 → buildConversationExecution 的 doOnNext → emitModelChunk → SSE Sink → 客户端
 *
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: ReactAgent 执行器 - 处理 OPEN_CHAT 模式，由 Agent 自主推理和工具调用
 * @author: 阿星不是程序员
 **/
@Component
public class ReactAgentExecutor implements ConversationExecutor {

    /**
     * 注入的 ReactAgent Bean（名称为 businessChatReactAgent）
     * 这是 Alibaba Cloud AI LangGraph 框架的 Reactive Agent，内部封装了
     * LLM 调用 + 工具编排 + 多步推理的完整 ReAct 循环
     */
    private final ReactAgent reactAgent;
    private final StreamEventWriter streamEventWriter;

    public ReactAgentExecutor(ReactAgent businessChatReactAgent,
                              StreamEventWriter streamEventWriter) {
        this.reactAgent = businessChatReactAgent;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        // 本执行器处理 REACT_AGENT 模式（OPEN_CHAT）
        return ExecutionMode.REACT_AGENT;
    }

    /**
     * 执行 ReactAgent 流式推理
     * <p>
     * <b>执行流程：</b>
     * <ol>
     *   <li>发送"正在分析"的思考状态</li>
     *   <li>调用 reactAgent.stream(agentQuestion, runnableConfig)
     *       <ul>
     *         <li>agentQuestion 由 ChatPreparationOrchestrator 准备，通过 prompt 模板渲染</li>
     *         <li>runnableConfig 中包含 threadId（conversationId）用于对话状态管理</li>
     *       </ul>
     *   </li>
     *   <li>concatMap 将每个 NodeOutput 转换为文本块 Mono</li>
     *   <li>绑定 doOnComplete/doOnError 记录追踪阶段</li>
     * </ol>
     * <p>
     * <b>ReAct Agent 内部循环：</b>
     * Thought(思考下一步) → Action(选择工具) → Action Input(工具参数) → Observation(工具结果)
     * → [循环直到得出最终答案] → Final Answer
     *
     * @param taskInfo 运行时任务上下文
     * @return 流式文本块 Flux
     */
    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        // 标记是否已有流式文本输出（用于避免 AGENT_MODEL_FINISHED 重复推送）
        AtomicBoolean streamedText = new AtomicBoolean(false);
        // 发送思考状态事件
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题进入开放式 Agent 自主执行阶段。");

        taskInfo.debugTrace().getRetrievalNotes().add("当前问题走 ReactAgent 执行路径，由 Agent 自主决定是否调用联网搜索或其他工具。");
        // 开始追踪 REACT_AGENT 执行阶段
        ConversationTraceRecorder.StageHandle agentStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                ConversationTraceStageCode.REACT_AGENT,
                mode().name(),
                "正在执行 ReAct Agent 推理与工具调用。",
                null
            );
        try {
            // ─── 核心调用：Alibaba Cloud AI ReactAgent 流式推理 ───
            return reactAgent.stream(taskInfo.executionPlan().getAgentQuestion(), taskInfo.runnableConfig())
                .publishOn(Schedulers.boundedElastic())  // 后续处理放在弹性线程池
                .concatMap(output -> extractTextChunk(output, streamedText))  // 从 NodeOutput 提取文本
                .doOnComplete(() -> {
                    if (taskInfo.traceRecorder() != null) {
                        taskInfo.traceRecorder().completeStage(agentStage, "ReAct Agent 执行完成。", Map.of(
                            "toolNames", taskInfo.debugTrace().getToolTraces() == null ? List.of() : taskInfo.debugTrace().getToolTraces(),
                            "usedTools", taskInfo.usedTools() == null ? List.of() : taskInfo.usedTools()
                        ));
                    }
                })
                .doOnError(error -> {
                    if (taskInfo.traceRecorder() != null) {
                        taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", error.getMessage(), null);
                    }
                });
        }
        catch (GraphRunnerException exception) {
            // Graph 执行器启动阶段异常（如线程ID不合法）
            if (taskInfo.traceRecorder() != null) {
                taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", exception.getMessage(), null);
            }
            return Flux.error(exception);
        }
    }

    /**
     * 从 NodeOutput 中提取文本块
     * <p>
     * <b>输出类型过滤逻辑：</b>
     * <ul>
     *   <li>非 StreamingOutput → 跳过</li>
     *   <li>内容为空 → 跳过</li>
     *   <li>AGENT_MODEL_STREAMING：标记已流式输出，返回文本块</li>
     *   <li>AGENT_MODEL_FINISHED：如果已有流式输出则跳过（去重），否则返回完整文本</li>
     *   <li>其他类型（TOOL_CALL_* 等）→ 跳过</li>
     * </ul>
     *
     * @param output       NodeOutput 节点输出
     * @param streamedText 流式文本标记（用于去重）
     * @return 文本块 Mono（可能为空）
     */
    private Mono<String> extractTextChunk(NodeOutput output, AtomicBoolean streamedText) {
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            // 非流式输出节点（如工具调用内部节点）→ 跳过
            return Mono.empty();
        }

        String content = extractStreamingText(streamingOutput);
        if (StrUtil.isBlank(content)) {
            return Mono.empty();
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
            // 流式增量文本块 → 标记并返回
            streamedText.set(true);
            return Mono.just(content);
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED) {
            // 已完成模型的完整输出 → 如果已有流式输出则跳过（去重）
            if (streamedText.get()) {
                return Mono.empty();
            }
            return Mono.just(content);
        }

        // TOOL_CALL_START / TOOL_CALL_RESULT / AGENT_COMPLETED 等类型不推送文本
        return Mono.empty();
    }

    /**
     * 从 StreamingOutput 中提取文本内容
     * <p>
     * <b>提取优先级：</b>
     * <ol>
     *   <li>streamingOutput.message().getText() — 优先取消息文本</li>
     *   <li>streamingOutput.getOriginData() 如果是 Message → getText()</li>
     *   <li>streamingOutput.getOriginData() 如果是 String → 直接返回</li>
     * </ol>
     */
    private String extractStreamingText(StreamingOutput<?> streamingOutput) {
        Message message = streamingOutput.message();
        if (message != null && StrUtil.isNotBlank(message.getText())) {
            return message.getText();
        }

        Object originData = streamingOutput.getOriginData();
        if (originData instanceof Message originMessage && StrUtil.isNotBlank(originMessage.getText())) {
            return originMessage.getText();
        }
        if (originData instanceof String text && StrUtil.isNotBlank(text)) {
            return text;
        }
        return "";
    }
}
