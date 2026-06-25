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
 * 对话轮次执行阶段轨迹表 (super_agent_chat_exchange_trace_stage)
 * <p>
 * 记录每一轮对话内部的各个执行阶段的详细信息，包括阶段编码、名称、
 * 顺序、层级（一级阶段/二级子步骤）、执行状态、耗时和结构化快照。
 * <p>
 * 典型阶段：MEMORY（记忆装载）、REWRITE（问题改写）、ROUTE（路由决策）、
 * RAG_RETRIEVE（混合检索）、EVIDENCE_BUDGET（证据预算组装）、
 * ANSWER_GENERATE（答案生成）、REACT_AGENT（Agent 执行）、FINALIZE（收尾）。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_exchange_trace_stage")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatExchangeTraceStage extends BaseTableData {

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

    /** 阶段编码，如 MEMORY / REWRITE / ROUTE / RAG_RETRIEVE / ANSWER_GENERATE */
    @TableField("stage_code")
    private String stageCode;

    /** 阶段名称 */
    @TableField("stage_name")
    private String stageName;

    /** 阶段顺序 */
    @TableField("stage_order")
    private Integer stageOrder;

    /**
     * 阶段层级 (stage_level)
     * 1:一级阶段 2:二级子步骤
     */
    @TableField("stage_level")
    private Integer stageLevel;

    /** 父阶段id，用于构建阶段树 */
    @TableField("parent_stage_id")
    private Long parentStageId;

    /** 执行模式，如 RAG_CHAT / REACT_AGENT / CLARIFICATION */
    @TableField("execution_mode")
    private String executionMode;

    /**
     * 阶段状态 (stage_state)
     * 1:运行中 2:完成 3:失败 4:跳过
     */
    @TableField("stage_state")
    private Integer stageState;

    /** 阶段开始时间 */
    @TableField("start_time")
    private Date startTime;

    /** 阶段结束时间 */
    @TableField("end_time")
    private Date endTime;

    /** 阶段耗时，毫秒 */
    @TableField("duration_ms")
    private Long durationMs;

    /** 阶段摘要 */
    @TableField("summary_text")
    private String summaryText;

    /** 阶段错误信息 */
    @TableField("error_message")
    private String errorMessage;

    /** 阶段结构化快照，JSON 格式 */
    @TableField("snapshot_json")
    private String snapshotJson;
}
