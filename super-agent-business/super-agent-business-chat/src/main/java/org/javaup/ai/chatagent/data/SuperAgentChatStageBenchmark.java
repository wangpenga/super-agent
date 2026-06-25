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

import java.util.Date;

/**
 * 阶段性能基准表 (super_agent_chat_stage_benchmark)
 * <p>
 * 记录各执行阶段在不同执行模式下的性能基准数据，包括 P50/P90/P99 耗时、
 * 平均/最大/最小耗时、样本数量以及最近 N 次耗时记录（用于滑动窗口计算）。
 * <p>
 * 用于性能监控和阶段耗时分析，帮助识别系统瓶颈。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_stage_benchmark")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatStageBenchmark extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 阶段编码 */
    @TableField("stage_code")
    private String stageCode;

    /** 执行模式：RAG_CHAT / REACT_AGENT */
    @TableField("execution_mode")
    private String executionMode;

    /** P50 耗时（毫秒） */
    @TableField("p50_duration_ms")
    private Long p50DurationMs;

    /** P90 耗时（毫秒） */
    @TableField("p90_duration_ms")
    private Long p90DurationMs;

    /** P99 耗时（毫秒） */
    @TableField("p99_duration_ms")
    private Long p99DurationMs;

    /** 平均耗时（毫秒） */
    @TableField("avg_duration_ms")
    private Long avgDurationMs;

    /** 最大耗时（毫秒） */
    @TableField("max_duration_ms")
    private Long maxDurationMs;

    /** 最小耗时（毫秒） */
    @TableField("min_duration_ms")
    private Long minDurationMs;

    /** 样本数量 */
    @TableField("sample_count")
    private Integer sampleCount;

    /** 最近 N 次耗时记录，JSON 格式（用于滑动窗口计算） */
    @TableField("recent_durations")
    private String recentDurations;

    /** 最后更新时间 */
    @TableField("last_update_time")
    private Date lastUpdateTime;
}
