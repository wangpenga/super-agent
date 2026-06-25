package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageState;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageView;

import java.util.List;

/**
 * 阶段轨迹存储接口 —— 操作 super_agent_chat_exchange_trace_stage 表
 *
 * @author 阿星不是程序员
 */
public interface ConversationTraceStageStore {

    /**
     * 开启追踪阶段 → INSERT 一条记录（stage_state = RUNNING）
     *
     * @param conversationId 会话编号 → dialogue_code
     * @param exchangeId     轮次 ID → exchange_id
     * @param traceId        唯一追踪 ID → trace_id
     * @param stageCode      阶段编码 → stage_code + stage_name
     * @param stageLevel     1=一级阶段 2=二级子步骤
     * @param parentStageId  父阶段 ID（二级子步骤时非 null）
     * @param executionMode  执行模式（RAG_CHAT / REACT_AGENT）
     * @param summaryText    阶段摘要
     * @param snapshot       结构化快照（JSON 序列化后写入 snapshot_json）
     * @return 数据库主键 stageId
     */
    long startStage(String conversationId,
                    long exchangeId,
                    String traceId,
                    ConversationTraceStageCode stageCode,
                    int stageLevel,
                    Long parentStageId,
                    String executionMode,
                    String summaryText,
                    Object snapshot);

    /**
     * 完成/失败追踪阶段 → UPDATE 记录
     *
     * @param stageId       数据库主键
     * @param stageState    结束状态：COMPLETED / FAILED
     * @param summaryText   阶段摘要（覆盖 start 时的值）
     * @param errorMessage  错误信息（仅 FAILED 时写入）
     * @param snapshot      结构化快照（覆盖 start 时的值）
     * @param durationMs    阶段耗时 = endTime - startTime
     */
    void finishStage(long stageId,
                     ConversationTraceStageState stageState,
                     String summaryText,
                     String errorMessage,
                     Object snapshot,
                     long durationMs);

    /** 查询某轮对话的所有阶段轨迹 */
    List<ConversationTraceStageView> listStageViews(String conversationId, long exchangeId);

    /** 删除某会话的所有阶段轨迹（会话重置时调用） */
    void deleteStages(String conversationId);
}
