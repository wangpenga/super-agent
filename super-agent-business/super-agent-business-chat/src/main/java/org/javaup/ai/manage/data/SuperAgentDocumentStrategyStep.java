package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * 文档策略步骤表 (super_agent_document_strategy_step)
 * <p>
 * 记录策略方案中每个切块步骤的详细信息。每个步骤属于一个方案 (plan)，
 * 具有执行顺序 (step_no) 和流水线类型（父块流水线/子块流水线）。
 * <p>
 * 步骤按策略类型（基于文档结构切块/递归分块/语义分块/大模型智能切块）和
 * 策略角色（主策略/优化策略/兜底策略/增强策略）组织执行，最终产出文档的父块和切块。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_strategy_step")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStrategyStep extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 方案id */
    private Long planId;

    /** 文档id */
    private Long documentId;

    /** 执行顺序 */
    private Integer stepNo;

    /**
     * 流水线类型
     * PARENT:父块流水线 CHILD:子块流水线
     */
    private String pipelineType;

    /**
     * 策略类型
     * 1:基于文档结构切块 2:递归分块 3:语义分块 4:大模型智能切块
     */
    private Integer strategyType;

    /**
     * 策略角色
     * 1:主策略 2:优化策略 3:兜底策略 4:增强策略
     */
    private Integer strategyRole;

    /**
     * 来源类型
     * 1:系统推荐 2:用户新增 3:用户保留
     */
    private Integer sourceType;

    /**
     * 执行状态
     * 1:待执行 2:执行中 3:执行成功 4:执行失败 5:已跳过
     */
    private Integer executeStatus;

    /** 本步骤推荐原因 */
    private String recommendReason;
}
