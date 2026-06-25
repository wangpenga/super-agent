package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 路由歧义澄清执行器 - 调用链路第5层分支 C（CLARIFICATION 模式）
 * <p>
 * <b>适用场景：</b>AUTO_DOCUMENT 模式下，知识路由置信度不足（&lt; 0.55）或多候选文档歧义。
 * <p>
 * <b>核心特点：不调用 LLM，直接返回预设的澄清问题文本。</b>
 * <p>
 * <b>澄清问题的内容：</b>
 * 由 {@link ChatPreparationOrchestrator} 在 prepare 阶段生成的 clarificationReply，
 * 包含候选文档列表（如 "1. 《文档A》（知识空间X）2. 《文档B》（知识空间Y）"），
 * 引导用户明确指定要查询的文档。
 * <p>
 * <b>文本中包含的澄清选项</b>（clarificationOptions）：
 * 如 ["我想问《文档A》", "我想问《文档B》"]，
 * 在 finishSuccessfully 收尾阶段会通过 recommendationService 转为推荐追问展示给用户。
 * <p>
 * <b>与 ReactAgentExecutor / RagChatExecutor 的区别：</b>
 * 这两个执行器会调用 LLM，返回流式文本 Flux；而本执行器直接返回单条文本 Flux.just(clarificationReply)，
 * 是一次性返回，不涉及流式生成。
 *
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 澄清执行器 - AUTO_DOCUMENT 下文档歧义时引导用户确认，不调用 LLM
 * @author: 阿星不是程序员
 **/
@Component
public class ClarificationExecutor implements ConversationExecutor {

    private final StreamEventWriter streamEventWriter;

    public ClarificationExecutor(StreamEventWriter streamEventWriter) {
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        // 本执行器处理 CLARIFICATION 模式（文档歧义澄清）
        return ExecutionMode.CLARIFICATION;
    }

    /**
     * 返回澄清问题文本，不调用 LLM
     * <p>
     * <b>处理流程：</b>
     * <ol>
     *   <li>从 executionPlan 中获取 clarificationReply（澄清问题文本）和 clarificationReason（澄清原因）</li>
     *   <li>将澄清原因记录到 debugTrace 的 retrievalNotes</li>
     *   <li>发送思考状态 + 澄清原因状态事件</li>
     *   <li>记录 ROUTE 追踪阶段</li>
     *   <li>直接返回 Flux.just(clarificationReply) — 单条文本，非流式</li>
     * </ol>
     * <p>
     * <b>文本示例：</b>
     * "这个问题目前存在文档范围歧义，我先确认你想问哪一份：
     *  1. 《项目开发规范》（技术知识库）
     *  2. 《产品需求文档》（产品知识库）
     *  你可以直接回复文档名，或者改用"当前文档问答"模式明确指定文档。"
     *
     * @param taskInfo 运行时任务上下文
     * @return 包含澄清问题文本的单元素 Flux
     */
    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        // 获取澄清回复文本（由 ChatPreparationOrchestrator 在 prepare 阶段生成）
        String clarificationReply = plan == null
            ? "当前我无法稳定判断你想问哪份知识文档，请补充更具体的文档名、主题或关键词。"
            : StrUtil.blankToDefault(plan.getClarificationReply(),
                "当前我无法稳定判断你想问哪份知识文档，请补充更具体的文档名、主题或关键词。");
        String clarificationReason = plan == null ? "" : StrUtil.blankToDefault(plan.getClarificationReason(), "");
        if (taskInfo.debugTrace() != null && StrUtil.isNotBlank(clarificationReason)) {
            taskInfo.debugTrace().getRetrievalNotes().add(clarificationReason);
        }

        // 发送思考状态事件
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题涉及多份候选文档，先向你确认知识范围。");
        if (StrUtil.isNotBlank(clarificationReason)) {
            // 发送澄清原因（如 "当前自动知识路由置信度为 0.42，候选文档数为 2，为避免误选文档..."）
            ExecutorEventSupport.publishStatus(taskInfo, streamEventWriter, clarificationReason);
        }
        // 记录追踪阶段
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(
                taskInfo.traceRecorder().startStage(ConversationTraceStageCode.ROUTE, mode().name(), "当前候选存在歧义，先返回澄清问题。", null),
                "已返回澄清问题。",
                Map.of(
                    "clarificationReply", clarificationReply,
                    "clarificationReason", clarificationReason,
                    "clarificationOptions", plan == null || plan.getClarificationOptions() == null ? List.of() : plan.getClarificationOptions()
                )
            );
        }
        // 直接返回预设文本（不调用 LLM，避免不必要的 Token 消耗）
        return Flux.just(clarificationReply);
    }
}
