package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.enums.ChatQueryMode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天准备编排器 - 调用链路第5层
 * <p>
 * <b>在整个调用链路中的角色：</b>
 * 在 Executor 执行之前，完成所有"理解用户意图 + 决定怎么执行"的准备工作。
 * <p>
 * <b>核心方法：</b>{@link #prepare(TaskInfo)} — 返回 {@link ConversationExecutionPlan}
 * <p>
 * <b>准备流程（按 chatMode 分派）：</b>
 * <pre>
 * prepare(taskInfo)
 *   │
 *   ├─ 1. summarizeHistory                     ← 装载会话记忆（长期摘要 + 近期窗口）
 *   │     └─ conversationMemoryService.loadMemoryContext(conversationId)
 *   │
 *   ├─ 2. 时间感知判断                           ← TimeSensitiveQueryHelper
 *   │     ├─ requiresCurrentDateAnchoring: 问题是否涉及"今天""当前"等时间词
 *   │     └─ requiresFreshSearch: 问题是否涉及"最新""实时"等实时性词
 *   │
 *   ├─ 3. 按 chatMode 分派路由：
 *   │     │
 *   │     ├─ OPEN_CHAT 模式：
 *   │     │   └─ mode = ExecutionMode.REACT_AGENT
 *   │     │      → ReactAgentExecutor.execute()
 *   │     │         └─ reactAgent.stream(agentQuestion, config)
 *   │     │            内部：LLM 自主决定工具调用（联网搜索等）
 *   │     │
 *   │     ├─ AUTO_DOCUMENT 模式（自动知识库）：
 *   │     │   ├─ knowledgeRouteService.route(question, rewriteQuestion)
 *   │     │   │   → 返回 KnowledgeRouteDecision（含置信度 + 候选文档列表）
 *   │     │   ├─ selectAutoCandidates → 筛选候选文档
 *   │     │   ├─ shouldAskClarification? → 置信度 &lt; 0.55 或多候选歧义
 *   │     │   │   ├─ YES → mode = ExecutionMode.CLARIFICATION
 *   │     │   │   │       → ClarificationExecutor.execute()
*      │     │   │   │          直接返回澄清问题文本（不调用 LLM）
 *   │     │   │   └─ NO  → mode = ExecutionMode.RETRIEVAL
 *   │     │   │            → 继续走下面的文档路由
 *   │     │   └─ 选出 topDocument，继续走文档路由
 *   │     │
 *   │     └─ DOCUMENT 模式（指定文档）/ AUTO_DOCUMENT（确定文档后）：
 *   │         ├─ chatQueryRewriteService.rewrite(question, historySummary)
 *   │         │   → RagRewriteResult{rewrittenQuestion, subQuestions}
 *   │         │      LLM 将口语化问题改写为检索友好的结构化查询
 *   │         │
 *   │         └─ documentQuestionRouter.route(documentId, question, rewriteResult)
 *   │             → DocumentNavigationDecision{
 *   │                 executionMode: GRAPH_ONLY | GRAPH_THEN_EVIDENCE | RETRIEVAL,
 *   │                 retrievalPlan: {retrievalQuestion, subQuestions},
 *   │                 structureAnchor: {structureNodeId, targetSectionHint},
 *   │                 itemAnchor: {itemIndex}
 *   │               }
 *   │               判断：结构图直接回答？结构图定位+取证？还是走混合检索？
 *   │
 *   └─ 4. 构建 ConversationExecutionPlan
 *       含：mode, rewriteQuestion, retrievalQuestion, navigationDecision,
 *           selectedDocument, historySummary, noEvidenceReply 等
 * </pre>
 *
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层 - 对话执行计划编排，负责在 LLM 执行前完成意图理解和路由决策
 * @author: wangpeng
 **/

@Slf4j
@Service
public class ChatPreparationOrchestrator {

    private static final Set<String> CAPABILITY_HINTS = Set.of(
        "你都能干什么", "你能做什么", "你可以做什么", "你会什么", "你是谁", "怎么用你", "你能帮我什么"
    );

    private static final Set<String> OPEN_CHAT_HINTS = Set.of(
        "天气", "温度", "下雨", "新闻", "股价", "汇率", "热搜", "今天", "明天", "最新", "现在"
    );

    private static final Set<String> CHITCHAT_HINTS = Set.of(
        "你好", "您好", "hello", "hi", "谢谢", "感谢", "再见", "拜拜"
    );

    private final ChatRagProperties properties;
    private final ConversationMemoryService conversationMemoryService;
    private final AnswerHistoryContextAssembler answerHistoryContextAssembler;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final DocumentQuestionRouter documentQuestionRouter;
    private final KnowledgeRouteService knowledgeRouteService;
    private final DocumentKnowledgeService documentKnowledgeService;

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ConversationMemoryService conversationMemoryService,
                                       AnswerHistoryContextAssembler answerHistoryContextAssembler,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       DocumentQuestionRouter documentQuestionRouter,
                                       KnowledgeRouteService knowledgeRouteService,
                                       DocumentKnowledgeService documentKnowledgeService) {
        this.properties = properties;
        this.conversationMemoryService = conversationMemoryService;
        this.answerHistoryContextAssembler = answerHistoryContextAssembler;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.documentQuestionRouter = documentQuestionRouter;
        this.knowledgeRouteService = knowledgeRouteService;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    /**
     * 调用链路第5层核心方法 - 准备执行计划
     * <p>
     * <b>这是对话的"大脑"——在真正调用 LLM 之前，决定走哪条执行路径。</b>
     * <p>
     * <b>6 步准备流程：</b>
     * <ol>
     *   <li><b>会话记忆装载</b>（MEMORY 阶段）：
     *       summarizeHistory → conversationMemoryService.loadMemoryContext
     *       返回 ConversationMemoryContext{长期摘要, 近期对话窗口, 压缩信息}</li>
     *   <li><b>历史规划上下文构建</b>：
     *       从摘要中提取 conversationGoal（会话目标）、stableFacts（已确认事实）、
     *       pendingQuestions（待跟进问题）、retrievalHints（检索提示）</li>
     *   <li><b>时间感知判断</b>：
     *       TimeSensitiveQueryHelper 判断问题是否涉及当日信息或需要实时搜索</li>
     *   <li><b>模式路由（核心分派逻辑）</b>：
     *       <ul>
     *         <li>OPEN_CHAT → REACT_AGENT：Agent 自主决定工具调用</li>
     *         <li>AUTO_DOCUMENT → 先走 KnowledgeRouteService 自动选择文档：
     *           置信度 &lt; 0.55 或多候选歧义 → CLARIFICATION 先问用户
     *           置信度 ≥ 0.55 且唯一 → 走文档路由</li>
     *         <li>DOCUMENT / 确定文档的 AUTO_DOCUMENT → 走文档路由</li>
     *       </ul>
     *   </li>
     *   <li><b>问题改写</b>（非 OPEN_CHAT 模式，REWRITE 阶段）：
     *       chatQueryRewriteService.rewrite(原始问题, 历史摘要)
     *       LLM 将口语化问题改写成检索友好的结构化查询 + 拆分子问题</li>
     *   <li><b>文档路由决策</b>（ROUTE 阶段）：
     *       documentQuestionRouter.route(documentId, question, rewriteResult)
     *       判断应该走 GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL</li>
     * </ol>
     * <p>
     * <b>产出：</b>{@link ConversationExecutionPlan} — 包含 ExecutionMode 和所有上下文信息，
     * 后续由 ConversationExecutorRegistry 根据 mode 选择对应的执行器执行。
     *
     * @param taskInfo 运行时任务上下文（含 question, chatMode, traceRecorder 等）
     * @return 完整的执行计划
     */
    public ConversationExecutionPlan prepare(TaskInfo taskInfo) {
        // ── 提取 taskInfo 中的关键参数 ──
        String conversationId = taskInfo.conversationId();
        String question = taskInfo.question();           // 用户原始提问
        ChatQueryMode chatMode = taskInfo.chatMode();    // OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT
        Long selectedDocumentId = taskInfo.selectedDocumentId();
        String selectedDocumentName = taskInfo.selectedDocumentName();
        Long selectedTaskId = taskInfo.selectedTaskId();
        LocalDate currentDate = taskInfo.currentDate();
        String currentDateText = taskInfo.currentDateText();
        ConversationTraceRecorder traceRecorder = taskInfo.traceRecorder();

        // ═══════════════════════════════════════════════════════════════
        // 步骤 1/6：装载会话记忆（MEMORY 阶段）
        // ═══════════════════════════════════════════════════════════════
        // 从 DB 加载长期摘要 + 近期对话窗口，用于构建 LLM 的历史上下文
        ConversationTraceRecorder.StageHandle memoryStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.MEMORY, chatMode == null ? "" : chatMode.name(), "正在装载会话记忆与最近窗口。", null);
        ConversationMemoryContext memoryContext;
        try {
            // 调用记忆服务：读取 memory_summary 表 + 近期 exchange 表数据
            memoryContext = summarizeHistory(conversationId, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(memoryStage, "会话记忆装载完成。", Map.of(
                    "compressionApplied", memoryContext != null && memoryContext.isCompressionApplied(),
                    "coveredExchangeId", memoryContext == null ? 0L : memoryContext.getCoveredExchangeId(),
                    "coveredExchangeCount", memoryContext == null ? 0 : memoryContext.getCoveredExchangeCount(),
                    "compressionCount", memoryContext == null ? 0 : memoryContext.getCompressionCount(),
                    "longTermSummary", memoryContext == null ? "" : safeText(memoryContext.getLongTermSummary()),
                    "recentTranscript", memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript()),
                    "answerRecentTranscript", memoryContext == null ? "" : safeText(memoryContext.getAnswerRecentTranscript())
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(memoryStage, "会话记忆装载失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        // ═══════════════════════════════════════════════════════════════
        // 步骤 2/6：构建历史上下文
        // ═══════════════════════════════════════════════════════════════
        // 历史规划上下文：结构化的会话目标、已确认事实、待跟进问题（给导航决策用的）
        HistoryPlanningContext historyPlanningContext = buildHistoryPlanningContext(memoryContext);
        // 历史摘要：精炼后的近期对话文本（注入到 LLM Prompt 中）
        String historySummary = buildPlanningHistory(memoryContext, historyPlanningContext);
        // 回答历史上下文：判断当前问题是否为追问（如"那第二个呢？"）
        AnswerHistoryContext answerHistoryContext = buildAnswerHistoryContext(
            question,
            memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript()
        );

        // ═══════════════════════════════════════════════════════════════
        // 步骤 3/6：时间感知判断
        // ═══════════════════════════════════════════════════════════════
        // 判断问题是否涉及"今天"/"当前"等时间词 → 需要日期锚定
        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        // 判断问题是否涉及"最新"/"实时"等词 → 需要实时联网搜索
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);

        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }

        // ═══════════════════════════════════════════════════════════════
        // 步骤 4/6：按 chatMode 分派路由（核心决策！）
        // ═══════════════════════════════════════════════════════════════

        // ────────── 路径 A：OPEN_CHAT 开放式提问 ──────────
        // 不需要改写、不需要检索，直接把问题交给 ReactAgent
        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            ConversationExecutionPlan plan = basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .mode(ExecutionMode.REACT_AGENT)  // 走 ReactAgentExecutor
                .build();
            if (traceRecorder != null) {
                ConversationTraceRecorder.StageHandle routeStage = traceRecorder.startStage(ConversationTraceStageCode.ROUTE, ExecutionMode.REACT_AGENT.name(), "路由到开放式 Agent。", null);
                traceRecorder.completeStage(routeStage, "已判定走开放式 Agent 路径。", Map.of(
                    "chatMode", chatMode.name(),
                    "executionMode", ExecutionMode.REACT_AGENT.name(),
                    "requiresFreshSearch", requiresFreshSearch,
                    "requiresCurrentDateAnchoring", requiresCurrentDateAnchoring
                ));
            }
            return plan;  // 提前返回，不执行后续的改写和检索逻辑
        }

        // ────────── 文档模式前置校验 ──────────
        // RAG 编排功能必须已启用
        if (!properties.isEnabled()) {
            throw new IllegalStateException("当前文档问答模式未启用，请先开启聊天侧 RAG 编排");
        }
        // DOCUMENT 模式（显式指定文档）必须传 selectedDocumentId 和 selectedTaskId
        if (chatMode == ChatQueryMode.DOCUMENT && (selectedDocumentId == null || selectedTaskId == null)) {
            throw new IllegalArgumentException("当前文档问答模式缺少有效的文档范围");
        }

        // ═══════════════════════════════════════════════════════════════
        // 步骤 5/6：问题改写（REWRITE 阶段）—— 非 OPEN_CHAT 模式都要改写
        // ═══════════════════════════════════════════════════════════════
        // 调用 LLM 将口语化问题改写为检索友好的结构化查询 + 拆分子问题
        ConversationTraceRecorder.StageHandle rewriteStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.REWRITE,
                ExecutionMode.RETRIEVAL.name(),
                "正在生成检索友好的问题表达。",
                buildRewriteStageSnapshot(question, historySummary, null)  // 改写前的快照（问题+历史上下文）
            );
        RagRewriteResult rewriteResult;
        try {
            // LLM 改写：原始问题 + 历史摘要 → 改写问题 + 子问题列表
            rewriteResult = chatQueryRewriteService.rewrite(question, historySummary, traceRecorder);
            if (traceRecorder != null) {
                // 记录改写后的快照（含 rewrittenQuestion + subQuestions + 原始模型输出）
                traceRecorder.completeStage(rewriteStage, "问题改写完成。", buildRewriteStageSnapshot(question, historySummary, rewriteResult));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(
                    rewriteStage,
                    "问题改写失败。",
                    exception.getMessage(),
                    buildRewriteStageSnapshot(question, historySummary, null)
                );
            }
            throw exception;
        }

        // 改写结果 → 提取改写后的问题和子问题
        String rewriteQuestion = rewriteResult == null ? safeText(question) : firstNonBlank(rewriteResult.getRewrittenQuestion(), safeText(question));
        List<String> rewriteSubQuestions = rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()
            ? List.of(rewriteQuestion)     // 没有子问题就用改写后的问题本身
            : rewriteResult.getSubQuestions();

        // ═══════════════════════════════════════════════════════════════
        // 步骤 6/6：文档路由决策（ROUTE 阶段）
        // ═══════════════════════════════════════════════════════════════

        // ────────── 初始化文档路由变量 ──────────
        // DOCUMENT 模式：直接用用户选定的文档（不变）
        // AUTO_DOCUMENT 模式：下面会通过 knowledgeRouteService 重新选定
        Long routedDocumentId = selectedDocumentId;
        String routedDocumentName = selectedDocumentName;
        Long routedTaskId = selectedTaskId;
        List<Long> routedDocumentIds = routedDocumentId == null ? List.of() : List.of(routedDocumentId);
        List<Long> routedTaskIds = routedTaskId == null ? List.of() : List.of(routedTaskId);

        if (chatMode == ChatQueryMode.AUTO_DOCUMENT) {
            // ─── 路径 B-1：检测用户是否在回复澄清时通过"我想问《xxx》"选择了文档 ───
            boolean userSelectedDoc = false;
            DocumentSelection docSelection = detectDocumentSelection(question);
            if (docSelection != null) {
                KnowledgeDocumentDescriptor selectedDoc = findDocumentByName(docSelection.documentName);
                if (selectedDoc != null) {
                    userSelectedDoc = true;
                    routedDocumentId = selectedDoc.getDocumentId();
                    routedDocumentName = selectedDoc.getDocumentName();
                    routedTaskId = selectedDoc.getLastIndexTaskId();
                    routedDocumentIds = List.of(routedDocumentId);
                    routedTaskIds = List.of(routedTaskId);

                    // ✨ 关键修复：把检索问题替换为用户上一轮的实际提问，而不是
                    // 当前"我想问《xxx》"这个文档选择语句。
                    // 否则检索时搜的是"我想问《个人房屋租赁合同》"，匹配到全文摘要而非具体答案。
                    String previousQuestion = extractPreviousQuestion(memoryContext);
                    if (StrUtil.isNotBlank(previousQuestion)) {
                        log.info("文档选择后将原问题替换为上一轮提问: previous='{}', current='{}'",
                            previousQuestion, question);
                        question = previousQuestion;
                        rewriteQuestion = previousQuestion;
                        rewriteSubQuestions = List.of(previousQuestion);
                        // 替换 rewriteResult 对象，避免下游 DocumentQuestionRouter
                        // 和检索引擎仍使用基于"我想问《xxx》"的错误改写结果
                        rewriteResult = new RagRewriteResult(previousQuestion, List.of(previousQuestion));
                    }

                    log.info("用户通过'{}'明确选择了文档: documentId={}, documentName={}",
                        docSelection.matchText, selectedDoc.getDocumentId(), selectedDoc.getDocumentName());
                    if (traceRecorder != null) {
                        traceRecorder.completeStage(
                            traceRecorder.startStage(ConversationTraceStageCode.ROUTE, "AUTO_DOCUMENT", "用户明确选择了文档。", null),
                            "用户通过「" + docSelection.matchText + "」选择了文档「" + selectedDoc.getDocumentName() + "」，跳过知识路由。",
                            Map.of(
                                "userSelection", docSelection.matchText,
                                "selectedDocumentName", selectedDoc.getDocumentName(),
                                "selectedDocumentId", selectedDoc.getDocumentId()
                            )
                        );
                    }
                } else {
                    log.warn("用户选择了文档但未在可检索文档中找到: docName='{}', question='{}'", docSelection.documentName, question);
                }
            }

            if (!userSelectedDoc) {
                // ─── 路径 B-2：AUTO_DOCUMENT 自动知识路由 ───
                // ① 调用知识路由服务，根据问题内容自动匹配最相关的文档
                KnowledgeRouteDecision routeDecision = knowledgeRouteService.route(question, rewriteQuestion);
                // ② 记录路由追踪（用于后续分析路由准确性）
                knowledgeRouteService.recordAutoRoute(conversationId, taskInfo.exchangeId(), question, rewriteQuestion, routeDecision);
                // ③ 筛选候选文档列表（已按文档名去重）
                List<DocumentRouteCandidate> candidateDocuments = selectAutoCandidates(routeDecision, question, rewriteQuestion);

                // ─── 判断是否需要向用户澄清 ───
                // 条件：置信度 < 0.55 或 候选文档 > 1 且 top2 分数接近
                if (shouldAskClarification(routeDecision, candidateDocuments)) {
                    // 返回 CLARIFICATION 执行计划：直接问用户想查哪个文档，不调 LLM
                    return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                        requiresCurrentDateAnchoring, requiresFreshSearch)
                        .mode(ExecutionMode.CLARIFICATION)  // 走 ClarificationExecutor
                        .rewriteQuestion(rewriteQuestion)
                        .rewriteSubQuestions(rewriteSubQuestions)
                        .retrievalQuestion(rewriteQuestion)
                        .retrievalSubQuestions(rewriteSubQuestions)
                        .retrievalDocumentIds(candidateDocuments.stream()  // 候选文档 ID 列表（已去重）
                            .map(DocumentRouteCandidate::getDocumentId)
                            .filter(StrUtil::isNotBlank)
                            .map(Long::valueOf)
                            .toList())
                        .retrievalTaskIds(candidateDocuments.stream()  // 候选文档的索引任务 ID 列表
                            .map(DocumentRouteCandidate::getLastIndexTaskId)
                            .filter(StrUtil::isNotBlank)
                            .map(Long::valueOf)
                            .toList())
                        .clarificationReply(buildClarificationReply(question, routeDecision, candidateDocuments))     // "你想问哪份文档？1. A 2. B"
                        .clarificationOptions(buildClarificationOptions(candidateDocuments))                          // ["我想问《A》", "我想问《B》"]
                        .clarificationReason(buildClarificationReason(routeDecision, candidateDocuments))             // "置信度 0.42，为避免误选..."
                        .build();
                }

                // ─── 不需要澄清 → 确定 top 文档 ───
                // 置信度 >= 0.55 且至少有 1 个候选 → 选第一个作为目标文档
                boolean confidentTopDocument = routeDecision != null
                    && routeDecision.getConfidence() != null
                    && routeDecision.getConfidence().doubleValue() >= 0.55D;
                DocumentRouteCandidate topDocument = confidentTopDocument && !candidateDocuments.isEmpty() ? candidateDocuments.get(0) : null;
                if (topDocument != null && StrUtil.isNotBlank(topDocument.getDocumentId()) && StrUtil.isNotBlank(topDocument.getLastIndexTaskId())) {
                    // 路由成功：用自动选定的文档替换 selectedDocument
                    routedDocumentId = Long.valueOf(topDocument.getDocumentId());
                    routedDocumentName = topDocument.getDocumentName();
                    routedTaskId = Long.valueOf(topDocument.getLastIndexTaskId());
                }
                else {
                    // 路由失败（置信度不足或候选为空）→ 清空文档信息，走兜底
                    routedDocumentId = null;
                    routedDocumentName = "";
                    routedTaskId = null;
                }
                // 候选文档列表（可能多个，用于后续的多文档检索）
                routedDocumentIds = candidateDocuments.stream()
                    .map(DocumentRouteCandidate::getDocumentId)
                    .filter(StrUtil::isNotBlank)
                    .map(Long::valueOf)
                    .toList();
                routedTaskIds = candidateDocuments.stream()
                    .map(DocumentRouteCandidate::getLastIndexTaskId)
                    .filter(StrUtil::isNotBlank)
                    .map(Long::valueOf)
                    .toList();

                if (traceRecorder != null) {
                    traceRecorder.completeStage(
                        traceRecorder.startStage(ConversationTraceStageCode.ROUTE, "AUTO_DOCUMENT", "正在生成知识范围候选。", null),
                        "知识范围路由完成。",
                        Map.of(
                            "confidence", routeDecision == null || routeDecision.getConfidence() == null ? "" : routeDecision.getConfidence().toPlainString(),
                            "routeStatus", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getRouteStatus(), ""),
                            "candidateDocumentCount", candidateDocuments.size(),
                            "confidentTopDocument", confidentTopDocument,
                            "topDocumentId", topDocument == null ? "" : StrUtil.blankToDefault(topDocument.getDocumentId(), ""),
                            "topDocumentName", topDocument == null ? "" : StrUtil.blankToDefault(topDocument.getDocumentName(), "")
                        )
                    );
                }
            }
        }
        else if (chatMode == ChatQueryMode.DOCUMENT) {
            // ─── 路径 C：DOCUMENT 显式指定文档 ───
            // 记录一条 shadow route trace（即使不自动路由，也记录用于分析）
            knowledgeRouteService.recordShadowRoute(conversationId, taskInfo.exchangeId(), selectedDocumentId, question, rewriteQuestion);
        }

        // ────────── 公共路径：文档路由决策（DOCUMENT + AUTO_DOCUMENT 确定文档后）──────────
        // 调用 documentQuestionRouter 判断具体执行方式：
        // GRAPH_ONLY（结构图直接回答）、GRAPH_THEN_EVIDENCE（结构图定位+校验）、RETRIEVAL（混合检索）
        ConversationTraceRecorder.StageHandle routeStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.ROUTE, ExecutionMode.RETRIEVAL.name(), "正在判定图查询还是混合检索。", null);
        DocumentNavigationDecision navigationDecision;
        try {
            // 核心：调用路由器判定执行模式
            navigationDecision = documentQuestionRouter.route(routedDocumentId, question, rewriteResult);
            if (traceRecorder != null) {
                traceRecorder.completeStage(routeStage, "执行路由完成。", Map.of(
                    "executionMode", navigationDecision == null || navigationDecision.getExecutionMode() == null ? "" : navigationDecision.getExecutionMode().name(),
                    "targetSectionHint", navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : StrUtil.blankToDefault(navigationDecision.getStructureAnchor().getTargetSectionHint(), ""),
                    "targetItemIndex", navigationDecision == null || navigationDecision.getItemAnchor() == null || navigationDecision.getItemAnchor().getItemIndex() == null
                        ? ""
                        : String.valueOf(navigationDecision.getItemAnchor().getItemIndex()),
                    "navigationSummary", navigationDecision == null ? "" : StrUtil.blankToDefault(navigationDecision.getSummaryText(), "")
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(routeStage, "执行路由失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        // ────────── 从路由决策中提取执行模式和检索参数 ──────────
        // 执行模式：默认为 RETRIEVAL（兜底策略），有决策就用决策的
        ExecutionMode executionMode = navigationDecision == null || navigationDecision.getExecutionMode() == null
            ? ExecutionMode.RETRIEVAL
            : navigationDecision.getExecutionMode();
        // 检索问题：路由器可能调整了检索问题（比改写更精确）
        String retrievalQuestion = navigationDecision == null || navigationDecision.getRetrievalPlan() == null
            ? rewriteQuestion
            : firstNonBlank(navigationDecision.getRetrievalPlan().getRetrievalQuestion(), rewriteQuestion);
        // 检索子问题：路由器可能调整了子问题拆分
        List<String> retrievalSubQuestions = navigationDecision == null || navigationDecision.getRetrievalPlan() == null
            || navigationDecision.getRetrievalPlan().getSubQuestions() == null || navigationDecision.getRetrievalPlan().getSubQuestions().isEmpty()
            ? rewriteSubQuestions
            : navigationDecision.getRetrievalPlan().getSubQuestions();

        log.info("聊天编排完成: conversationId={}, chatMode={}, originalQuestion='{}', rewriteQuestion='{}', retrievalQuestion='{}', executionMode={}, targetSection='{}'",
            conversationId,
            chatMode,
            safeText(question),
            rewriteQuestion,
            retrievalQuestion,
            executionMode,
            navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : safeText(navigationDecision.getStructureAnchor().getTargetSectionHint()));

        // ────────── 组装最终的 ConversationExecutionPlan ──────────
        // basePlan 填入公共字段（历史、时间感知、兜底文本），Builder 链式填入路由决策特有字段
        return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
            requiresCurrentDateAnchoring, requiresFreshSearch)
            .mode(executionMode)                          // ← 最终决定走哪个 Executor
            .navigationDecision(navigationDecision)       // ← 路由决策（含结构图锚点 + 检索计划）
            .rewriteQuestion(rewriteQuestion)             // ← 改写后的问题
            .rewriteSubQuestions(rewriteSubQuestions)     // ← 改写后的子问题
            .retrievalQuestion(retrievalQuestion)         // ← 最终检索问题
            .retrievalSubQuestions(retrievalSubQuestions) // ← 最终检索子问题
            .selectedDocumentId(routedDocumentId)         // ← 目标文档 ID
            .selectedDocumentName(routedDocumentName)     // ← 目标文档名
            .selectedTaskId(routedTaskId)                 // ← 目标索引任务 ID
            .retrievalDocumentIds(routedDocumentIds)      // ← 候选文档 ID 列表（AUTO_DOCUMENT 可能多文档）
            .retrievalTaskIds(routedTaskIds)              // ← 候选任务 ID 列表
            .noEvidenceReply(buildDocumentModeNoEvidenceReply(question, requiresFreshSearch))  // ← 无证据兜底文本
            .build();
    }

    private ConversationExecutionPlan.ConversationExecutionPlanBuilder basePlan(String question,
                                                                                ChatQueryMode chatMode,
                                                                                ConversationMemoryContext memoryContext,
                                                                                HistoryPlanningContext historyPlanningContext,
                                                                                String historySummary,
                                                                                AnswerHistoryContext answerHistoryContext,
                                                                                LocalDate currentDate,
                                                                                String currentDateText,
                                                                                boolean requiresCurrentDateAnchoring,
                                                                                boolean requiresFreshSearch) {
        return ConversationExecutionPlan.builder()
            .chatMode(chatMode)
            .originalQuestion(question)
            .agentQuestion(question)
            .rewriteQuestion(question)
            .rewriteSubQuestions(List.of(question))
            .retrievalQuestion(question)
            .retrievalSubQuestions(List.of(question))
            .historySummary(historySummary)
            .longTermSummary(memoryContext.getLongTermSummary())
            .historyPlanningContext(historyPlanningContext)
            .recentHistoryTranscript(memoryContext.getRecentTranscript())
            .answerRecentTranscript(memoryContext.getAnswerRecentTranscript())
            .answerHistoryContext(answerHistoryContext)
            .historyCompressionApplied(memoryContext.isCompressionApplied())
            .historyCoveredExchangeId(memoryContext.getCoveredExchangeId())
            .historyCoveredExchangeCount(memoryContext.getCoveredExchangeCount())
            .historyCompressionCount(memoryContext.getCompressionCount())
            .currentDate(currentDate)
            .currentDateText(currentDateText)
            .requiresCurrentDateAnchoring(requiresCurrentDateAnchoring)
            .requiresFreshSearch(requiresFreshSearch)
            .noEvidenceReply(properties.getNoEvidenceReply());
    }

    private Map<String, Object> buildRewriteStageSnapshot(String question,
                                                          String historySummary,
                                                          RagRewriteResult rewriteResult) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("originalQuestion", StrUtil.blankToDefault(question, ""));
        snapshot.put("historyContext", StrUtil.blankToDefault(historySummary, ""));
        snapshot.put("rewriteQuestion", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRewrittenQuestion(), ""));
        snapshot.put("subQuestions", rewriteResult == null || rewriteResult.getSubQuestions() == null ? List.of() : rewriteResult.getSubQuestions());
        snapshot.put("rawModelOutput", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRawModelOutput(), ""));

        ChatRagProperties.RewriteOptionsProperties rewriteOptions = properties == null ? null : properties.getRewriteOptions();
        boolean overrideEnabled = rewriteOptions != null && rewriteOptions.isEnabled();
        snapshot.put("rewriteOverrideEnabled", overrideEnabled);
        snapshot.put("rewriteTemperature", rewriteOptions == null ? null : rewriteOptions.getTemperature());
        snapshot.put("rewriteTopP", rewriteOptions == null ? null : rewriteOptions.getTopP());
        snapshot.put("rewriteThinking", rewriteOptions == null ? null : rewriteOptions.getThinking());
        return snapshot;
    }

    private ConversationMemoryContext summarizeHistory(String conversationId, ConversationTraceRecorder traceRecorder) {
        return conversationMemoryService.loadMemoryContext(conversationId, traceRecorder);
    }

    private HistoryPlanningContext buildHistoryPlanningContext(ConversationMemoryContext memoryContext) {
        ConversationSummaryPayload payload = memoryContext == null ? null : memoryContext.getSummaryPayload();
        if (payload == null) {
            return HistoryPlanningContext.builder().build();
        }
        return HistoryPlanningContext.builder()
            .conversationGoal(payload.getConversationGoal())
            .stableFacts(payload.getStableFacts() == null ? List.of() : new ArrayList<>(payload.getStableFacts()))
            .pendingQuestions(payload.getPendingQuestions() == null ? List.of() : new ArrayList<>(payload.getPendingQuestions()))
            .retrievalHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .queryContextHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .build();
    }

    private String buildPlanningHistory(ConversationMemoryContext memoryContext,
                                        HistoryPlanningContext historyPlanningContext) {
        String structuredHistory = buildStructuredPlanningHistory(historyPlanningContext);
        String recentTranscript = memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript());
        int maxChars = Math.max(1, properties.getPlanningHistoryMaxChars());
        if (recentTranscript.isBlank()) {
            return clipHead(structuredHistory, maxChars);
        }
        int recentBudget = Math.min(Math.max(maxChars / 2, (int) Math.round(maxChars * 0.65D)), maxChars);
        String recentPart = clipTail(recentTranscript, recentBudget);
        int structuredBudget = Math.max(0, maxChars - recentPart.length() - (recentPart.isBlank() ? 0 : 2));
        String structuredPart = clipHead(structuredHistory, structuredBudget);
        return joinNonBlank(structuredPart, recentPart);
    }

    private AnswerHistoryContext buildAnswerHistoryContext(String question,
                                                           String answerRecentTranscript) {
        return answerHistoryContextAssembler.assemble(question, answerRecentTranscript);
    }

    private String buildStructuredPlanningHistory(HistoryPlanningContext historyPlanningContext) {
        StringBuilder builder = new StringBuilder();
        if (historyPlanningContext == null) {
            return "";
        }
        appendSection(builder, "会话目标", historyPlanningContext.getConversationGoal());
        appendBulletSection(builder, "已确认事实", historyPlanningContext.getStableFacts());
        appendBulletSection(builder, "待跟进问题", historyPlanningContext.getPendingQuestions());
        appendBulletSection(builder, "检索提示", historyPlanningContext.getRetrievalHints());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n").append(content.trim()).append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n");
        values.stream()
            .filter(item -> item != null && !item.isBlank())
            .limit(5)
            .forEach(item -> builder.append("- ").append(item.trim()).append('\n'));
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return safeText(right);
        }
        if (right == null || right.isBlank()) {
            return safeText(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return safeText(right);
    }

    private List<DocumentRouteCandidate> selectAutoCandidates(KnowledgeRouteDecision routeDecision,
                                                              String question,
                                                              String rewriteQuestion) {
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return fallbackDocuments(question, rewriteQuestion, 5);
        }
        int candidateLimit = routeDecision.getConfidence() != null && routeDecision.getConfidence().doubleValue() >= 0.80D ? 3 : 5;
        List<DocumentRouteCandidate> candidates = routeDecision.getDocuments().stream()
            .filter(item -> StrUtil.isNotBlank(item.getDocumentId()) && StrUtil.isNotBlank(item.getLastIndexTaskId()))
            .limit(candidateLimit)
            .toList();
        if (candidates.isEmpty()) {
            return fallbackDocuments(question, rewriteQuestion, candidateLimit);
        }
        // 低置信度时合并路由候选和兜底候选，并去重
        if (routeDecision.getConfidence() != null && routeDecision.getConfidence().doubleValue() < 0.55D) {
            List<DocumentRouteCandidate> merged = mergeCandidates(candidates, fallbackDocuments(question, rewriteQuestion, candidateLimit), candidateLimit);
            // 按文档名再次去重：同名文档保留分数最高的那个，避免列表中出现重复项
            return deduplicateCandidatesByName(merged);
        }
        // 按文档名去重后返回
        return deduplicateCandidatesByName(candidates);
    }

    /**
     * 按文档名去重候选文档列表，同名文档保留分数最高的那个。
     * 解决同一文档因多个 taskId 或评分通道不同导致重复出现在候选中的问题。
     */
    private List<DocumentRouteCandidate> deduplicateCandidatesByName(List<DocumentRouteCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        Map<String, DocumentRouteCandidate> nameMap = new LinkedHashMap<>();
        for (DocumentRouteCandidate candidate : candidates) {
            String normalizedName = normalizeDocName(candidate.getDocumentName());
            if (StrUtil.isBlank(normalizedName)) {
                // 文档名为空时直接用 documentId 作为 key
                nameMap.merge(candidate.getDocumentId(), candidate, (existing, incoming) ->
                    (incoming.getScore() != null && (existing.getScore() == null || incoming.getScore().compareTo(existing.getScore()) > 0)) ? incoming : existing
                );
                continue;
            }
            nameMap.merge(normalizedName, candidate, (existing, incoming) ->
                (incoming.getScore() != null && (existing.getScore() == null || incoming.getScore().compareTo(existing.getScore()) > 0)) ? incoming : existing
            );
        }
        return new ArrayList<>(nameMap.values());
    }

    private List<DocumentRouteCandidate> fallbackDocuments(String question,
                                                           String rewriteQuestion,
                                                           int limit) {
        List<KnowledgeDocumentDescriptor> descriptors = documentKnowledgeService.listRetrievableDocuments();
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = extractFallbackTerms(question, rewriteQuestion);
        return descriptors.stream()
            .sorted((left, right) -> Double.compare(
                fallbackDescriptorScore(right, queryTerms),
                fallbackDescriptorScore(left, queryTerms)
            ))
            .limit(Math.max(1, limit))
            .map(item -> new DocumentRouteCandidate(
                String.valueOf(item.getDocumentId()),
                item.getDocumentName(),
                item.getLastIndexTaskId() == null ? "" : String.valueOf(item.getLastIndexTaskId()),
                StrUtil.blankToDefault(item.getKnowledgeScopeCode(), ""),
                StrUtil.blankToDefault(item.getKnowledgeScopeName(), ""),
                StrUtil.blankToDefault(item.getBusinessCategory(), ""),
                StrUtil.blankToDefault(item.getDocumentTags(), ""),
                BigDecimal.valueOf(fallbackDescriptorScore(item, queryTerms)).setScale(4, RoundingMode.HALF_UP),
                "低置信度时基于文档元数据进行保守扩范围候选"
            ))
            .toList();
    }

    private List<DocumentRouteCandidate> mergeCandidates(List<DocumentRouteCandidate> primary,
                                                         List<DocumentRouteCandidate> secondary,
                                                         int limit) {
        LinkedHashMap<String, DocumentRouteCandidate> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.getDocumentId(), item));
        secondary.forEach(item -> merged.putIfAbsent(item.getDocumentId(), item));
        return merged.values().stream().limit(Math.max(1, limit)).toList();
    }

    private boolean shouldAskClarification(KnowledgeRouteDecision routeDecision,
                                           List<DocumentRouteCandidate> candidateDocuments) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return true;
        }
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return true;
        }
        if (routeDecision.getConfidence() == null || routeDecision.getConfidence().doubleValue() < 0.55D) {
            return true;
        }
        if (candidateDocuments.size() < 2) {
            return false;
        }
        BigDecimal topScore = candidateDocuments.get(0).getScore();
        BigDecimal secondScore = candidateDocuments.get(1).getScore();
        if (topScore == null || secondScore == null) {
            return false;
        }
        return topScore.subtract(secondScore).doubleValue() <= 3D
            && !Objects.equals(candidateDocuments.get(0).getKnowledgeScopeCode(), candidateDocuments.get(1).getKnowledgeScopeCode());
    }

    private String buildClarificationReply(String originalQuestion,
                                           KnowledgeRouteDecision routeDecision,
                                           List<DocumentRouteCandidate> candidateDocuments) {
        List<DocumentRouteCandidate> topCandidates = candidateDocuments == null ? List.of() : candidateDocuments.stream().limit(3).toList();
        if (topCandidates.isEmpty()) {
            return "当前我还不能稳定判断你想问哪份知识文档。请补充更具体的文档名、主题词，或者直接切换到“当前文档问答”后指定文档。";
        }
        StringBuilder builder = new StringBuilder("这个问题目前存在文档范围歧义，我先确认你想问哪一份：\n");
        for (int index = 0; index < topCandidates.size(); index++) {
            DocumentRouteCandidate item = topCandidates.get(index);
            builder.append(index + 1)
                .append(". 《")
                .append(StrUtil.blankToDefault(item.getDocumentName(), item.getDocumentId()))
                .append("》");
            if (StrUtil.isNotBlank(item.getKnowledgeScopeName()) || StrUtil.isNotBlank(item.getKnowledgeScopeCode())) {
                builder.append("（")
                    .append(StrUtil.blankToDefault(item.getKnowledgeScopeName(), item.getKnowledgeScopeCode()))
                    .append("）");
            }
            builder.append('\n');
        }
        builder.append("你可以直接回复文档名，或者改用“当前文档问答”模式明确指定文档。");
        return builder.toString();
    }

    private List<String> buildClarificationOptions(List<DocumentRouteCandidate> candidateDocuments) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return List.of();
        }
        return candidateDocuments.stream()
            .limit(3)
            .map(item -> "我想问《" + StrUtil.blankToDefault(item.getDocumentName(), item.getDocumentId()) + "》")
            .toList();
    }

    private String buildClarificationReason(KnowledgeRouteDecision routeDecision,
                                            List<DocumentRouteCandidate> candidateDocuments) {
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return "当前自动知识路由没有形成稳定候选，已改为先向用户确认文档范围。";
        }
        String confidenceText = routeDecision.getConfidence() == null ? "-" : routeDecision.getConfidence().toPlainString();
        int candidateCount = candidateDocuments == null ? 0 : candidateDocuments.size();
        return "当前自动知识路由置信度为 " + confidenceText + "，候选文档数为 " + candidateCount + "，为避免误选文档，先返回澄清问题。";
    }

    private List<String> extractFallbackTerms(String question, String rewriteQuestion) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String routingText = (safeText(question) + " " + safeText(rewriteQuestion)).trim();
        for (String segment : routingText.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
                if (trimmed.length() >= 4) {
                    int maxGram = Math.min(6, trimmed.length());
                    for (int gram = 2; gram <= maxGram; gram++) {
                        for (int start = 0; start + gram <= trimmed.length(); start++) {
                            terms.add(trimmed.substring(start, start + gram));
                        }
                    }
                }
            }
        }
        return terms.stream().limit(40).toList();
    }

    private double fallbackDescriptorScore(KnowledgeDocumentDescriptor descriptor, List<String> queryTerms) {
        String content = normalizeFallbackText(String.join(" ",
            StrUtil.blankToDefault(descriptor.getDocumentName(), ""),
            StrUtil.blankToDefault(descriptor.getKnowledgeScopeCode(), ""),
            StrUtil.blankToDefault(descriptor.getKnowledgeScopeName(), ""),
            StrUtil.blankToDefault(descriptor.getBusinessCategory(), ""),
            StrUtil.blankToDefault(descriptor.getDocumentTags(), "")
        ));
        if (queryTerms == null || queryTerms.isEmpty() || content.isBlank()) {
            return 0D;
        }
        double score = 0D;
        List<String> sortedTerms = queryTerms.stream()
            .map(this::normalizeFallbackText)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        List<String> matched = new ArrayList<>();
        for (String term : sortedTerms) {
            if (term.length() < 2) {
                continue;
            }
            boolean covered = matched.stream().anyMatch(existing -> existing.contains(term));
            if (covered) {
                continue;
            }
            if (content.contains(term)) {
                matched.add(term);
                if (term.length() >= 8) {
                    score += 12D;
                }
                else if (term.length() >= 5) {
                    score += 8D;
                }
                else if (term.length() >= 3) {
                    score += 4D;
                }
                else {
                    score += 2D;
                }
            }
        }
        return score;
    }

    private String normalizeFallbackText(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String buildDocumentModeNoEvidenceReply(String question, boolean requiresFreshSearch) {
        String normalizedQuestion = safeText(question);
        if (looksLikeCapabilityQuestion(normalizedQuestion)) {
            return "当前你正在使用“当前文档问答”模式，我会优先基于所选文档回答。这个问题更像是在询问助手能力，而不是当前文档内容。如果你想了解我能做什么，请切换到“开放式提问”模式。";
        }
        if (looksLikeOpenChatQuestion(normalizedQuestion, requiresFreshSearch)) {
            return "当前你正在使用“当前文档问答”模式，我只能基于所选文档回答。这个问题更像开放式提问，例如天气、最新信息或一般交流。如果你想继续问这类问题，请切换到“开放式提问”模式。";
        }
        return StrUtil.blankToDefault(
            properties.getNoEvidenceReply(),
            "当前没有从当前文档中检索到足够证据，暂时不能给出可靠结论。你可以补充更具体的标题、术语或关键词后再试。"
        );
    }

    private boolean looksLikeCapabilityQuestion(String normalizedQuestion) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        return CAPABILITY_HINTS.stream().anyMatch(normalizedQuestion::contains);
    }

    private boolean looksLikeOpenChatQuestion(String normalizedQuestion, boolean requiresFreshSearch) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        if (requiresFreshSearch) {
            return true;
        }
        if (OPEN_CHAT_HINTS.stream().anyMatch(normalizedQuestion::contains)) {
            return true;
        }
        return CHITCHAT_HINTS.stream().anyMatch(normalizedQuestion::contains);
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTO_DOCUMENT 模式下的文档选择检测（解决澄清循环问题）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 检测用户是否通过"我想问《xxx》"等方式在 AUTO_DOCUMENT 模式中选择了文档。
     * <p>
     * 当系统返回澄清列表后，用户回复如"我想问《个人房屋租赁合同.docx》"时，
     * 识别出这是一次文档选择，而不是一个新的知识路由请求。
     *
     * @param question 用户当前的问题
     * @return 如果检测到文档选择意图，返回包含文档名的 DocumentSelection；否则返回 null
     */
    private DocumentSelection detectDocumentSelection(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();

        // 模式 1：明确的文档选择句式 —— "我想问《xxx》" / "我要问《xxx》" / "选《xxx》" 等
        Pattern selectionPattern = Pattern.compile(
            "(?:我想问|我要问|我问|选择|选|我要看|请问)?[《]([^》]{1,50})[》]"
        );
        Matcher matcher = selectionPattern.matcher(normalized);
        if (matcher.find()) {
            String docName = matcher.group(1).trim();
            if (StrUtil.isNotBlank(docName) && docName.length() >= 2) {
                return new DocumentSelection(docName, matcher.group());
            }
        }

        // 模式 2：用户直接回复了文档名（数字序号选择），如 "1" / "第一个" / "文档一"
        Pattern numberPattern = Pattern.compile("^\\s*[第]?[一二两三四五六七八九十0-9]+[个份篇]?\\s*$");
        if (numberPattern.matcher(normalized).matches()) {
            // 纯数字选择无法直接映射到文档名，这里不做处理
            // 交给下游知识路由处理
            return null;
        }

        return null;
    }

    /**
     * 根据文档名（或部分文档名）在可检索文档列表中查找匹配的文档。
     * <p>
     * 匹配策略：精确匹配 → 前缀匹配 → 包含匹配
     *
     * @param docName 用户选择的文档名（可能不完整）
     * @return 匹配的文档描述，未找到返回 null
     */
    private KnowledgeDocumentDescriptor findDocumentByName(String docName) {
        if (StrUtil.isBlank(docName)) {
            return null;
        }
        List<KnowledgeDocumentDescriptor> docs = documentKnowledgeService.listRetrievableDocuments();
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        String normalizedTarget = normalizeDocName(docName);

        // 精确匹配
        for (KnowledgeDocumentDescriptor doc : docs) {
            if (normalizeDocName(doc.getDocumentName()).equals(normalizedTarget)) {
                return doc;
            }
        }

        // 前缀匹配（用户输入开头部分匹配文档名，或文档名开头部分匹配用户输入）
        for (KnowledgeDocumentDescriptor doc : docs) {
            String normalizedDocName = normalizeDocName(doc.getDocumentName());
            if (normalizedDocName.startsWith(normalizedTarget)
                || normalizedTarget.startsWith(normalizedDocName)
                || normalizedDocName.contains(normalizedTarget)
                || normalizedTarget.contains(normalizedDocName)) {
                return doc;
            }
        }

        return null;
    }

    /**
     * 标准化文档名：去空白、特殊字符、转小写，用于比较。
     */
    private String normalizeDocName(String name) {
        return StrUtil.blankToDefault(name, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\].]+", "")
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 从会话记忆的近期对话文本中提取上一轮用户的问题。
     * <p>
     * recentTranscript 的格式为：
     * <pre>
     * 用户: 房子位于哪里
     * 助手: 这个问题目前存在文档范围歧义...
     * </pre>
     * 取最后一组"用户:"后面的内容作为上一轮的实际提问。
     *
     * @param memoryContext 会话记忆上下文（含 recentTranscript）
     * @return 上一轮用户的问题文本，如果没找到则返回空字符串
     */
    private String extractPreviousQuestion(ConversationMemoryContext memoryContext) {
        String transcript = memoryContext == null ? "" : memoryContext.getRecentTranscript();
        if (StrUtil.isBlank(transcript)) {
            return "";
        }
        // 查找所有 "用户:" 或 "用户：" 开头行，取最后一个
        Pattern pattern = Pattern.compile("用户[：:]([^\\n]+)");
        Matcher matcher = pattern.matcher(transcript);
        String lastQuestion = "";
        while (matcher.find()) {
            String matched = matcher.group(1).trim();
            if (StrUtil.isNotBlank(matched)) {
                lastQuestion = matched;
            }
        }
        return lastQuestion;
    }

    /**
     * 文档选择结果 —— 用户通过"我想问《xxx》"等模式选择了文档。
     */
    private static final class DocumentSelection {
        /** 提取出的文档名 */
        private final String documentName;
        /** 用户输入的匹配原文（用于日志和追踪） */
        private final String matchText;

        private DocumentSelection(String documentName, String matchText) {
            this.documentName = documentName;
            this.matchText = matchText;
        }
    }
}
