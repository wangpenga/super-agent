package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.data.SuperAgentChatMemorySummary;
import org.javaup.ai.chatagent.mapper.SuperAgentChatMemorySummaryMapper;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化会话记忆服务 — 对话长期记忆的管理者
 * <p>
 * 核心职责：
 * <ol>
 *   <li><b>加载记忆上下文</b>（{@link #loadMemoryContext}）：
 *       从 memory_summary 表读取长期摘要 + 从 exchange 表读取近期窗口 → 组装 ConversationMemoryContext</li>
 *   <li><b>触发记忆压缩</b>（{@link #refreshSummaryIfNecessary}）：
 *       当未压缩轮次超过 keepRecentTurns 时，调用 LLM 将溢出轮次压缩为长期摘要</li>
 *   <li><b>异步刷新</b>（{@link #refreshConversationSummaryAsync}）：
 *       每次对话收尾后异步触发压缩检查</li>
 * </ol>
 * <p>
 * <b>数据流：</b>
 * <pre>
 * exchange 表（所有轮次）
 *   ├─ coveredExchangeId 之前 → 已压缩到 memory_summary.longTermSummary
 *   └─ coveredExchangeId 之后 → 未被压缩，作为 recentTranscript 直接读取
 * </pre>
 *
 * @author wangpeng
 */
@Slf4j
@Service
public class PersistentConversationMemoryService implements ConversationMemoryService {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern RETRIEVAL_HINT_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{2,}|[\\p{IsHan}]{2,12}");
    private static final int MAX_SECTION_ITEMS = 6;
    private static final int MAX_ITEM_LENGTH = 80;
    private static final int MAX_GOAL_LENGTH = 120;
    private static final int MAX_QUESTION_LENGTH = 160;
    private static final int MAX_ANSWER_LENGTH = 320;
    private static final int MAX_ANSWER_CONTEXT_ANSWER_LENGTH = 220;

    private final ConversationArchiveStore conversationArchiveStore;
    private final SuperAgentChatMemorySummaryMapper summaryMapper;
    private final ObjectMapper objectMapper;
    private final ChatRagProperties properties;
    private final ExecutorService chatMemorySummaryExecutorService;
    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final Set<String> refreshingConversationIds = ConcurrentHashMap.newKeySet();

    @Resource
    private UidGenerator uidGenerator;

    public PersistentConversationMemoryService(ConversationArchiveStore conversationArchiveStore,
                                               SuperAgentChatMemorySummaryMapper summaryMapper,
                                               ObjectMapper objectMapper,
                                               ChatRagProperties properties,
                                               @Qualifier("chatMemorySummaryExecutorService") ExecutorService chatMemorySummaryExecutorService,
                                               ObservedChatModelService observedChatModelService,
                                               PromptTemplateService promptTemplateService) {
        this.conversationArchiveStore = conversationArchiveStore;
        this.summaryMapper = summaryMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatMemorySummaryExecutorService = chatMemorySummaryExecutorService;
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public ConversationMemoryContext loadMemoryContext(String conversationId) {
        return loadMemoryContext(conversationId, null);
    }

/**
 * 加载会话记忆上下文
 * <p>
 * <b>这是 prepare 阶段调用的入口，返回编排器需要的完整记忆数据。</b>
 * <p>
 * <b>两种模式：</b>
 * <ol>
 *   <li><b>压缩关闭</b>（historySummary.enabled=false）：
 *       直接从 exchange 表读最近 N 轮原文，不查 memory_summary 表</li>
 *   <li><b>压缩开启</b>（默认）：
 *       ① 查 memory_summary 表 → 长期摘要
 *       ② 查 exchange 表 → 近期未压缩的窗口
 *       ③ 如果未压缩轮次溢出 → 触发 LLM 压缩（本方法内同步完成）</li>
 * </ol>
 * <p>
 * <b>压缩触发条件：</b>
 * "未被长期摘要覆盖的已完成轮次" > keepRecentTurns（默认 4）
 * <p>
 * <b>压缩产出：</b>
 * ConversationSummaryPayload（LLM 生成的结构化摘要）→ 写入 memory_summary 表
 *
 * @param conversationId 会话 ID
 * @param traceRecorder  追踪记录器（可 null）
 * @return 组装好的 ConversationMemoryContext
 */
@Override
public ConversationMemoryContext loadMemoryContext(String conversationId, ConversationTraceRecorder traceRecorder) {
    // ── conversationId 为空 → 返回空上下文（新会话首次对话）──
    if (StrUtil.isBlank(conversationId)) {
        return emptyContext();
    }

    ChatRagProperties.HistorySummaryProperties historySummaryProperties = properties.getHistorySummary();

    // ══════════════════════════════════════════════════════════
    // 路径 A：压缩功能关闭 → 只读近期窗口，不查 memory_summary
    // ══════════════════════════════════════════════════════════
    if (!historySummaryProperties.isEnabled()) {
        // 从 exchange 表拉取最近几轮原文（多拉一些防止过滤后不够）
        String recentTranscript = renderRecentTranscript(
            conversationArchiveStore.listRecentExchanges(conversationId, Math.max(1, properties.getRewriteHistoryTurns() * 3)),
            Math.max(1, properties.getRewriteHistoryTurns()),
            historySummaryProperties.getRecentTranscriptMaxChars()
        );
        String answerRecentTranscript = renderAnswerRecentTranscript(
            conversationArchiveStore.listRecentExchanges(conversationId, Math.max(1, properties.getRewriteHistoryTurns() * 3)),
            Math.max(1, properties.getRewriteHistoryTurns()),
            Math.max(1, properties.getAnswerHistoryMaxChars())
        );
        return ConversationMemoryContext.builder()
            .assembledHistory(recentTranscript)
            .longTermSummary("")           // 无长期摘要
            .recentTranscript(recentTranscript)
            .answerRecentTranscript(answerRecentTranscript)
            .summaryPayload(ConversationSummaryPayload.builder().build())  // 空结构
            .coveredExchangeId(0L)         // 无覆盖
            .coveredExchangeCount(0)
            .compressionCount(0)
            .compressionApplied(false)
            .build();
    }

    // ══════════════════════════════════════════════════════════
    // 路径 B：压缩功能开启 → 查 memory_summary + 按需触发压缩
    // ══════════════════════════════════════════════════════════

    // ① 查 memory_summary 表 + 按需触发压缩（可能同步阻塞调 LLM）
    SuperAgentChatMemorySummary summaryState = refreshSummaryIfNecessary(
        conversationId,
        findSummary(conversationId).orElse(null),  // 查 DB 中现有的摘要记录
        traceRecorder
    );
    // ② 将 summary_json 反序列化为结构化对象
    ConversationSummaryPayload summaryPayload = readSummaryPayload(summaryState);

    // ③ 从 exchange 表拉取近期轮次（未被压缩覆盖的窗口）
    List<ConversationExchangeView> recentExchanges = conversationArchiveStore.listRecentExchanges(
        conversationId,
        recentFetchLimit(historySummaryProperties.getKeepRecentTurns())  // 多拉一点防止过滤后不够
    );
    // ④ 渲染近期对话原文（含 Q+A，注入 LLM Prompt 作为完整对话上下文）
    String recentTranscript = renderRecentTranscript(
        recentExchanges,
        historySummaryProperties.getKeepRecentTurns(),
        historySummaryProperties.getRecentTranscriptMaxChars()
    );
    // ⑤ 渲染近期对话（仅含 Q，不含 A）—— 专用于追问判断
    // 与 recentTranscript 的区别：只渲染 question，不渲染 answer。
    // 原因：AnswerHistoryContextAssembler 通过这个文本判断"用户现在的提问是不是对上轮回答的追问"，
    // 它只需要知道用户问过什么，不需要助手答过什么。省下的字符预算可以覆盖更多轮次。
    String answerRecentTranscript = renderAnswerRecentTranscript(
        recentExchanges,
        historySummaryProperties.getKeepRecentTurns(),
        Math.max(1, properties.getAnswerHistoryMaxChars())
    );
    // ⑥ 提取 longTermSummary 文本
    String longTermSummary = summaryState == null ? "" : safeText(summaryState.getSummaryText());

    // ⑦ 组装返回
    return ConversationMemoryContext.builder()
        // 拼接好的完整历史（长期摘要 + 近期窗口）
        .assembledHistory(assembleHistory(longTermSummary, recentTranscript))
        .longTermSummary(longTermSummary)
        .recentTranscript(recentTranscript)
        .answerRecentTranscript(answerRecentTranscript)
        .summaryPayload(summaryPayload)
        .coveredExchangeId(summaryState == null ? 0L : defaultLong(summaryState.getCoveredExchangeId()))
        .coveredExchangeCount(summaryState == null ? 0 : safeInt(summaryState.getCoveredExchangeCount()))
        .compressionCount(summaryState == null ? 0 : safeInt(summaryState.getCompressionCount()))
        .compressionApplied(StrUtil.isNotBlank(longTermSummary))  // 有摘要文本 = 压缩已应用
        .build();
}

    @Override
    public void refreshConversationSummaryAsync(String conversationId) {
        if (StrUtil.isBlank(conversationId) || !properties.getHistorySummary().isEnabled()) {
            return;
        }

        if (!refreshingConversationIds.add(conversationId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshSummaryIfNecessary(conversationId, findSummary(conversationId).orElse(null), null);
            }
            catch (Exception exception) {
                log.warn("异步预热会话摘要失败, conversationId={}", conversationId, exception);
            }
            finally {
                refreshingConversationIds.remove(conversationId);
            }
        }, chatMemorySummaryExecutorService);
    }

    @Override
    public ConversationMemorySummaryView getConversationSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return emptySummaryView("");
        }
        return toSummaryView(conversationId, findSummary(conversationId).orElse(null));
    }

    @Override
    public ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return emptySummaryView("");
        }

        if (!refreshingConversationIds.add(conversationId)) {
            return getConversationSummary(conversationId);
        }
        try {
            summaryMapper.delete(new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
                .eq(SuperAgentChatMemorySummary::getConversationId, conversationId));
            SuperAgentChatMemorySummary rebuiltState = refreshSummaryIfNecessary(conversationId, null, null);
            return toSummaryView(conversationId, rebuiltState);
        }
        finally {
            refreshingConversationIds.remove(conversationId);
        }
    }

    @Override
    public void deleteConversationSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        summaryMapper.delete(new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
            .eq(SuperAgentChatMemorySummary::getConversationId, conversationId));
    }

/**
 * 按需刷新会话长期摘要（ID 为 exchangeId）
 * <p>
 * <b>核心逻辑：</b>
 * <ol>
 *   <li>查 exchange 表：coveredExchangeId 之后有哪些新轮次</li>
 *   <li>筛选出"稳定轮次"（COMPLETED + 有 question）</li>
 *   <li>如果稳定轮次 > keepRecentTurns → 有溢出 → 需要压缩</li>
 *   <li>将溢出轮次按 batch 分批，每批调 LLM 生成/合并摘要</li>
 *   <li>每批压缩后写入 memory_summary 表（saveSummarySnapshot）</li>
 * </ol>
 * <p>
 * <b>示例：</b>
 * <pre>
 * keepRecentTurns=4, compressionBatchTurns=6
 * 如果有 10 个稳定轮次
 *   溢出 = 10 - 4 = 6 个轮次
 *   分 1 批（6 个）→ 调 LLM 压缩 → 写入 memory_summary
 *   更新 coveredExchangeId，下次从新的位置开始
 * </pre>
 *
 * @param conversationId 会话 ID
 * @param currentState   当前 memory_summary 记录（可能为 null）
 * @param traceRecorder  追踪记录器
 * @return 最新的 memory_summary 记录
 */
private SuperAgentChatMemorySummary refreshSummaryIfNecessary(String conversationId,
                                                              SuperAgentChatMemorySummary currentState,
                                                              ConversationTraceRecorder traceRecorder) {
    ChatRagProperties.HistorySummaryProperties historySummaryProperties = properties.getHistorySummary();
    // 当前已覆盖到的 exchangeId（=0 表示从未压缩过）
    long coveredExchangeId = currentState == null ? 0L : defaultLong(currentState.getCoveredExchangeId());

    // ① 从 exchange 表拉取 coveredExchangeId 之后的所有轮次（增量）
    List<ConversationExchangeView> incrementalExchanges = conversationArchiveStore.listExchangesAfter(
        conversationId,
        coveredExchangeId
    );
    // ② 筛选"稳定轮次"：COMPLETED + 有 question（RUNNING/FAILED/STOPPED 的不参与压缩）
    List<ConversationExchangeView> stableExchanges = incrementalExchanges.stream()
        .filter(this::isStableSummaryExchange)
        .toList();

    // ③ 计算溢出数量 = 稳定轮次 - 保留阈值
    int overflowCount = Math.max(0, stableExchanges.size() - historySummaryProperties.getKeepRecentTurns());
    if (overflowCount <= 0) {
        // 没溢出 → 不需要压缩，直接返回现有状态
        return currentState;
    }

    // ④ 有溢出 → 取出最旧的溢出轮次（保留最新的 keepRecentTurns 个）
    List<ConversationExchangeView> overflowExchanges = stableExchanges.subList(0, overflowCount);
    SuperAgentChatMemorySummary workingState = currentState;

    // ⑤ 按 batch 大小分批压缩（每批调一次 LLM）
    for (int start = 0; start < overflowExchanges.size(); start += historySummaryProperties.getCompressionBatchTurns()) {
        int end = Math.min(start + historySummaryProperties.getCompressionBatchTurns(), overflowExchanges.size());
        List<ConversationExchangeView> batch = overflowExchanges.subList(start, end);

        // 调 LLM 合并已有的 summary + 新的 batch 对话 → 新的 structured summary
        ConversationSummaryPayload mergedPayload = mergeSummaryPayload(readSummaryPayload(workingState), batch, traceRecorder);

        // 取本批最后一条 exchange，作为新的 coveredExchangeId
        ConversationExchangeView lastExchange = batch.get(batch.size() - 1);

        // 写入 memory_summary 表（INSERT 或 UPDATE）
        workingState = saveSummarySnapshot(
            conversationId,
            workingState,
            mergedPayload,
            lastExchange.getExchangeId(),                                                      // 更新 coveredExchangeId
            safeInt(workingState == null ? null : workingState.getCoveredExchangeCount()) + batch.size(),  // 累加覆盖数
            resolveSourceTime(lastExchange)
        );
    }
    return workingState;
}

    /**
     * 合并会话长期摘要 —— 调 LLM 把已有摘要 + 新对话批次合并为新的结构化摘要
     * <p>
     * <b>两条路径：</b>
     * <ol>
     *   <li><b>LLM 路径（正常）</b>：调 callText → 解析 JSON → normalizePayload 返回</li>
     *   <li><b>规则路径（兜底）</b>：LLM 调用异常或解析失败 → fallbackMerge</li>
     * </ol>
     *
     * @param existingPayload 已有的长期摘要（可能为空）
     * @param batch           要被压缩的新对话批次
     * @param traceRecorder   追踪记录器
     * @return 合并后的 ConversationSummaryPayload
     */
    private ConversationSummaryPayload mergeSummaryPayload(ConversationSummaryPayload existingPayload,
                                                           List<ConversationExchangeView> batch,
                                                           ConversationTraceRecorder traceRecorder) {
        try {
            // ① 调 LLM：把已有摘要 JSON + 新对话批次 → 新的结构化摘要 JSON
            String content = observedChatModelService.callText(
                "summary",                                                      // stageName
                promptTemplateService.render(PromptTemplateNames.CONVERSATION_SUMMARY_SYSTEM, Map.of()),  // system prompt
                buildSummaryMergePrompt(existingPayload, batch),                // user prompt（含已有摘要+新对话）
                traceRecorder
            );
            // ② 从 LLM 输出中提取 JSON 对象，反序列化为 ConversationSummaryPayload
            ConversationSummaryPayload parsedPayload = parseSummaryPayload(content);
            if (parsedPayload != null) {
                // ③ 规范化：裁剪超长字段 + 去重 + 限制条数
                return normalizePayload(parsedPayload);
            }
        }
        catch (Exception exception) {
            // LLM 调用失败 → 走规则兜底，不抛异常
            log.warn("合并会话长期摘要失败，回退到规则压缩: {}", exception.getMessage());
        }
        // ④ LLM 失败或解析失败 → 纯规则拼接（不依赖 LLM）
        return fallbackMerge(existingPayload, batch);
    }

    /**
     * 构建摘要合并的 LLM Prompt
     * <p>
     * 产物是给 LLM 的 user prompt，包含两个变量：
     * <ul>
     *   <li><b>existingSummaryJson</b>：已有的长期摘要 JSON（首次压缩时为 {}）</li>
     *   <li><b>newConversationBatch</b>：格式化的近期对话批次文本（Q+A，按句子边界截断）</li>
     * </ul>
     */
    private String buildSummaryMergePrompt(ConversationSummaryPayload existingPayload,
                                           List<ConversationExchangeView> batch) {
        // 将已有摘要序列化为 JSON（先 copy 一份防止污染原对象）
        String existingJson = writePayloadJson(normalizePayload(copyPayload(existingPayload)));
        // 用 conversation-summary-merge.st 模板渲染 user prompt
        return promptTemplateService.render(PromptTemplateNames.CONVERSATION_SUMMARY_MERGE, Map.of(
            "existingSummaryJson", StrUtil.isNotBlank(existingJson) ? existingJson : "{}",
            "newConversationBatch", renderCompressionTranscript(batch)
        ));
    }

    /**
     * 规则兜底：不调 LLM，用纯文本拼接来更新摘要
     * <p>
     * 当 LLM 调用失败时使用。处理逻辑（按优先级）：
     * <ol>
     *   <li>摘要文本拼接：旧摘要 + 新对话高亮 → 合并为一段</li>
     *   <li>会话目标：如果为空，用最后一条提问填空</li>
     *   <li>待跟进问题：把本批所有提问追加进去</li>
     *   <li>检索提示：从最后一条提问中提取关键词</li>
     * </ol>
     * 这不如 LLM 生成的摘要质量高，但保证系统不会因为 LLM 故障而崩溃。
     */
    private ConversationSummaryPayload fallbackMerge(ConversationSummaryPayload existingPayload,
                                                     List<ConversationExchangeView> batch) {
        // ① 拷贝一份已有摘要，避免污染传入的对象
        ConversationSummaryPayload mergedPayload = copyPayload(existingPayload);
        // ② 从新对话批次中提取高亮文本（最多 4 条），与旧摘要拼接
        String batchHighlight = renderFallbackBatchHighlight(batch);
        String mergedSummary = joinNonBlank(mergedPayload.getSummary(), batchHighlight, "；");
        mergedPayload.setSummary(clipText(mergedSummary, properties.getHistorySummary().getSummaryMaxChars()));

        // ③ 如果还没有会话目标，拿本批最后一条提问来填
        ConversationExchangeView lastExchange = batch.get(batch.size() - 1);
        if (StrUtil.isBlank(mergedPayload.getConversationGoal()) && StrUtil.isNotBlank(lastExchange.getQuestion())) {
            mergedPayload.setConversationGoal(clipText(lastExchange.getQuestion(), MAX_GOAL_LENGTH));
        }

        // ④ 把本批所有提问追加到待跟进问题中
        List<String> pendingQuestions = new ArrayList<>(safeList(mergedPayload.getPendingQuestions()));
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                pendingQuestions.add(clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
            }
        }
        mergedPayload.setPendingQuestions(deduplicateAndLimit(pendingQuestions));

        // ⑤ 从最后一条提问中提取检索关键词
        List<String> retrievalHints = new ArrayList<>(safeList(mergedPayload.getRetrievalHints()));
        if (StrUtil.isNotBlank(lastExchange.getQuestion())) {
            retrievalHints.addAll(extractRetrievalHints(lastExchange.getQuestion()));
        }
        mergedPayload.setRetrievalHints(deduplicateAndLimit(retrievalHints));
        // ⑥ 再次规范化（裁剪 + 去重）
        return normalizePayload(mergedPayload);
    }

/**
 * 保存摘要快照到 memory_summary 表（INSERT 或 UPDATE）
 * <p>
 * <b>并发安全设计：</b>
 * <ol>
 *   <li>写入前重新查询 DB（latestState），防止覆盖其他线程/节点的最新写入</li>
 *   <li>如果 DB 中的 coveredExchangeId 已经比当前大 → 放弃写入，返回 DB 中的最新版</li>
 *   <li>如果 DB 中的 coveredExchangeId 相同且已有内容 → 也跳过（幂等去重）</li>
 *   <li>首次保存 → INSERT；后续保存 → UPDATE，compressionCount +1, summaryVersion +1</li>
 * </ol>
 *
 * @param conversationId      会话 ID
 * @param currentState        当前内存中的摘要状态
 * @param payload             要保存的摘要内容
 * @param coveredExchangeId   本次覆盖到的 exchangeId
 * @param coveredExchangeCount 累计覆盖轮次数
 * @param lastSourceEditTime  源轮次最后编辑时间
 * @return 最新的摘要记录
 */
private SuperAgentChatMemorySummary saveSummarySnapshot(String conversationId,
                                                        SuperAgentChatMemorySummary currentState,
                                                        ConversationSummaryPayload payload,
                                                        long coveredExchangeId,
                                                        int coveredExchangeCount,
                                                        Date lastSourceEditTime) {
    // ① 重新查 DB，获取最新状态（防止并发覆盖）
    SuperAgentChatMemorySummary latestState = findSummary(conversationId).orElse(null);
    long latestCoveredExchangeId = latestState == null ? 0L : defaultLong(latestState.getCoveredExchangeId());

    // ② DB 中已经有更新的覆盖进度 → 放弃本次写入，返回 DB 中的版本
    if (latestCoveredExchangeId > coveredExchangeId) {
        return latestState;
    }

    // ③ 覆盖进度相同且 DB 已有内容 → 幂等跳过（可能被并发执行了两次）
    if (latestState != null
        && latestCoveredExchangeId == coveredExchangeId
        && StrUtil.isNotBlank(latestState.getSummaryText())) {
        return latestState;
    }

    // ④ 将结构化 payload 转为文本和 JSON
    String summaryText = buildLongTermSummaryText(payload);
    String summaryJson = writePayloadJson(payload);

    // ⑤ 分支 A：首次保存 → INSERT
    if (latestState == null) {
        SuperAgentChatMemorySummary newState = new SuperAgentChatMemorySummary();
        newState.setId(uidGenerator.getUid());
        newState.setConversationId(conversationId);
        newState.setCoveredExchangeId(coveredExchangeId);
        newState.setCoveredExchangeCount(Math.max(coveredExchangeCount, 0));
        newState.setCompressionCount(1);           // 首次压缩 = 1
        newState.setSummaryVersion(1);
        newState.setSummaryText(summaryText);
        newState.setSummaryJson(summaryJson);
        newState.setLastSourceEditTime(lastSourceEditTime);
        newState.setStatus(BusinessStatus.YES.getCode());
        summaryMapper.insert(newState);
        return newState;
    }

    // ⑥ 分支 B：已有记录 → UPDATE（累加版本号和压缩次数）
    SuperAgentChatMemorySummary updateState = new SuperAgentChatMemorySummary();
    updateState.setId(latestState.getId());
    updateState.setCoveredExchangeId(coveredExchangeId);                                  // 推进覆盖进度
    updateState.setCoveredExchangeCount(Math.max(coveredExchangeCount, safeInt(latestState.getCoveredExchangeCount())));
    updateState.setCompressionCount(safeInt(latestState.getCompressionCount()) + 1);       // 压缩次数 +1
    updateState.setSummaryVersion(safeInt(latestState.getSummaryVersion()) + 1);           // 版本号 +1
    updateState.setSummaryText(summaryText);
    updateState.setSummaryJson(summaryJson);
    updateState.setLastSourceEditTime(lastSourceEditTime);
    summaryMapper.updateById(updateState);

    // ⑦ 回写到 latestState，让调用方能拿到最新值（避免调用方再次查 DB）
    latestState.setCoveredExchangeId(updateState.getCoveredExchangeId());
    latestState.setCoveredExchangeCount(updateState.getCoveredExchangeCount());
    latestState.setCompressionCount(updateState.getCompressionCount());
    latestState.setSummaryVersion(updateState.getSummaryVersion());
    latestState.setSummaryText(updateState.getSummaryText());
    latestState.setSummaryJson(updateState.getSummaryJson());
    latestState.setLastSourceEditTime(updateState.getLastSourceEditTime());
    return latestState;
}

/** 按 conversationId 查 memory_summary 表（取最新一条） */
private Optional<SuperAgentChatMemorySummary> findSummary(String conversationId) {
    return Optional.ofNullable(summaryMapper.selectOne(
        new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
            .eq(SuperAgentChatMemorySummary::getConversationId, conversationId)
            .orderByDesc(SuperAgentChatMemorySummary::getId)
            .last("LIMIT 1")
    ));
}

/**
 * 从 DB 记录中反序列化 ConversationSummaryPayload
 * <p>
 * 优先用 summaryJson 字段（结构化 JSON），解析失败或为空时
 * 用 summaryText 字段做兜底（纯文本摘要，无结构字段）。
 */
private ConversationSummaryPayload readSummaryPayload(SuperAgentChatMemorySummary summaryState) {
    if (summaryState == null) {
        return ConversationSummaryPayload.builder().build();
    }
    // 优先：从 summaryJson 解析结构化数据
    if (StrUtil.isNotBlank(summaryState.getSummaryJson())) {
        ConversationSummaryPayload payload = parseSummaryPayload(summaryState.getSummaryJson());
        if (payload != null) {
            return normalizePayload(payload);
        }
    }
    // 兜底：summaryJson 为空或解析失败 → 用 summaryText 做纯文本摘要
    ConversationSummaryPayload fallbackPayload = ConversationSummaryPayload.builder()
        .summary(summaryState.getSummaryText())
        .build();
    return normalizePayload(fallbackPayload);
}

/**
 * 从 LLM 输出中解析 JSON 为 ConversationSummaryPayload
 * <p>
 * LLM 输出可能包含 Markdown 代码块或多余文本，
 * 先用 {@link #extractJsonObject} 提取 JSON 对象，再用 Jackson 解析。
 *
 * @param raw LLM 原始输出文本
 * @return 解析后的 payload，失败返回 null
 */
private ConversationSummaryPayload parseSummaryPayload(String raw) {
    if (StrUtil.isBlank(raw)) {
        return null;
    }
    try {
        // ① 从原始文本中提取 JSON 对象（处理 LLM 输出的 Markdown 包装）
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        // ② 逐字段映射（用 path 而非 get，字段缺失时返回 null/missing node，不抛异常）
        ConversationSummaryPayload payload = ConversationSummaryPayload.builder()
            .summary(root.path("summary").asText(""))
            .conversationGoal(root.path("conversation_goal").asText(""))
            .stableFacts(readStringArray(root.path("stable_facts")))
            .userPreferences(readStringArray(root.path("user_preferences")))
            .resolvedPoints(readStringArray(root.path("resolved_points")))
            .pendingQuestions(readStringArray(root.path("pending_questions")))
            .retrievalHints(readStringArray(root.path("retrieval_hints")))
            .build();
        // ③ 规范化：裁剪超长字段 + 去重
        return normalizePayload(payload);
    }
    catch (Exception exception) {
        // JSON 解析失败（LLM 输出了非法格式）→ 返回 null，调用方会走 fallback
        log.debug("解析会话长期摘要 JSON 失败: {}", raw, exception);
        return null;
    }
}

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (StrUtil.isNotBlank(text)) {
                result.add(text);
            }
        });
        return result;
    }

    private String renderCompressionTranscript(List<ConversationExchangeView> batch) {
        StringBuilder builder = new StringBuilder();
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipTextAtSentence(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                // FAILED 的回答标注不可靠，LLM 做摘要时应降低权重
                if (exchange.getStatus() == ChatTurnStatus.FAILED) {
                    builder.append("助手（本回答生成失败，内容不可靠）：")
                        .append(clipTextAtSentence(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                        .append('\n');
                } else {
                    builder.append("助手：")
                        .append(clipTextAtSentence(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                        .append('\n');
                }
            }
            if (exchange.getStatus() == ChatTurnStatus.STOPPED && StrUtil.isNotBlank(exchange.getErrorMessage())) {
                builder.append("补充说明：本轮被停止，说明=")
                    .append(clipText(exchange.getErrorMessage(), MAX_ITEM_LENGTH))
                    .append('\n');
            }
        }
        return builder.toString().trim();
    }

/**
 * 规则兜底时提取对话批次的要点高亮（不调 LLM，纯文本拼接）
 * <p>
 * 遍历本批次的每条 exchange，提取"用户关注"和"已有结论"摘要，
 * 最多保留 4 条高亮（足够兜底，避免旧摘要无限膨胀）。
 * 用于 fallbackMerge 中替代 LLM 生成的 summary 字段。
 */
private String renderFallbackBatchHighlight(List<ConversationExchangeView> batch) {
    List<String> highlights = new ArrayList<>();
    for (ConversationExchangeView exchange : batch) {
        if (StrUtil.isNotBlank(exchange.getQuestion())) {
            highlights.add("用户关注：" + clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
        }
        if (StrUtil.isNotBlank(exchange.getAnswer())) {
            highlights.add("已有结论：" + clipText(exchange.getAnswer(), MAX_ITEM_LENGTH));
        }
        // 最多 4 条（2 对 Q+A），防止旧摘要过度膨胀
        if (highlights.size() >= 4) {
            break;
        }
    }
    return String.join("；", highlights);
}

    /**
     * 渲染近期对话原文 — 注入 LLM Prompt 的"近期窗口"
     * <p>
     * 从 exchange 表拉出的轮次列表中，取最近 keepRecentTurns 轮，
     * 格式化为 "用户：xxx\n助手：xxx\n" 文本，超出字符数上限时从尾部裁剪（保留最新）。
     * <p>
     * <b>步骤：</b>过滤 → 截取最近 N 轮 → 逐轮渲染 → 尾部裁剪
     *
     * @param exchanges               exchange 表拉取的原始轮次列表
     * @param keepRecentTurns         保留最近 N 轮（默认 4）
     * @param recentTranscriptMaxChars 字符数上限（默认 2200），超出从尾部裁剪
     * @return 格式化的近期对话文本
     */
    private String renderRecentTranscript(List<ConversationExchangeView> exchanges,
                                          int keepRecentTurns,
                                          int recentTranscriptMaxChars) {
        // ① 过滤：只保留非 RUNNING 且有内容的轮次
        List<ConversationExchangeView> renderableExchanges = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            if (shouldKeepInRecentWindow(exchange)) {
                renderableExchanges.add(exchange);
            }
        }
        if (renderableExchanges.isEmpty()) {
            return "";
        }
        // ② 从后往前截取最近 keepRecentTurns 轮
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        // ③ 逐轮渲染为 "用户：...\n助手：...\n"（question/answer 按句子边界截断）
        StringBuilder builder = new StringBuilder("【最近对话原文】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipTextAtSentence(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            // COMPLETED：正常渲染回答
            if (exchange.getStatus() == ChatTurnStatus.COMPLETED && StrUtil.isNotBlank(exchange.getAnswer())) {
                builder.append("助手：")
                    .append(clipTextAtSentence(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                    .append('\n');
            } else if (exchange.getStatus() != ChatTurnStatus.COMPLETED && StrUtil.isNotBlank(exchange.getErrorMessage())) {
                // FAILED/STOPPED：显式标注状态，让 LLM 知道这轮没有有效回答，防止 Q-A 错位
                builder.append("（")
                    .append(exchange.getStatus() == ChatTurnStatus.FAILED ? "本轮回答失败" : "本轮已停止")
                    .append("，原因：")
                    .append(clipText(exchange.getErrorMessage(), MAX_ITEM_LENGTH))
                    .append("）\n");
            }
        }

        // ④ 从尾部裁剪到字符数上限（保留最新内容，因为越新的对话对 LLM 越重要）
        return clipRecentTranscript(builder.toString().trim(), recentTranscriptMaxChars);
    }

    /**
     * 渲染近期对话（仅用户提问，不含助手回答）
     * <p>
     * <b>与 renderRecentTranscript 的核心区别：</b>
     * <table>
     *   <tr><td></td><td>renderRecentTranscript</td><td>renderAnswerRecentTranscript</td></tr>
     *   <tr><td>渲染内容</td><td>用户提问 + 助手回答</td><td>仅用户提问</td></tr>
     *   <tr><td>用途</td><td>注入 LLM Prompt 作为对话上下文</td><td>判断当前问题是否为追问</td></tr>
     *   <tr><td>标题</td><td>【最近对话原文】</td><td>【最近相关对话】</td></tr>
     *   <tr><td>过滤条件</td><td>shouldKeepInRecentWindow（需要 Q 或 A 有值）</td><td>仅需 question 非空</td></tr>
     *   <tr><td>截断方式</td><td>clipTextAtSentence（按句子边界）</td><td>clipText（固定字数）</td></tr>
     * </table>
     * <p>
     * <b>为什么不渲染助手回答？</b>
     * 这个方法的产物传给 AnswerHistoryContextAssembler，用于判断"用户刚才问的'那第二个呢？'
     * 是不是对着上一轮回答的追问"。它只需要知道用户问过什么，不需要助手答过什么。
     * 只渲染 question 可以省字符数，把预算留给更多轮次。
     *
     * @param exchanges       原始轮次列表
     * @param keepRecentTurns 保留最近 N 轮
     * @param maxChars        字符数上限
     * @return 仅含用户提问的格式化文本
     */
    private String renderAnswerRecentTranscript(List<ConversationExchangeView> exchanges,
                                                int keepRecentTurns,
                                                int maxChars) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        // ① 过滤：非 RUNNING 且有 question（与 shouldKeepInRecentWindow 不同：不要求 answer 有值）
        List<ConversationExchangeView> renderableExchanges = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            if (exchange != null
                && exchange.getStatus() != ChatTurnStatus.RUNNING
                && StrUtil.isNotBlank(exchange.getQuestion())) {
                renderableExchanges.add(exchange);
            }
        }
        if (renderableExchanges.isEmpty()) {
            return "";
        }
        // ② 从后往前截取最近 keepRecentTurns 轮
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        // ③ 逐轮渲染：只渲染 question，不渲染 answer
        StringBuilder builder = new StringBuilder("【最近相关对话】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            // 注意：这里故意不渲染 answer！追问判断不需要答案内容
        }
        // ④ 从尾部裁剪到字符数上限
        return clipRecentTranscript(builder.toString().trim(), maxChars);
    }

    private String buildLongTermSummaryText(ConversationSummaryPayload payload) {
        ConversationSummaryPayload normalizedPayload = normalizePayload(payload);
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "长期会话摘要", normalizedPayload.getSummary());
        appendSection(builder, "会话目标", normalizedPayload.getConversationGoal());
        appendBulletSection(builder, "已确认事实", normalizedPayload.getStableFacts());
        appendBulletSection(builder, "用户偏好与约束", normalizedPayload.getUserPreferences());
        appendBulletSection(builder, "已解决问题", normalizedPayload.getResolvedPoints());
        appendBulletSection(builder, "待跟进问题", normalizedPayload.getPendingQuestions());
        appendBulletSection(builder, "检索提示", normalizedPayload.getRetrievalHints());
        return clipText(builder.toString().trim(), properties.getHistorySummary().getSummaryMaxChars());
    }

    private String assembleHistory(String longTermSummary, String recentTranscript) {
        return joinNonBlank(longTermSummary, recentTranscript, "\n\n").trim();
    }

/**
 * 规范化 ConversationSummaryPayload 的每个字段
 * <p>
 * 对所有字段做三件事：
 * <ol>
 *   <li><b>裁剪</b>：超长字段截断到配置上限（summary→summaryMaxChars，goal→120，其余→80）</li>
 *   <li><b>去重</b>：列表字段用 LinkedHashSet 去重（保留首次出现顺序）</li>
 *   <li><b>限数</b>：列表字段最多保留 MAX_SECTION_ITEMS(6) 条</li>
 * </ol>
 * <p>
 * <b>summary 为空时的兜底：</b>
 * 如果 LLM 返回的 summary 字段为空，用其他非空字段拼接一个：
 * "目标：xxx；事实：xxx；待跟进：xxx"
 */
private ConversationSummaryPayload normalizePayload(ConversationSummaryPayload payload) {
    ConversationSummaryPayload workingPayload = payload == null ? ConversationSummaryPayload.builder().build() : payload;
    // summary 超长截断；为空时从其他字段合成
    String normalizedSummary = clipText(safeText(workingPayload.getSummary()), properties.getHistorySummary().getSummaryMaxChars());
    if (StrUtil.isBlank(normalizedSummary)) {
        normalizedSummary = synthesizeSummaryFromSections(workingPayload);
    }
    // 重建对象：每个字段都经过 裁剪 + 去重 + 限数
    return ConversationSummaryPayload.builder()
        .summary(normalizedSummary)
        .conversationGoal(clipText(safeText(workingPayload.getConversationGoal()), MAX_GOAL_LENGTH))
        .stableFacts(deduplicateAndLimit(workingPayload.getStableFacts()))
        .userPreferences(deduplicateAndLimit(workingPayload.getUserPreferences()))
        .resolvedPoints(deduplicateAndLimit(workingPayload.getResolvedPoints()))
        .pendingQuestions(deduplicateAndLimit(workingPayload.getPendingQuestions()))
        .retrievalHints(deduplicateAndLimit(workingPayload.getRetrievalHints()))
        .build();
}

/**
 * summary 为空时的兜底：从其他结构化字段拼出一个摘要文本
 * <p>
 * 格式："目标：xxx；事实：xxx；待跟进：xxx"
 */
private String synthesizeSummaryFromSections(ConversationSummaryPayload payload) {
    List<String> parts = new ArrayList<>();
    if (StrUtil.isNotBlank(payload.getConversationGoal())) {
        parts.add("目标：" + clipText(payload.getConversationGoal(), MAX_ITEM_LENGTH));
    }
    if (!safeList(payload.getStableFacts()).isEmpty()) {
        parts.add("事实：" + String.join("；", safeList(payload.getStableFacts())));
    }
    if (!safeList(payload.getPendingQuestions()).isEmpty()) {
        parts.add("待跟进：" + String.join("；", safeList(payload.getPendingQuestions())));
    }
    return clipText(String.join("；", parts), properties.getHistorySummary().getSummaryMaxChars());
}

/**
 * 去重 + 限数：LinkedHashSet 去重，每条裁剪到 MAX_ITEM_LENGTH(80)，最多 MAX_SECTION_ITEMS(6) 条
 */
private List<String> deduplicateAndLimit(List<String> values) {
    LinkedHashSet<String> deduplicated = new LinkedHashSet<>();  // 保留插入顺序的去重集合
    for (String value : safeList(values)) {
        String text = clipText(safeText(value), MAX_ITEM_LENGTH);  // 每条裁剪到 80 字
        if (StrUtil.isNotBlank(text)) {
            deduplicated.add(text);
        }
        if (deduplicated.size() >= MAX_SECTION_ITEMS) {  // 最多 6 条
            break;
        }
    }
    return new ArrayList<>(deduplicated);
}

/**
 * 深拷贝 ConversationSummaryPayload（避免修改传入的原对象）
 */
private ConversationSummaryPayload copyPayload(ConversationSummaryPayload payload) {
    if (payload == null) {
        return ConversationSummaryPayload.builder().build();
    }
    return ConversationSummaryPayload.builder()
            .summary(payload.getSummary())
            .conversationGoal(payload.getConversationGoal())
            .stableFacts(new ArrayList<>(safeList(payload.getStableFacts())))
            .userPreferences(new ArrayList<>(safeList(payload.getUserPreferences())))
            .resolvedPoints(new ArrayList<>(safeList(payload.getResolvedPoints())))
            .pendingQuestions(new ArrayList<>(safeList(payload.getPendingQuestions())))
            .retrievalHints(new ArrayList<>(safeList(payload.getRetrievalHints())))
            .build();
    }

    private String writePayloadJson(ConversationSummaryPayload payload) {
        try {
            return objectMapper.writeValueAsString(normalizePayload(payload));
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化会话长期摘要失败", exception);
        }
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }

    private boolean isStableSummaryExchange(ConversationExchangeView exchange) {
        if (exchange == null || exchange.getStatus() == null) {
            return false;
        }

        return exchange.getStatus() == ChatTurnStatus.COMPLETED
            && StrUtil.isNotBlank(exchange.getQuestion());
    }

    private boolean shouldKeepInRecentWindow(ConversationExchangeView exchange) {
        return exchange != null
            && exchange.getStatus() != ChatTurnStatus.RUNNING
            && (StrUtil.isNotBlank(exchange.getQuestion()) || StrUtil.isNotBlank(exchange.getAnswer()));
    }

    private int recentFetchLimit(int keepRecentTurns) {

        return Math.max(keepRecentTurns * 3, keepRecentTurns + 4);
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append('【').append(title).append("】\n")
            .append(content.trim())
            .append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        List<String> normalizedValues = safeList(values);
        if (normalizedValues.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append('【').append(title).append("】\n");
        for (String value : normalizedValues) {
            builder.append("- ").append(value).append('\n');
        }
    }

    private String clipText(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    /**
     * 按句子边界截断，避免在词语/句子中间切断
     * <p>
     * 在 maxChars 范围内向前查找最后一个句子结束标记（。！？\n；;），
     * 找到则截断到该位置 + "…"，找不到则退化为 {@link #clipText}。
     */
    private String clipTextAtSentence(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        // 在 maxChars 范围内向前搜索句子边界（最多回溯 40 字）
        int cutPoint = Math.max(0, maxChars - 1);
        for (int i = cutPoint; i >= Math.max(0, maxChars - 40); i--) {
            char c = normalized.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '；' || c == ';') {
                return normalized.substring(0, i + 1) + "…";
            }
        }
        // 找不到句子边界，退化为原 clipText（保证不超长）
        return clipText(normalized, maxChars);
    }

    private String clipRecentTranscript(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        int startIndex = Math.max(0, normalized.length() - Math.max(0, maxChars - 1));
        return "…" + normalized.substring(startIndex);
    }

    private String joinNonBlank(String left, String right, String delimiter) {
        if (StrUtil.isBlank(left)) {
            return safeText(right);
        }
        if (StrUtil.isBlank(right)) {
            return safeText(left);
        }
        return safeText(left) + delimiter + safeText(right);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Date resolveSourceTime(ConversationExchangeView exchange) {
        if (exchange == null) {
            return null;
        }
        return exchange.getEditTime() != null ? exchange.getEditTime() : exchange.getCreateTime();
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private ConversationMemoryContext emptyContext() {
        return ConversationMemoryContext.builder()
            .assembledHistory("")
            .longTermSummary("")
            .recentTranscript("")
            .answerRecentTranscript("")
            .summaryPayload(ConversationSummaryPayload.builder().build())
            .coveredExchangeId(0L)
            .coveredExchangeCount(0)
            .compressionCount(0)
            .compressionApplied(false)
            .build();
    }

    private ConversationMemorySummaryView toSummaryView(String conversationId, SuperAgentChatMemorySummary summaryState) {
        if (summaryState == null) {
            return emptySummaryView(conversationId);
        }
        return new ConversationMemorySummaryView(
            conversationId,
            StrUtil.isNotBlank(summaryState.getSummaryText()),
            defaultLong(summaryState.getCoveredExchangeId()),
            safeInt(summaryState.getCoveredExchangeCount()),
            safeInt(summaryState.getCompressionCount()),
            safeInt(summaryState.getSummaryVersion()),
            safeText(summaryState.getSummaryText()),
            readSummaryPayload(summaryState),
            toInstant(summaryState.getLastSourceEditTime()),
            toInstant(summaryState.getEditTime())
        );
    }

    private ConversationMemorySummaryView emptySummaryView(String conversationId) {
        return new ConversationMemorySummaryView(
            conversationId,
            false,
            0L,
            0,
            0,
            0,
            "",
            ConversationSummaryPayload.builder().build(),
            null,
            null
        );
    }

    private List<String> extractRetrievalHints(String question) {
        if (StrUtil.isBlank(question)) {
            return List.of();
        }
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        Matcher matcher = RETRIEVAL_HINT_PATTERN.matcher(question);
        while (matcher.find()) {
            String hint = matcher.group().trim();
            if (hint.length() >= 2 && !isNoiseHint(hint)) {
                hints.add(clipText(hint, MAX_ITEM_LENGTH));
            }
            if (hints.size() >= MAX_SECTION_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(hints);
    }

    private boolean isNoiseHint(String value) {
        return "请问".equals(value)
            || "帮我".equals(value)
            || "一下".equals(value)
            || "如何".equals(value)
            || "怎么".equals(value)
            || "什么".equals(value)
            || "哪个".equals(value)
            || "这个".equals(value)
            || "那个".equals(value)
            || "可以".equals(value)
            || "需要".equals(value);
    }
}
