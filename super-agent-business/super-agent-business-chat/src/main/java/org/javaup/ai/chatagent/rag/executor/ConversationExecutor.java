package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.service.TaskInfo;
import reactor.core.publisher.Flux;

/**
 * 统一对话执行器抽象 - 调用链路第5层执行器接口
 * <p>
 * <b>策略模式：</b>每种 {@link ExecutionMode} 对应一个执行器实现。
 * <p>
 * <b>5 种执行器实现：</b>
 * <ul>
 *   <li>{@code ReactAgentExecutor}（REACT_AGENT）：OPEN_CHAT 模式，LLM 自主推理 + 工具调用</li>
 *   <li>{@code RagChatExecutor}（RETRIEVAL）：文档问答模式，双通道检索 → Prompt 组装 → LLM 生成</li>
 *   <li>{@code ClarificationExecutor}（CLARIFICATION）：文档歧义澄清，直接返回预设文本，不调用 LLM</li>
 *   <li>{@code GraphOnlyExecutor}（GRAPH_ONLY）：结构图直接回答，不调用 LLM</li>
 *   <li>{@code GraphThenEvidenceExecutor}（GRAPH_THEN_EVIDENCE）：结构图定位 + 证据校验 + 答案渲染</li>
 * </ul>
 * <p>
 * <b>调用方式：</b>
 * 由 {@link ConversationExecutorRegistry} 按 ExecutionMode 查找对应实现，
 * 在 {@code BusinessChatService.buildConversationExecution} 中调用。
 *
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 统一对话执行器抽象 - 策略模式，按 ExecutionMode 选择执行路径
 * @author: 阿星不是程序员
 **/
public interface ConversationExecutor {

    /**
     * @return 本执行器处理的 {@link ExecutionMode}
     */
    ExecutionMode mode();

    /**
     * 执行对话任务的流式处理
     * <p>
     * <b>返回值说明：</b>
     * <ul>
     *   <li>返回 {@link Flux}{@code <String>}：流式文本块序列</li>
     *   <li>每条文本块会被 BusinessChatService 的 doOnNext 捕获，
     *       通过 emitModelChunk 推送到 Sink → 客户端 SSE 连接</li>
     *   <li>Flux 完成时触发 finishSuccessfully，异常时触发 finishWithFailure</li>
     * </ul>
     *
     * @param taskInfo 运行时任务上下文（含 executionPlan, sink, traceRecorder 等）
     * @return 流式文本块序列
     */
    Flux<String> execute(TaskInfo taskInfo);
}
