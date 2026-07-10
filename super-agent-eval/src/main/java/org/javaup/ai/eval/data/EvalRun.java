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
 * 评估运行记录 —— 一次完整的离线评估执行
 * <p>
 * 每次运行会快照当前配置、跑完所有测试集条目、汇总平均指标。
 * 通过比较多次运行的结果（A/B 测试）来指导参数优化。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_eval_run")
@EqualsAndHashCode(callSuper = true)
public class EvalRun extends BaseEvalEntity {

    /** 主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 运行名称，如 "2026-07-10-baseline", "2026-07-11-variant-a" */
    private String runName;

    /** 运行类型：manual / scheduled / ab_test */
    private String runType;

    /** 运行时的完整配置快照（JSON），用于回溯和 A/B 对比 */
    private String configSnapshot;

    /** 测试集规模 */
    private Integer datasetSize;

    /** 平均 Context Precision */
    private BigDecimal avgContextPrecision;

    /** 平均 Context Recall */
    private BigDecimal avgContextRecall;

    /** 平均 Faithfulness */
    private BigDecimal avgFaithfulness;

    /** 平均 Answer Relevancy */
    private BigDecimal avgAnswerRelevancy;

    /** 平均检索耗时（毫秒） */
    private Long avgLatencyMs;

    /** 运行状态：1=pending 2=running 3=completed 4=failed */
    private Integer runStatus;

    /** 开始时间 */
    private Date startedAt;

    /** 完成时间 */
    private Date completedAt;

    /** 错误信息 */
    private String errorMessage;
}
