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
 * 文档任务日志表 (super_agent_document_task_log)
 * <p>
 * 记录文档处理任务在生命周期各阶段的详细日志，包括阶段类型、事件类型、
 * 日志级别、操作人信息和日志明细 JSON。
 * <p>
 * 用于追踪文档从上传到入库完成的完整处理过程，便于问题排查和审计。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_task_log")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTaskLog extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 任务id */
    private Long taskId;

    /** 文档id */
    private Long documentId;

    /**
     * 阶段类型
     * 1:文件上传 2:内容解析 3:策略路由 4:策略确认
     * 5:切块执行 6:切块后处理 7:向量化 8:入库完成
     */
    private Integer stageType;

    /**
     * 事件类型
     * 1:开始 2:完成 3:失败 4:推荐策略 5:用户调整 6:用户确认
     */
    private Integer eventType;

    /**
     * 日志级别
     * 1:INFO 2:WARN 3:ERROR
     */
    private Integer logLevel;

    /**
     * 操作人类型
     * 1:系统 2:用户 3:管理员
     */
    private Integer operatorType;

    /** 操作人id */
    private Long operatorId;

    /** 日志内容 */
    private String content;

    /** 日志明细 JSON */
    private String detailJson;
}
