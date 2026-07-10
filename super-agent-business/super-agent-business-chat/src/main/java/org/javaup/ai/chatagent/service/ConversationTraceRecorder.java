package org.javaup.ai.chatagent.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.debug.ChatLimitStats;
import org.javaup.ai.chatagent.model.debug.ChatModelUsageTrace;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageState;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Throwables.getStackTraceAsString;

/**
 * 对话执行追踪记录器
 * <p>
 * 负责在对话执行过程中分阶段记录追踪信息，数据写入
 * <b>super_agent_chat_exchange_trace_stage</b> 表。
 * <p>
 * <b>典型用法：</b>
 * <pre>
 *   StageHandle h = recorder.startStage(MEMORY, "RAG_CHAT", "正在装载记忆", null);
 *   // ... 执行记忆装载 ...
 *   recorder.completeStage(h, "记忆装载完成", Map.of("compressed", true));
 * </pre>
 * <p>
 * <b>写入的数据库表字段：</b>
 * <table>
 *   <tr><td>dialogue_code</td><td>会话编号 = conversationId</td></tr>
 *   <tr><td>exchange_id</td><td>轮次 ID（哪一轮对话）</td></tr>
 *   <tr><td>trace_id</td><td>本次执行的唯一 traceId（同一轮多个阶段共享）</td></tr>
 *   <tr><td>stage_code</td><td>阶段编码：MEMORY / REWRITE / ROUTE / RAG_RETRIEVE / EVIDENCE_BUDGET / ANSWER_GENERATE / REACT_AGENT / FINALIZE / RECOMMENDATION</td></tr>
 *   <tr><td>stage_name</td><td>阶段名称（自动从 stageCode 解析）</td></tr>
 *   <tr><td>stage_order</td><td>阶段顺序号（自动递增）</td></tr>
 *   <tr><td>stage_level</td><td>1=一级阶段 2=二级子步骤</td></tr>
 *   <tr><td>parent_stage_id</td><td>父阶段 ID（二级子步骤时指向一级阶段）</td></tr>
 *   <tr><td>execution_mode</td><td>执行模式：RAG_CHAT / REACT_AGENT / CLARIFICATION</td></tr>
 *   <tr><td>stage_state</td><td>阶段状态：1=运行中 2=完成 3=失败 4=跳过</td></tr>
 *   <tr><td>start_time</td><td>阶段开始时间</td></tr>
 *   <tr><td>end_time</td><td>阶段结束时间</td></tr>
 *   <tr><td>duration_ms</td><td>阶段耗时（毫秒），startTime 到 complete/fail 的时间差</td></tr>
 *   <tr><td>summary_text</td><td>阶段摘要（如"记忆装载完成"）</td></tr>
 *   <tr><td>error_message</td><td>阶段错误信息（仅 fail 时有值）</td></tr>
 *   <tr><td>snapshot_json</td><td>阶段结构化快照（JSON），记录该阶段的详细输入输出数据</td></tr>
 * </table>
 *
 * @author wangpeng
 */
@Slf4j
public class ConversationTraceRecorder {

    /** 阶段轨迹表的存储接口 —— 用于写入 trace_stage 表 */
    private final ConversationTraceStageStore traceStageStore;

    /** 检索结果表的存储接口 —— 用于写入 retrieval_result 和 channel_execution 表 */
    private final RetrievalObserveStore retrievalObserveStore;

    /** 所属会话 ID → 写入 trace_stage.dialogue_code */
    private final String conversationId;

    /** 所属轮次 ID → 写入 trace_stage.exchange_id */
    private final long exchangeId;

    /** 本次执行的唯一 traceId → 写入 trace_stage.trace_id，同一轮所有阶段共享同一个 traceId */
    private final String traceId;

    /**
     * LLM 调用追踪列表（线程安全）
     * 记录本轮对话中每次 LLM 调用的耗时、Token 用量等指标
     */
    private final List<ChatModelUsageTrace> modelUsageTraces = Collections.synchronizedList(new ArrayList<>());

    /**
     * 调用限制统计
     * 记录本轮对话的模型调用次数上限和工具调用次数上限
     */
    private final ChatLimitStats limitStats = new ChatLimitStats();

    public ConversationTraceRecorder(ConversationTraceStageStore traceStageStore,
                                     RetrievalObserveStore retrievalObserveStore,
                                     String conversationId,
                                     long exchangeId,
                                     String traceId) {
        this.traceStageStore = traceStageStore;
        this.retrievalObserveStore = retrievalObserveStore;
        this.conversationId = conversationId;
        this.exchangeId = exchangeId;
        this.traceId = traceId;
    }

    public String conversationId() {
        return conversationId;
    }

    public long exchangeId() {
        return exchangeId;
    }

    public String traceId() {
        return traceId;
    }

    /**
     * 开启一个追踪阶段 → INSERT 一条 trace_stage 记录（state=RUNNING）
     *
     * @param stageCode     阶段编码（MEMORY / REWRITE / ROUTE / ...）
     * @param executionMode 执行模式（RAG_CHAT / REACT_AGENT / ...）
     * @param summaryText   阶段摘要（如"正在装载会话记忆"）
     * @param snapshot      结构化快照（可 null，后续 complete/fail 时覆盖）
     * @return StageHandle：包含 stageId（数据库主键）+ 开始时间戳 + 阶段编码
     */
    public StageHandle startStage(ConversationTraceStageCode stageCode,
                                  String executionMode,
                                  String summaryText,
                                  Object snapshot) {
        // INSERT 到 trace_stage 表，返回自增/生成的 stageId
        long stageId = traceStageStore.startStage(
            conversationId,      // → dialogue_code
            exchangeId,          // → exchange_id
            traceId,             // → trace_id
            stageCode,           // → stage_code + stage_name（自动解析）
            1,                   // → stage_level = 1（一级阶段）
            null,                // → parent_stage_id = null（无父阶段）
            executionMode,       // → execution_mode
            summaryText,         // → summary_text
            snapshot             // → snapshot_json
        );
        return new StageHandle(stageId, System.currentTimeMillis(), stageCode);
    }

    /**
     * 完成一个追踪阶段 → UPDATE trace_stage 记录（state=COMPLETED）
     *
     * @param stageHandle startStage 返回的句柄
     * @param summaryText 阶段完成摘要（覆盖 start 时的摘要）
     * @param snapshot    结构化快照（覆盖 start 时的快照）
     */
    public void completeStage(StageHandle stageHandle,
                              String summaryText,
                              Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        traceStageStore.finishStage(
            stageHandle.stageId(),                                       // 主键
            ConversationTraceStageState.COMPLETED,                       // → stage_state = 2（完成）
            summaryText,                                                 // → summary_text
            "",                                                          // → error_message（空）
            snapshot,                                                    // → snapshot_json
            System.currentTimeMillis() - stageHandle.startTimeMs()       // → duration_ms
        );
    }

    /**
     * 标记追踪阶段失败 → UPDATE trace_stage 记录（state=FAILED）
     */
    public void failStage(StageHandle stageHandle,
                          String summaryText,
                          String errorMessage,
                          Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        traceStageStore.finishStage(
            stageHandle.stageId(),
            ConversationTraceStageState.FAILED,   // → stage_state = 3（失败）
            summaryText,
            errorMessage,                          // → error_message
            snapshot,
            System.currentTimeMillis() - stageHandle.startTimeMs()
        );
    }

    /**
     * 标记追踪阶段失败（带异常堆栈） → UPDATE trace_stage 记录（state=FAILED）
     * 与上面相比，会把异常的 class 名和堆栈信息注入到 snapshot 中
     */
    public void failStage(StageHandle stageHandle,
                          String summaryText,
                          Throwable throwable,
                          Object snapshot) {
        if (stageHandle == null) {
            return;
        }
        String errorMessage = throwable == null ? "" : throwable.getMessage();
        String stackTrace = throwable == null ? "" : getStackTraceAsString(throwable);

        // 把异常信息注入到 snapshot 中，方便调试
        Object enhancedSnapshot = snapshot;
        if (throwable != null && snapshot instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshotMap = new LinkedHashMap<>((Map<String, Object>) snapshot);
            snapshotMap.put("exceptionClass", throwable.getClass().getName());
            snapshotMap.put("stackTrace", stackTrace);
            enhancedSnapshot = snapshotMap;
        } else if (throwable != null) {
            enhancedSnapshot = Map.of(
                "exceptionClass", throwable.getClass().getName(),
                "errorMessage", errorMessage,
                "stackTrace", stackTrace
            );
        }

        traceStageStore.finishStage(
            stageHandle.stageId(),
            ConversationTraceStageState.FAILED,
            summaryText,
            errorMessage,
            enhancedSnapshot,
            System.currentTimeMillis() - stageHandle.startTimeMs()
        );
    }

    /**
     * 记录一次 LLM 调用追踪（追加到内存列表，不影响 trace_stage 表）
     * 最终在 finishSuccessfully/finishWithFailure 中通过 snapshotModelUsageTraces 导出
     */
    public void addModelUsageTrace(ChatModelUsageTrace trace) {
        if (trace != null) {
            modelUsageTraces.add(trace);
        }
    }

    /**
     * 获取所有 LLM 调用追踪的快照（线程安全）
     */
    public List<ChatModelUsageTrace> snapshotModelUsageTraces() {
        return new ArrayList<>(modelUsageTraces);
    }

    /**
     * 获取调用限制统计对象
     * 在 finishSuccessfully/finishWithFailure 中会填入实际调用次数和上限
     */
    public ChatLimitStats limitStats() {
        return limitStats;
    }

    /**
     * 批量写入检索结果到 super_agent_chat_retrieval_result 表
     */
    public void recordRetrievalResults(List<RetrievalResultView> results) {
        if (retrievalObserveStore == null || results == null || results.isEmpty()) {
            return;
        }
        try {
            retrievalObserveStore.batchSaveResults(conversationId, exchangeId, results);
        } catch (RuntimeException exception) {
            log.warn("记录检索结果快照失败, conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    /**
     * 批量写入通道执行详情到 super_agent_chat_channel_execution 表
     */
    public void recordChannelExecutions(List<ChannelExecutionView> executions) {
        if (retrievalObserveStore == null || executions == null || executions.isEmpty()) {
            return;
        }
        try {
            retrievalObserveStore.batchSaveChannelExecutions(conversationId, exchangeId, executions);
        } catch (RuntimeException exception) {
            log.warn("记录通道执行详情失败, conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    /**
     * 阶段句柄 —— startStage 的返回值
     *
     * @param stageId     数据库主键（trace_stage.id），后续 complete/fail 时用来定位记录
     * @param startTimeMs 阶段开始时间戳（System.currentTimeMillis()），用于计算耗时
     * @param stageCode   阶段编码
     */
    public record StageHandle(long stageId, long startTimeMs, ConversationTraceStageCode stageCode) {
    }
}
