package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 检索通道执行详情视图 —— 写入 super_agent_chat_channel_execution 表
 * <p>
 * 记录每个检索通道在每个子问题上的执行效果，用于分析通道性能。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelExecutionView {

    private Long id;
    /** 所属 traceId */
    private String traceId;
    /** 子问题序号（从 1 开始） */
    private int subQuestionIndex;
    /** 子问题文本 */
    private String subQuestion;
    /** 检索通道：vector / keyword */
    private String channelType;
    /**
     * 执行状态
     * 1:成功 2:失败 3:超时 4:跳过
     */
    private int executionState;
    /** 通道开始时间（毫秒精度） */
    private Instant startTime;
    /** 通道结束时间（毫秒精度） */
    private Instant endTime;
    /** 通道执行耗时（毫秒） */
    private Long durationMs;
    /** 原始召回数量 */
    private int recalledCount;
    /** 闸门过滤后保留数量 */
    private int acceptedCount;
    /** 最终选入 Prompt 的数量 */
    private int finalSelectedCount;
    /** 召回结果平均分数 */
    private BigDecimal avgScore;
    /** 召回结果最高分数 */
    private BigDecimal maxScore;
    /** 召回结果最低分数 */
    private BigDecimal minScore;
    /** 错误信息（仅失败时有值） */
    private String errorMessage;
    private Instant createTime;
}
