package org.javaup.ai.chatagent.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.dto.ConversationExchangeDetailQueryDto;
import org.javaup.ai.chatagent.dto.ConversationIdentityDto;
import org.javaup.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.javaup.ai.chatagent.dto.RetrievalObserveQueryDto;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.ConversationExchangeDetailView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.StageBenchmarkView;
import org.javaup.ai.chatagent.service.BusinessChatService;
import org.javaup.ai.chatagent.vo.ConversationResetVo;
import org.javaup.ai.chatagent.vo.ConversationSessionListVo;
import org.javaup.ai.chatagent.vo.ConversationStopVo;
import org.javaup.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 业务聊天控制器 - SSE 流式对话入口
 *
 * <pre>
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║                    POST /api/chat/stream (SSE)                               ║
 * ║           入参: question, conversationId, chatMode, selectedDocumentId        ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *                                     │
 *                                     ▼
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  阶段一: 启动保护 (BusinessChatService.openDeferredConversationStream)       ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  1. buildLaunchPlan(request)                                                ║
 * ║     ├─ normalizeQuestion(question)        trim + 非空校验                     ║
 * ║     ├─ normalizeConversationId(id)        有就用 / 没有生成UUID(去横线)        ║
 * ║     ├─ parseRequiredChatMode(mode)        OPEN_CHAT/DOCUMENT/AUTO_DOCUMENT   ║
 * ║     ├─ resolveSelectedDocument(mode,id)   校验文档合法性                       ║
 * ║     ├─ LocalDate.now(Asia/Shanghai)       当前日期                            ║
 * ║     ├─ formatCurrentDate(date)            "2026-06-26（星期四）"             ║
 * ║     └─ 返回 StreamLaunchPlan{question, conversationId, chatMode, ...}       ║
 * ║                                                                              ║
 * ║  2. claimConversationLease(plan)                                             ║
 * ║     └─ redisLeaseManager.acquire(key, token, TTL=30s)                       ║
 * ║        ├─ 成功 → 继续                                                        ║
 * ║        └─ 失败 → return rejectionFlux("该会话正在执行中，请稍后再试")           ║
 * ║                                                                              ║
 * ║  3. bootstrapConversation(plan)                                              ║
 * ║     ├─ 3a. archiveStore.startExchange(...)                                  ║
 * ║     │      ├─ upsertDialogue (没有→INSERT, 有且变了→UPDATE, 没变→跳过)        ║
 * ║     │      └─ INSERT exchange (状态=RUNNING, answer/thinking全空占位)         ║
 * ║     │                                                                        ║
 * ║     ├─ 3b. createTaskInfo(plan, exchangeView)                               ║
 * ║     │      ├─ new Sinks.many().unicast().onBackpressureBuffer()  // SSE管道  ║
 * ║     │      ├─ buildSessionConfig(conversationId)  // RunnableConfig         ║
 * ║     │      ├─ new ConversationTraceRecorder(...)   // 阶段追踪器              ║
 * ║     │      ├─ new StreamEventMetadata(cid, eid)    // 事件元数据              ║
 * ║     │      ├─ config.context().put(sink, metadata, thinkingSteps, ...)      ║
 * ║     │      └─ new TaskInfo(所有对象打包)                                      ║
 * ║     │                                                                        ║
 * ║     ├─ 3c. chatRuntimeRegistry.register(taskInfo)                           ║
 * ║     │      ├─ 成功 (putIfAbsent → null) → 继续                               ║
 * ║     │      └─ 失败 (已存在) → failBootstrappedExchange + 释放租约 + 拒绝      ║
 * ║     │                                                                        ║
 * ║     └─ 3d. bindClientChannel(taskInfo)                                      ║
 * ║            └─ sink.asFlux()                                                 ║
 * ║                 .doOnSubscribe → activateGeneration                          ║
 * ║                 .doOnCancel    → stopTask("客户端已取消请求")                  ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *                                     │
 *                                     │ SSE连接建立, doOnSubscribe触发
 *                                     ▼
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  阶段二: 激活执行 (BusinessChatService.activateGeneration)                    ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  1. startLeaseRenewal(taskInfo)                                             ║
 * ║     └─ Flux.interval(10s, 10s) → renewLeaseOrStop(taskInfo)                ║
 * ║        ├─ redisLeaseManager.renew(key, token, TTL=30s) → 成功则继续          ║
 * ║        └─ 续约失败 → stopTask("租约已失效")                                   ║
 * ║                                                                              ║
 * ║  2. buildConversationExecution(taskInfo).subscribe()                        ║
 * ║     └─ 见下方 "阶段三"                                                        ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *                                     │
 *                                     ▼
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  阶段三: 执行管道 (BusinessChatService.buildConversationExecution)            ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  Flux.defer(() → {                                                          ║
 * ║                                                                              ║
 * ║    1. emit thinking("正在分析问题上下文")  // SSE 思考事件                     ║
 * ║                                                                              ║
 * ║    2. prepareExecutionPlan(taskInfo)  // 阻塞调用, boundedElastic线程池       ║
 * ║       └─ chatPreparationOrchestrator.prepare(taskInfo)                      ║
 * ║          见下方 "阶段四: 编排决策"                                             ║
 * ║                                                                              ║
 * ║    3. executorRegistry.get(plan.mode)  // 按 ExecutionMode 选执行器          ║
 * ║                                                                              ║
 * ║    4. executor.execute(taskInfo)  // 流式文本 Flux                            ║
 * ║       └─ 见下方 "阶段五: 执行器执行"                                           ║
 * ║                                                                              ║
 * ║  })                                                                          ║
 * ║  .publishOn(Schedulers.boundedElastic())                                    ║
 * ║  .doOnNext(chunk → emitModelChunk(taskInfo, chunk))                         ║
 * ║     ├─ answerBuffer.append(chunk)        // 累积完整回答                       ║
 * ║     ├─ firstResponseTimeMs 首次记录       // CAS 防重复                        ║
 * ║     └─ sink.emitNext(streamEventWriter.text(chunk))  // SSE → 客户端          ║
 * ║  .doOnError(error → finishWithFailure(taskInfo, error))                     ║
 * ║  .doOnComplete(() → finishSuccessfully(taskInfo))                           ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *                                     │
 *                                     ▼
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  阶段四: 编排决策 (ChatPreparationOrchestrator.prepare)                        ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  步骤① summarizeHistory(conversationId)                                     ║
 * ║    └─ conversationMemoryService.loadMemoryContext()                         ║
 * ║       读取 memory_summary 表 (长期摘要) + exchange 表 (近期窗口)               ║
 * ║                                                                              ║
 * ║  步骤② 构建历史上下文                                                          ║
 * ║    ├─ buildHistoryPlanningContext(memoryContext)                            ║
 * ║    │   结构化: conversationGoal / stableFacts / pendingQuestions             ║
 * ║    └─ buildPlanningHistory(memoryContext, planningContext)                  ║
 * ║       精炼文本, 按 Token 预算裁剪 (clipHead + clipTail)                       ║
 * ║                                                                              ║
 * ║  步骤③ 时间感知判断                                                           ║
 * ║    ├─ TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question)       ║
 * ║    └─ TimeSensitiveQueryHelper.requiresFreshSearch(question)                ║
 * ║                                                                              ║
 * ║  步骤④ 按 chatMode 分派 ──────────────────────────────────────────────────┐  ║
 * ║  ┌────────────────────────────────────────────────────────────────────────┐  ║
 * ║  │                                                                         │  ║
 * ║  │  ┌─ OPEN_CHAT ───────────────────────────────────────────────────┐     │  ║
 * ║  │  │  mode = REACT_AGENT                                            │     │  ║
 * ║  │  │  直接返回 plan, 跳过改写和检索                                    │     │  ║
 * ║  │  │  → ReactAgentExecutor                                          │     │  ║
 * ║  │  └────────────────────────────────────────────────────────────────┘     │  ║
 * ║  │                                                                         │  ║
 * ║  │  ┌─ AUTO_DOCUMENT ───────────────────────────────────────────────┐     │  ║
 * ║  │  │  knowledgeRouteService.route(question, rewriteQuestion)        │     │  ║
 * ║  │  │    → KnowledgeRouteDecision{confidence, documents}             │     │  ║
 * ║  │  │                                                                 │     │  ║
 * ║  │  │  selectAutoCandidates(decision) → List<DocumentRouteCandidate> │     │  ║
 * ║  │  │                                                                 │     │  ║
 * ║  │  │  shouldAskClarification?                                        │     │  ║
 * ║  │  │  ├─ YES (置信度<0.55 或 多候选歧义)                             │     │  ║
 * ║  │  │  │   → mode = CLARIFICATION                                   │     │  ║
 * ║  │  │  │   → buildClarificationReply("你想问哪份文档? 1.A 2.B")       │     │  ║
 * ║  │  │  │   → ClarificationExecutor (不调LLM, 直接返回)                │     │  ║
 * ║  │  │  │                                                              │     │  ║
 * ║  │  │  └─ NO  (置信度≥0.55 且确定)                                    │     │  ║
 * ║  │  │      → 确定 topDocument, 覆盖 routedDocumentId                  │     │  ║
 * ║  │  │      → 继续走文档路由 (同 DOCUMENT 路径)                          │     │  ║
 * ║  │  └────────────────────────────────────────────────────────────────┘     │  ║
 * ║  │                                                                         │  ║
 * ║  │  ┌─ DOCUMENT (指定文档) / AUTO_DOCUMENT确定文档后 ──────────────────┐   │  ║
 * ║  │  │                                                                  │   │  ║
 * ║  │  │  步骤⑤ chatQueryRewriteService.rewrite(question, historySummary) │   │  ║
 * ║  │  │    LLM 将口语问题 → 检索友好查询 + 拆分子问题                       │   │  ║
 * ║  │  │    出参: RagRewriteResult{rewrittenQuestion, subQuestions}       │   │  ║
 * ║  │  │                                                                  │   │  ║
 * ║  │  │  步骤⑥ documentQuestionRouter.route(docId, question, rewrite)    │   │  ║
 * ║  │  │    判断执行方式:                                                   │   │  ║
 * ║  │  │    ├─ GRAPH_ONLY          → GraphOnlyExecutor                   │   │  ║
 * ║  │  │    ├─ GRAPH_THEN_EVIDENCE → GraphThenEvidenceExecutor           │   │  ║
 * ║  │  │    └─ RETRIEVAL           → RagChatExecutor                     │   │  ║
 * ║  │  │                                                                  │   │  ║
 * ║  │  └──────────────────────────────────────────────────────────────────┘   │  ║
 * ║  └────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                              ║
 * ║  步骤⑦ 组装 ConversationExecutionPlan (Builder模式, 30个字段)                ║
 * ║    basePlan → .mode(executionMode) → .navigationDecision(...) → .build()   ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *                                     │
 *                                     ▼
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  阶段五: 执行器执行 (5选1, 按 ExecutionMode)                                   ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  ┌─ REACT_AGENT → ReactAgentExecutor.execute(taskInfo) ──────────────────┐  ║
 * ║  │  适用: OPEN_CHAT 模式                                                   │  ║
 * ║  │                                                                         │  ║
 * ║  │  1. publishThinking("进入开放式Agent自主执行阶段")                        │  ║
 * ║  │                                                                         │  ║
 * ║  │  2. reactAgent.stream(agentQuestion, runnableConfig)                   │  ║
 * ║  │     内部执行 ReAct 循环:                                                 │  ║
 * ║  │       Thought(思考) → Action(选工具) → ActionInput(填参数)               │  ║
 * ║  │       → Observation(工具结果) → [循环直到得出 Final Answer]              │  ║
 * ║  │     输出: NodeOutput 流 (含多种类型)                                     │  ║
 * ║  │                                                                         │  ║
 * ║  │  3. extractTextChunk(output, streamedText)                             │  ║
 * ║  │     ├─ 非 StreamingOutput → 跳过                                        │  ║
 * ║  │     ├─ AGENT_MODEL_STREAMING   → 返回文本块 (标记已流式)                  │  ║
 * ║  │     ├─ AGENT_MODEL_FINISHED    → 如果已流式过则跳过(去重)                 │  ║
 * ║  │     └─ TOOL_CALL_START/RESULT → 跳过(不推文本)                          │  ║
 * ║  └─────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                              ║
 * ║  ┌─ RETRIEVAL → RagChatExecutor.execute(taskInfo) ───────────────────────┐  ║
 * ║  │  适用: DOCUMENT / AUTO_DOCUMENT (走混合检索)                             │  ║
 * ║  │                                                                         │  ║
 * ║  │  步骤① RagRetrievalEngine.retrieve(plan)     // 阻塞I/O, elastic线程池  │  ║
 * ║  │    ├─ 结构图通道: 基于文档结构图, 定位相关章节/编号项                       │  ║
 * ║  │    ├─ 向量语义通道: 基于 embedding 相似度检索 chunk                        │  ║
 * ║  │    ├─ RRF 融合: 多通道结果去重 + 排名融合                                  │  ║
 * ║  │    └─ Rerank 精排: 对融合结果进行重排序                                    │  ║
 * ║  │    出参: RagRetrievalContext{检索备注, 引用列表, 通道使用记录}              │  ║
 * ║  │                                                                         │  ║
 * ║  │  步骤② 证据处理                                                          │  ║
 * ║  │    ├─ context.isEmpty()                                                 │  ║
 * ║  │    │   → Flux.just(noEvidenceReply)     // 直接返回兜底文本, 不调LLM     │  ║
 * ║  │    │                                                                     │  ║
 * ║  │    └─ context 有结果                                                     │  ║
 * ║  │        ├─ references.addAll(context.flattenReferences())   // 加入引用    │  ║
 * ║  │        ├─ usedTools.addAll(context.usedChannels)           // 记录通道    │  ║
 * ║  │        └─ 继续 ↓                                                        │  ║
 * ║  │                                                                         │  ║
 * ║  │  步骤③ RagPromptAssemblyService.assemble(plan, context)                 │  ║
 * ║  │    按 Token 预算组装 Prompt:                                              │  ║
 * ║  │    ├─ systemPrompt: 系统指令 + 引用规范                                    │  ║
 * ║  │    ├─ userPrompt: 用户问题 + 历史上下文 + 结构化检索证据                     │  ║
 * ║  │    ├─ renderedReferences: 预算内选入的引用                                  │  ║
 * ║  │    └─ omittedReferences: 超出预算被丢弃的引用                               │  ║
 * ║  │                                                                         │  ║
 * ║  │  步骤④ ObservedChatModelService.streamText("rag_answer", sys, usr)      │  ║
 * ║  │    → 底层调用 LLM API (DashScope/通义千问), 返回 Flux<String>            │  ║
 * ║  │    → 每条 chunk 经 doOnNext → emitModelChunk → SSE → 客户端             │  ║
 * ║  └─────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                              ║
 * ║  ┌─ CLARIFICATION → ClarificationExecutor.execute(taskInfo) ──────────────┐  ║
 * ║  │  适用: AUTO_DOCUMENT 置信度不足, 文档范围有歧义                             │  ║
 * ║  │  直接返回 Flux.just(clarificationReply)   // 不调用 LLM                  │  ║
 * ║  │  例: "你想问哪份文档? 1.《A》2.《B》"                                      │  ║
 * ║  └─────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                              ║
 * ║  ┌─ GRAPH_ONLY → GraphOnlyExecutor.execute(taskInfo) ──────────────────────┐  ║
 * ║  │  适用: 问题可直接通过文档结构图回答 (如"第三章有哪些小节?")                   │  ║
 * ║  │  ├─ findSectionWithSiblings/Children(docId, sectionNodeId)              │  ║
 * ║  │  └─ graphAnswerRenderer.renderGraphAnswer(mode, decision, graphResult)  │  ║
 * ║  │  不调用 LLM                                                                │  ║
 * ║  └─────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                              ║
 * ║  ┌─ GRAPH_THEN_EVIDENCE → GraphThenEvidenceExecutor.execute(taskInfo) ─────┐  ║
 * ║  │  适用: 需精确定位章节/编号项 + 校验证据 (如"第4.2节的第3项是什么?")          │  ║
 * ║  │  ├─ buildGraphResult(docId, sectionNodeId, itemIndex, keyword)          │  ║
 * ║  │  ├─ hasGraphEvidence(result) → NO → 返回 noEvidenceReply               │  ║
 * ║  │  └─ graphAnswerRenderer.renderGraphAnswer(...)  // 不调用 LLM           │  ║
 * ║  └─────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *                                     │
 *                                     │ Flux<String> 文本流
 *                                     ▼
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  阶段六: SSE 推送与收尾                                                       ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  每条文本的旅程:                                                               ║
 * ║  Executor chunk → emitModelChunk → answerBuffer.append →                    ║
 * ║    StreamEventWriter.text(chunk) → JSON → sink.emitNext → SSE → 客户端       ║
 * ║                                                                              ║
 * ║  ╔══════════════════════════════════════════════════════════════════════╗    ║
 * ║  ║  三种收尾出口 (互斥, CAS 保证只走一个)                                 ║    ║
 * ║  ╠════════════════╦══════════════════════╦══════════════════════════════╣    ║
 * ║  ║  正常完成       ║  异常失败             ║  客户端取消                    ║    ║
 * ║  ║  finishSuccess  ║  finishWithFailure   ║  stopTask                     ║    ║
 * ║  ╠════════════════╬══════════════════════╬══════════════════════════════╣    ║
 * ║  ║                ║                      ║                               ║    ║
 * ║  ║ ① finalized    ║ ① finalized CAS      ║ ① finalized CAS              ║    ║
 * ║  ║    CAS防重入    ║                      ║                               ║    ║
 * ║  ║                ║                      ║ ② interrupt Agent            ║    ║
 * ║  ║ ② 生成推荐追问  ║ ② 发送 error 事件    ║                               ║    ║
 * ║  ║    recommend.   ║    SSE sink.emit     ║ ③ dispose 订阅链              ║    ║
 * ║  ║    generate(...)║                      ║                               ║    ║
 * ║  ║                ║ ③ safeComplete(sink)  ║ ④ 发送 status 事件            ║    ║
 * ║  ║ ③ 补发references║    关闭 SSE 流        ║    "⏹ 用户已停止"             ║    ║
 * ║  ║    事件到客户端  ║                      ║                               ║    ║
 * ║  ║                ║                      ║ ⑤ safeComplete(sink)          ║    ║
 * ║  ║ ④ 补发recommend ║                      ║                               ║    ║
 * ║  ║    事件到客户端  ║                      ║                               ║    ║
 * ║  ║                ║                      ║                               ║    ║
 * ║  ║ ⑤ safeComplete  ║                      ║                               ║    ║
 * ║  ║    关闭 SSE 流   ║                      ║                               ║    ║
 * ║  ║                ║                      ║                               ║    ║
 * ║  ║ ⑥ 落库          ║ ④ 落库               ║ ⑥ 落库                       ║    ║
 * ║  ║  archiveStore   ║  archiveStore        ║  archiveStore                 ║    ║
 * ║  ║  .completeExch  ║  .completeExchange   ║  .completeExchange            ║    ║
 * ║  ║  (COMPLETED)    ║  (FAILED)            ║  (STOPPED)                    ║    ║
 * ║  ║                ║                      ║                               ║    ║
 * ║  ║ ⑦ 异步刷新摘要  ║ ⑤ 异步刷新摘要        ║ ⑦ 异步刷新摘要                 ║    ║
 * ║  ║  memoryService  ║                      ║                               ║    ║
 * ║  ║  .refreshAsync  ║                      ║                               ║    ║
 * ║  ║                ║                      ║                               ║    ║
 * ║  ║ ⑧ cleanup:     ║ ⑥ cleanup:           ║ ⑧ cleanup:                    ║    ║
 * ║  ║  ├─ dispose租约 ║  ├─ dispose租约       ║  ├─ dispose租约                ║    ║
 * ║  ║  ├─ dispose订阅 ║  ├─ dispose订阅       ║  ├─ dispose订阅                ║    ║
 * ║  ║  ├─ release租约 ║  ├─ release租约       ║  ├─ release租约                ║    ║
 * ║  ║  └─ 注销运行时   ║  └─ 注销运行时        ║  └─ 注销运行时                 ║    ║
 * ║  ╚════════════════╩══════════════════════╩══════════════════════════════╝    ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 * </pre>
 *
 * @author 阿星不是程序员
 **/
@AllArgsConstructor
@RestController
@RequestMapping("/api/chat")
public class BusinessChatController {

    private final BusinessChatService businessChatService;

    /**
     * SSE 流式对话（POST /api/chat/stream）
     * <p>
     * 接收用户问题，通过 {@link Flux#defer} 延迟执行，返回 SSE 事件流。
     * 每个元素是一条 JSON 格式的 SSE 事件（text/thinking/error/reference/recommend）。
     * <p>
     * <b>入参：</b>
     * <ul>
     *   <li>question（必填）：用户问题</li>
     *   <li>conversationId（可选，不传自动生成 UUID）</li>
     *   <li>chatMode（必填）：OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT</li>
     *   <li>selectedDocumentId（DOCUMENT 模式下必填）</li>
     * </ul>
     * <p>
     * <b>链路：</b>openConversationStream → 租约加锁 → 引导启动 → 执行计划编排 → 执行器执行 → 收尾
     *
     * @param dto 聊天请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ChatRequestDto dto) {
        return businessChatService.openConversationStream(dto);
    }

    @PostMapping("/document/options")
    public ApiResponse<List<KnowledgeDocumentOptionView>> documentOptions() {
        return ApiResponse.ok(businessChatService.listKnowledgeDocumentOptions());
    }

    @PostMapping("/session/stop")
    public ApiResponse<ConversationStopVo> stop(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.stopConversation(dto.getConversationId()));
    }

    @PostMapping("/session/detail")
    public ApiResponse<ConversationSessionView> session(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.getSession(dto.getConversationId()));
    }

    @PostMapping("/exchange/detail")
    public ApiResponse<ConversationExchangeDetailView> exchange(@Valid @RequestBody ConversationExchangeDetailQueryDto dto) {
        return ApiResponse.ok(businessChatService.getExchangeDetail(dto.getConversationId(), dto.getExchangeId()));
    }

    @PostMapping("/session/list")
    public ApiResponse<ConversationSessionListVo> sessions(@RequestBody(required = false) ConversationSessionListQueryDto dto) {
        return ApiResponse.ok(businessChatService.listSessions(dto));
    }

    @PostMapping("/session/reset")
    public ApiResponse<ConversationResetVo> reset(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.resetConversation(dto.getConversationId()));
    }

    @PostMapping("/session/summary/rebuild")
    public ApiResponse<ConversationMemorySummaryView> rebuildSummary(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.rebuildConversationSummary(dto.getConversationId()));
    }

    @PostMapping("/exchange/retrieval/results")
    public ApiResponse<List<RetrievalResultView>> retrievalResults(@Valid @RequestBody RetrievalObserveQueryDto dto) {
        return ApiResponse.ok(businessChatService.getRetrievalResults(dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @PostMapping("/exchange/channel/executions")
    public ApiResponse<List<ChannelExecutionView>> channelExecutions(@Valid @RequestBody RetrievalObserveQueryDto dto) {
        return ApiResponse.ok(businessChatService.getChannelExecutions(dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @PostMapping("/stage/benchmarks")
    public ApiResponse<List<StageBenchmarkView>> stageBenchmarks() {
        return ApiResponse.ok(businessChatService.getStageBenchmarks());
    }
}
