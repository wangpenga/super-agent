package org.javaup.ai.eval.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 指标日汇总 —— 每天各指标的聚合统计
 * <p>
 * 用于 Dashboard 趋势图，支持按日期维度查看指标变化。
 * 通过 {@code metricName} 区分具体指标类型。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_eval_metric_daily")
@EqualsAndHashCode(callSuper = true)
public class EvalMetricDaily extends BaseEvalEntity {

    /** 主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 统计日期 */
    private Date metricDate;

    /** 指标名：context_precision / context_recall / faithfulness / answer_relevancy */
    private String metricName;

    /** 日均值 */
    private BigDecimal avgValue;

    /** P50 中位数 */
    private BigDecimal p50;

    /** P90 值 */
    private BigDecimal p90;

    /** 最小值 */
    private BigDecimal minValue;

    /** 最大值 */
    private BigDecimal maxValue;

    /** 样本数 */
    private Integer sampleCount;

    /** 运行次数 */
    private Integer runCount;
}
