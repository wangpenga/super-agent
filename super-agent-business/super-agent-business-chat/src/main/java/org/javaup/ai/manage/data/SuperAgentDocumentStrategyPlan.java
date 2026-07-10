package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.util.Date;

/**
 * 文档策略方案表 (super_agent_document_strategy_plan)
 * <p>
 * 记录每个文档的切块策略方案。系统会根据文档的结构化程度和内容质量自动推荐
 * 切块策略组合，用户可以调整后确认。每个方案包含方案版本、来源（系统推荐/用户调整）、
 * 状态、策略数量、策略快照以及确认人信息。
 * <p>
 * 一个文档可以有多个方案版本（通过 plan_version 区分），当前生效的方案记录在
 * SuperAgentDocument.currentPlanId 中。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_strategy_plan")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStrategyPlan extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 方案版本号 */
    private Integer planVersion;

    /**
     * 方案来源
     * 1:系统推荐 2:用户调整
     */
    private Integer planSource;

    /**
     * 方案状态
     * 1:待确认 2:已确认 3:已执行 4:已废弃
     */
    private Integer planStatus;

    /** 策略数量 */
    private Integer strategyCount;

    /** 策略快照，例:1,2,3 */
    private String strategySnapshot;

    /** 推荐原因 */
    private String recommendReason;

    /** 调整说明 */
    private String adjustNote;

    /** 确认人id */
    private Long confirmUserId;

    /** 确认时间 */
    private Date confirmTime;
}
