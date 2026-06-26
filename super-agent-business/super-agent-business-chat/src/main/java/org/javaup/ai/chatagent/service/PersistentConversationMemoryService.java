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
 * @author 阿星不是程序员
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
    // ④ 渲染近期对话原文（给 LLM 看的）
    String recentTranscript = renderRecentTranscript(
        recentExchanges,
        historySummaryProperties.getKeepRecentTurns(),
        historySummaryProperties.getRecentTranscriptMaxChars()
    );
    // ⑤ 渲染近期助手回答（用于追问判断）
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

    private ConversationSummaryPayload mergeSummaryPayload(ConversationSummaryPayload existingPayload,
                                                           List<ConversationExchangeView> batch,
                                                           ConversationTraceRecorder traceRecorder) {
        try {
            String content = observedChatModelService.callText(
                "summary",
                promptTemplateService.render(PromptTemplateNames.CONVERSATION_SUMMARY_SYSTEM, Map.of()),
                buildSummaryMergePrompt(existingPayload, batch),
                traceRecorder
            );
            ConversationSummaryPayload parsedPayload = parseSummaryPayload(content);
            if (parsedPayload != null) {
                return normalizePayload(parsedPayload);
            }
        }
        catch (Exception exception) {
            log.warn("合并会话长期摘要失败，回退到规则压缩: {}", exception.getMessage());
        }
        return fallbackMerge(existingPayload, batch);
    }

    private String buildSummaryMergePrompt(ConversationSummaryPayload existingPayload,
                                           List<ConversationExchangeView> batch) {
        String existingJson = writePayloadJson(normalizePayload(copyPayload(existingPayload)));
        return promptTemplateService.render(PromptTemplateNames.CONVERSATION_SUMMARY_MERGE, Map.of(
            "existingSummaryJson", StrUtil.isNotBlank(existingJson) ? existingJson : "{}",
            "newConversationBatch", renderCompressionTranscript(batch)
        ));
    }

    private ConversationSummaryPayload fallbackMerge(ConversationSummaryPayload existingPayload,
                                                     List<ConversationExchangeView> batch) {
        ConversationSummaryPayload mergedPayload = copyPayload(existingPayload);
        String batchHighlight = renderFallbackBatchHighlight(batch);
        String mergedSummary = joinNonBlank(mergedPayload.getSummary(), batchHighlight, "；");
        mergedPayload.setSummary(clipText(mergedSummary, properties.getHistorySummary().getSummaryMaxChars()));

        ConversationExchangeView lastExchange = batch.get(batch.size() - 1);
        if (StrUtil.isBlank(mergedPayload.getConversationGoal()) && StrUtil.isNotBlank(lastExchange.getQuestion())) {
            mergedPayload.setConversationGoal(clipText(lastExchange.getQuestion(), MAX_GOAL_LENGTH));
        }

        List<String> pendingQuestions = new ArrayList<>(safeList(mergedPayload.getPendingQuestions()));
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                pendingQuestions.add(clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
            }
        }
        mergedPayload.setPendingQuestions(deduplicateAndLimit(pendingQuestions));

        List<String> retrievalHints = new ArrayList<>(safeList(mergedPayload.getRetrievalHints()));
        if (StrUtil.isNotBlank(lastExchange.getQuestion())) {
            retrievalHints.addAll(extractRetrievalHints(lastExchange.getQuestion()));
        }
        mergedPayload.setRetrievalHints(deduplicateAndLimit(retrievalHints));
        return normalizePayload(mergedPayload);
    }

    private SuperAgentChatMemorySummary saveSummarySnapshot(String conversationId,
                                                            SuperAgentChatMemorySummary currentState,
                                                            ConversationSummaryPayload payload,
                                                            long coveredExchangeId,
                                                            int coveredExchangeCount,
                                                            Date lastSourceEditTime) {
        SuperAgentChatMemorySummary latestState = findSummary(conversationId).orElse(null);
        long latestCoveredExchangeId = latestState == null ? 0L : defaultLong(latestState.getCoveredExchangeId());

        if (latestCoveredExchangeId > coveredExchangeId) {
            return latestState;
        }

        if (latestState != null
            && latestCoveredExchangeId == coveredExchangeId
            && StrUtil.isNotBlank(latestState.getSummaryText())) {
            return latestState;
        }

        String summaryText = buildLongTermSummaryText(payload);
        String summaryJson = writePayloadJson(payload);

        if (latestState == null) {
            SuperAgentChatMemorySummary newState = new SuperAgentChatMemorySummary();
            newState.setId(uidGenerator.getUid());
            newState.setConversationId(conversationId);
            newState.setCoveredExchangeId(coveredExchangeId);
            newState.setCoveredExchangeCount(Math.max(coveredExchangeCount, 0));
            newState.setCompressionCount(1);
            newState.setSummaryVersion(1);
            newState.setSummaryText(summaryText);
            newState.setSummaryJson(summaryJson);
            newState.setLastSourceEditTime(lastSourceEditTime);
            newState.setStatus(BusinessStatus.YES.getCode());
            summaryMapper.insert(newState);
            return newState;
        }

        SuperAgentChatMemorySummary updateState = new SuperAgentChatMemorySummary();
        updateState.setId(latestState.getId());
        updateState.setCoveredExchangeId(coveredExchangeId);
        updateState.setCoveredExchangeCount(Math.max(coveredExchangeCount, safeInt(latestState.getCoveredExchangeCount())));
        updateState.setCompressionCount(safeInt(latestState.getCompressionCount()) + 1);
        updateState.setSummaryVersion(safeInt(latestState.getSummaryVersion()) + 1);
        updateState.setSummaryText(summaryText);
        updateState.setSummaryJson(summaryJson);
        updateState.setLastSourceEditTime(lastSourceEditTime);
        summaryMapper.updateById(updateState);

        latestState.setCoveredExchangeId(updateState.getCoveredExchangeId());
        latestState.setCoveredExchangeCount(updateState.getCoveredExchangeCount());
        latestState.setCompressionCount(updateState.getCompressionCount());
        latestState.setSummaryVersion(updateState.getSummaryVersion());
        latestState.setSummaryText(updateState.getSummaryText());
        latestState.setSummaryJson(updateState.getSummaryJson());
        latestState.setLastSourceEditTime(updateState.getLastSourceEditTime());
        return latestState;
    }

    private Optional<SuperAgentChatMemorySummary> findSummary(String conversationId) {
        return Optional.ofNullable(summaryMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
                .eq(SuperAgentChatMemorySummary::getConversationId, conversationId)
                .orderByDesc(SuperAgentChatMemorySummary::getId)
                .last("LIMIT 1")
        ));
    }

    private ConversationSummaryPayload readSummaryPayload(SuperAgentChatMemorySummary summaryState) {
        if (summaryState == null) {
            return ConversationSummaryPayload.builder().build();
        }
        if (StrUtil.isNotBlank(summaryState.getSummaryJson())) {
            ConversationSummaryPayload payload = parseSummaryPayload(summaryState.getSummaryJson());
            if (payload != null) {
                return normalizePayload(payload);
            }
        }
        ConversationSummaryPayload fallbackPayload = ConversationSummaryPayload.builder()
            .summary(summaryState.getSummaryText())
            .build();
        return normalizePayload(fallbackPayload);
    }

    private ConversationSummaryPayload parseSummaryPayload(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            ConversationSummaryPayload payload = ConversationSummaryPayload.builder()
                .summary(root.path("summary").asText(""))
                .conversationGoal(root.path("conversation_goal").asText(""))
                .stableFacts(readStringArray(root.path("stable_facts")))
                .userPreferences(readStringArray(root.path("user_preferences")))
                .resolvedPoints(readStringArray(root.path("resolved_points")))
                .pendingQuestions(readStringArray(root.path("pending_questions")))
                .retrievalHints(readStringArray(root.path("retrieval_hints")))
                .build();
            return normalizePayload(payload);
        }
        catch (Exception exception) {
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
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                builder.append("助手：")
                    .append(clipText(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                    .append('\n');
            }
            if (exchange.getStatus() == ChatTurnStatus.STOPPED && StrUtil.isNotBlank(exchange.getErrorMessage())) {
                builder.append("补充说明：本轮被停止，说明=")
                    .append(clipText(exchange.getErrorMessage(), MAX_ITEM_LENGTH))
                    .append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String renderFallbackBatchHighlight(List<ConversationExchangeView> batch) {
        List<String> highlights = new ArrayList<>();
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                highlights.add("用户关注：" + clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                highlights.add("已有结论：" + clipText(exchange.getAnswer(), MAX_ITEM_LENGTH));
            }
            if (highlights.size() >= 4) {
                break;
            }
        }
        return String.join("；", highlights);
    }

    private String renderRecentTranscript(List<ConversationExchangeView> exchanges,
                                          int keepRecentTurns,
                                          int recentTranscriptMaxChars) {
        List<ConversationExchangeView> renderableExchanges = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            if (shouldKeepInRecentWindow(exchange)) {
                renderableExchanges.add(exchange);
            }
        }
        if (renderableExchanges.isEmpty()) {
            return "";
        }
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        StringBuilder builder = new StringBuilder("【最近对话原文】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            if (exchange.getStatus() == ChatTurnStatus.COMPLETED && StrUtil.isNotBlank(exchange.getAnswer())) {
                builder.append("助手：")
                    .append(clipText(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                    .append('\n');
            }
        }

    // ④ 从尾部裁剪到字符数上限（保留最新内容）
    return clipRecentTranscript(builder.toString().trim(), recentTranscriptMaxChars);
}

    private String renderAnswerRecentTranscript(List<ConversationExchangeView> exchanges,
                                                int keepRecentTurns,
                                                int maxChars) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
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
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        StringBuilder builder = new StringBuilder("【最近相关对话】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }

        }
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

    private ConversationSummaryPayload normalizePayload(ConversationSummaryPayload payload) {
        ConversationSummaryPayload workingPayload = payload == null ? ConversationSummaryPayload.builder().build() : payload;
        String normalizedSummary = clipText(safeText(workingPayload.getSummary()), properties.getHistorySummary().getSummaryMaxChars());
        if (StrUtil.isBlank(normalizedSummary)) {
            normalizedSummary = synthesizeSummaryFromSections(workingPayload);
        }
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

    private List<String> deduplicateAndLimit(List<String> values) {
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String value : safeList(values)) {
            String text = clipText(safeText(value), MAX_ITEM_LENGTH);
            if (StrUtil.isNotBlank(text)) {
                deduplicated.add(text);
            }
            if (deduplicated.size() >= MAX_SECTION_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(deduplicated);
    }

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
