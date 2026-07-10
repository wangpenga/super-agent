package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 检索通道执行详情表 (super_agent_chat_channel_execution)
 * <p>
 * 记录每个检索通道（vector/keyword）在每个子问题上的执行详情，
 * 包括执行状态、耗时（毫秒精度）、召回数、闸门后保留数、最终选入 Prompt 数、
 * 分数统计（平均/最高/最低）以及通道配置快照。
 * <p>
 * 用于分析各检索通道的性能和效果。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_channel_execution")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatChannelExecution extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属业务会话编号 (dialogue_code) */
    @TableField("dialogue_code")
    private String conversationId;

    /** 所属轮次id */
    @TableField("exchange_id")
    private Long exchangeId;

    /** 本轮执行 trace id */
    @TableField("trace_id")
    private String traceId;

    /** 子问题下标（从1开始） */
    @TableField("sub_question_index")
    private Integer subQuestionIndex;

    /** 子问题文本 */
    @TableField("sub_question")
    private String subQuestion;

    /** 检索通道：vector / keyword */
    @TableField("channel_type")
    private String channelType;

    /**
     * 执行状态 (execution_state)
     * 1:成功 2:失败 3:超时 4:跳过
     */
    @TableField("execution_state")
    private Integer executionState;

    /** 通道执行开始时间（毫秒精度） */
    @TableField("start_time")
    private Date startTime;

    /** 通道执行结束时间（毫秒精度） */
    @TableField("end_time")
    private Date endTime;

    /** 通道执行耗时，毫秒 */
    @TableField("duration_ms")
    private Long durationMs;

    /** 原始召回数 */
    @TableField("recalled_count")
    private Integer recalledCount;

    /** 闸门后保留数 */
    @TableField("accepted_count")
    private Integer acceptedCount;

    /** 最终选入 Prompt 数 */
    @TableField("final_selected_count")
    private Integer finalSelectedCount;

    /** 平均分数 */
    @TableField("avg_score")
    private BigDecimal avgScore;

    /** 最高分数 */
    @TableField("max_score")
    private BigDecimal maxScore;

    /** 最低分数 */
    @TableField("min_score")
    private BigDecimal minScore;

    /** 通道配置快照（topK、阈值等），JSON 格式 */
    @TableField("config_snapshot")
    private String configSnapshot;

    /** 错误信息 */
    @TableField("error_message")
    private String errorMessage;
}
