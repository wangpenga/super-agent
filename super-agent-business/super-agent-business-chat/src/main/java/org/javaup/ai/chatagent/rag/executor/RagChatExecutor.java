package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.service.RagPromptAssemblyService;
import org.javaup.ai.chatagent.rag.service.RagRetrievalEngine;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识问答执行器 - 调用链路第5层分支 B（RETRIEVAL 模式）
 * <p>
 * <b>适用场景：</b>文档问答模式（DOCUMENT / AUTO_DOCUMENT 确定文档后），走混合检索路径。
 * <p>
 * <b>这是最复杂、最常用的执行器。完整的 RAG 流水线：</b>
 * <pre>
 * execute(taskInfo)
 *   │
 *   ├─ 1. RAG 检索阶段（RAG_RETRIEVE）
 *   │   └─ ragRetrievalEngine.retrieve(plan, traceRecorder)
 *   │       内部执行双通道混合检索：
 *   │       ├─ 结构图通道：基于文档结构图，定位相关章节/编号项
 *   │       └─ 关键词/语义向量通道：基于 chunk 的 embedding 相似度检索
 *   │       → 召回 → 融合 → 重排序 → 返回 RagRetrievalContext
 *   │       内含：每个子问题的证据列表、引用列表、使用的通道列表
 *   │
 *   ├─ 2. 证据处理
 *   │   ├─ 如果 context.isEmpty() → 直接返回 noEvidenceReply 兜底文本（不调用 LLM）
 *   │   └─ 如果有证据 → 将引用加入 taskInfo.references，继续下一步
 *   │
 *   ├─ 3. Prompt 组装阶段（EVIDENCE_BUDGET）
 *   │   └─ ragPromptAssemblyService.assemble(plan, context)
 *   │       根据 Token 预算将证据组织成 LLM 可理解的格式：
 *   │       ├─ systemPrompt: 系统指令 + 证据引用规范
 *   │       ├─ userPrompt: 用户问题 + 历史对话上下文 + 结构化的检索证据
 *   │       ├─ 超出预算的证据会被 omitted（省略），记录在 omittedReferenceDetails 中
 *   │       └─ 返回 RagPromptAssemblyResult
 *   │
 *   └─ 4. LLM 生成阶段（ANSWER_GENERATE）
 *       └─ observedChatModelService.streamText("rag_answer", systemPrompt, userPrompt, traceRecorder)
 *           → 底层调用 LLM API（带观测的流式调用）
 *           → 返回 Flux&lt;String&gt; 文本流
 *           → 每条文本块经过 buildConversationExecution 的 doOnNext → emitModelChunk → Sink → 客户端
 * </pre>
 * <p>
 * <b>无证据兜底机制：</b>
 * 当检索结果为空时，不调用 LLM，直接返回预设的 noEvidenceReply 文本。
 * 这个文本会根据问题类型动态生成（能力问询、开放式问题、一般无证据），
 * 引导用户切换到合适的模式或补充更具体的信息。
 *
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: RAG 知识问答执行器 - 双通道检索 + Prompt 预算组装 + LLM 流式生成
 * @author: 阿星不是程序员
 **/
@Component
public class RagChatExecutor implements ConversationExecutor {

    private final RagRetrievalEngine ragRetrievalEngine;
    private final RagPromptAssemblyService ragPromptAssemblyService;
    private final StreamEventWriter streamEventWriter;
    private final ObservedChatModelService observedChatModelService;

    public RagChatExecutor(RagRetrievalEngine ragRetrievalEngine,
                           RagPromptAssemblyService ragPromptAssemblyService,
                           StreamEventWriter streamEventWriter,
                           ObservedChatModelService observedChatModelService) {
        this.ragRetrievalEngine = ragRetrievalEngine;
        this.ragPromptAssemblyService = ragPromptAssemblyService;
        this.streamEventWriter = streamEventWriter;
        this.observedChatModelService = observedChatModelService;
    }

    @Override
    public ExecutionMode mode() {
        // 本执行器处理 RETRIEVAL 模式（文档知识问答）
        return ExecutionMode.RETRIEVAL;
    }

    /**
     * 执行 RAG 检索 + LLM 生成的完整流水线
     * <p>
     * <b>四阶段流水线：</b>
     * <ol>
     *   <li><b>RAG_RETRIEVE</b>：双通道混合检索（结构图 + 向量语义）</li>
     *   <li><b>证据处理</b>：空证据则兜底返回，有证据则继续</li>
     *   <li><b>EVIDENCE_BUDGET</b>：Token 预算控制下的 Prompt 组装</li>
     *   <li><b>ANSWER_GENERATE</b>：LLM 流式生成最终答案</li>
     * </ol>
     * <p>
     * <b>线程模型：</b>
     * ragRetrievalEngine.retrieve 是阻塞方法（涉及数据库查询 + embedding 计算），
     * 通过 Mono.fromCallable + subscribeOn(boundedElastic) 在弹性线程池执行。
     *
     * @param taskInfo 运行时任务上下文（含 executionPlan）
     * @return LLM 生成的流式文本 Flux
     */
    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在根据问题规划知识检索范围。");

        // ─── 阶段1：开始 RAG 检索追踪 ───
        ConversationTraceRecorder.StageHandle retrieveStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.RAG_RETRIEVE, mode().name(), "正在执行双通道混合检索。", null);

        // ─── 阶段1：双通道混合检索（在弹性线程池中执行）───
        return Mono.fromCallable(() -> ragRetrievalEngine.retrieve(plan, taskInfo.traceRecorder()))
            .subscribeOn(Schedulers.boundedElastic())  // 阻塞 I/O 放到弹性线程池
            .doOnError(error -> {
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(retrieveStage, "RAG 检索失败。", error.getMessage(), null);
                }
            })
            .doOnSuccess(context -> {
                // 记录检索完成的详细指标
                if (taskInfo.traceRecorder() != null && context != null) {
                    taskInfo.traceRecorder().completeStage(retrieveStage, "RAG 检索完成。", Map.of(
                        "retrievalQuestion", StrUtil.blankToDefault(context.getRetrievalQuestion(), ""),
                        "usedChannels", context.getUsedChannels() == null ? List.of() : context.getUsedChannels(),
                        "retrievalNotes", context.getRetrievalNotes() == null ? List.of() : context.getRetrievalNotes(),
                        "referenceCount", context.flattenReferences().size(),
                        "subQuestionCount", context.getSubQuestionEvidenceList() == null ? 0 : context.getSubQuestionEvidenceList().size(),
                        "subQuestions", context.getSubQuestionEvidenceList() == null
                            ? List.of()
                            : context.getSubQuestionEvidenceList().stream().map(item -> Map.of(
                                "index", item.getSubQuestionIndex(),
                                "question", StrUtil.blankToDefault(item.getSubQuestion(), ""),
                                "referenceCount", item.getReferences() == null ? 0 : item.getReferences().size(),
                                "documentCount", item.getDocuments() == null ? 0 : item.getDocuments().size(),
                                "fusedCandidateCount", item.getFusedCandidateCount() == null ? 0 : item.getFusedCandidateCount(),
                                "parentCandidateCount", item.getParentCandidateCount() == null ? 0 : item.getParentCandidateCount(),
                                "rerankedCandidateCount", item.getRerankedCandidateCount() == null ? 0 : item.getRerankedCandidateCount(),
                                "channelTraces", item.getChannelTraces() == null
                                    ? List.of()
                                    : item.getChannelTraces().stream().map(trace -> Map.of(
                                        "channelName", StrUtil.blankToDefault(trace.getChannelName(), ""),
                                        "recalledCount", trace.getRecalledCount(),
                                        "acceptedCount", trace.getAcceptedCount()
                                    )).toList(),
                                "references", item.getReferences() == null
                                    ? List.of()
                                    : item.getReferences().stream().map(reference -> Map.of(
                                        "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
                                        "documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                                        "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), ""),
                                        "channel", StrUtil.blankToDefault(reference.getChannel(), "")
                                    )).toList()
                            )).toList(),
                        "references", context.flattenReferences().stream().map(reference -> Map.of(
                            "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
                            "documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                            "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), ""),
                            "channel", StrUtil.blankToDefault(reference.getChannel(), "")
                        )).toList()
                    ));
                }
            })
            // ─── 阶段2-4：证据处理 → Prompt 组装 → LLM 生成 ───
            .flatMapMany(context -> streamFromRetrievalContext(taskInfo, plan, context));
    }

    /**
     * 从检索上下文到 LLM 流式输出的处理
     * <p>
     * <b>三条处理路径：</b>
     * <ol>
     *   <li><b>检索结果为空</b>：直接返回 noEvidenceReply，不调用 LLM（节省 Token）</li>
     *   <li><b>有检索结果</b>：
     *       <ol type="a">
     *         <li>记录检索备注和使用通道到 debugTrace</li>
     *         <li>将引用加入 taskInfo.references（最终展示给用户）</li>
     *         <li>Prompt 预算组装（EVIDENCE_BUDGET 阶段）</li>
     *         <li>LLM 流式生成答案（ANSWER_GENERATE 阶段）</li>
     *       </ol>
     *   </li>
     * </ol>
     */
    private Flux<String> streamFromRetrievalContext(TaskInfo taskInfo,
                                                    ConversationExecutionPlan plan,
                                                    RagRetrievalContext context) {
        // 发送检索备注为思考状态事件
        context.getRetrievalNotes().forEach(note -> ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, note));

        // 记录使用的检索通道（如 "structure_graph", "vector_search"）
        taskInfo.usedTools().addAll(context.getUsedChannels());
        taskInfo.debugTrace().setRetrievalNotes(new ArrayList<>(context.getRetrievalNotes()));
        taskInfo.debugTrace().setUsedChannels(new ArrayList<>(context.getUsedChannels()));

        // ─── 路径1：检索结果为空 → 返回无证据兜底回复 ───
        if (context.isEmpty()) {
            ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前没有足够证据，直接返回无证据兜底回复。");
            return Flux.just(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }

        // ─── 路径2：有检索结果 → 继续处理 ───
        // 将所有检索引用加入 taskInfo（在 finishSuccessfully 中会补发给客户端）
        taskInfo.references().addAll(context.flattenReferences());
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "证据整理完成，正在基于证据生成回答。");

        // ─── 阶段3：Prompt 预算组装 ───
        ConversationTraceRecorder.StageHandle budgetStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.EVIDENCE_BUDGET, mode().name(), "正在组装证据与 Prompt 预算。", null);
        RagPromptAssemblyResult promptAssemblyResult = ragPromptAssemblyService.assemble(plan, context);
        String systemPrompt = promptAssemblyResult.getSystemPrompt();
        String userPrompt = promptAssemblyResult.getUserPrompt();
        // 记录到 debugTrace 中（调试面板可查看完整 Prompt）
        taskInfo.debugTrace().setRagSystemPrompt(systemPrompt);
        taskInfo.debugTrace().setRagUserPrompt(userPrompt);
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(budgetStage, "证据预算与 Prompt 组装完成。", Map.of(
                "totalBudget", promptAssemblyResult.getTotalBudget(),
                "perSubQuestionBudget", promptAssemblyResult.getPerSubQuestionBudget(),
                "renderedReferenceCount", promptAssemblyResult.getRenderedReferenceCount(),
                "omittedReferenceCount", promptAssemblyResult.getOmittedReferenceCount(),
                "renderedReferenceDetails", promptAssemblyResult.getRenderedReferenceDetails() == null ? List.of() : promptAssemblyResult.getRenderedReferenceDetails(),
                "omittedReferenceDetails", promptAssemblyResult.getOmittedReferenceDetails() == null ? List.of() : promptAssemblyResult.getOmittedReferenceDetails(),
                "systemPrompt", StrUtil.blankToDefault(systemPrompt, ""),
                "userPrompt", StrUtil.blankToDefault(userPrompt, "")
            ));
        }

        // ─── 阶段4：LLM 流式生成最终答案 ───
        ConversationTraceRecorder.StageHandle answerStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.ANSWER_GENERATE, mode().name(), "正在基于证据生成回答。", null);
        // observedChatModelService.streamText 底层调用 LLM API（如通义千问/DashScope），返回流式文本 Flux
        return observedChatModelService.streamText("rag_answer", systemPrompt, userPrompt, taskInfo.traceRecorder())
            .doOnComplete(() -> {
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(answerStage, "答案生成完成。", Map.of(
                        "firstResponseTimeMs", taskInfo.firstResponseTimeMs().get(),
                        "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            })
            .doOnError(error -> {
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(answerStage, "答案生成失败。", error.getMessage(), null);
                }
            });
    }
}
