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
 * 文档任务表 (super_agent_document_task)
 * <p>
 * 记录文档处理任务的执行信息，包括解析路由任务和索引构建任务。
 * 跟踪任务类型、状态、当前阶段、触发来源、重试次数、耗时和错误信息。
 * <p>
 * 文档处理的生命周期阶段：
 * 文件上传 → 内容解析 → 策略路由 → 策略确认 → 切块执行 → 切块后处理 → 向量化 → 入库完成
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_task")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTask extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 执行方案id */
    private Long planId;

    /**
     * 任务类型
     * 1:解析路由 2:构建索引
     */
    private Integer taskType;

    /**
     * 任务状态
     * 1:新建 2:进行中 3:成功 4:失败 5:已取消
     */
    private Integer taskStatus;

    /**
     * 当前阶段
     * 1:文件上传 2:内容解析 3:策略路由 4:策略确认
     * 5:切块执行 6:切块后处理 7:向量化 8:入库完成
     */
    private Integer currentStage;

    /**
     * 触发来源
     * 1:系统自动 2:用户手动
     */
    private Integer triggerSource;

    /** 执行时策略快照，例:1,2,3 */
    private String strategySnapshot;

    /** 重试次数 */
    private Integer retryCount;

    /** 开始时间 */
    private Date startTime;

    /** 结束时间 */
    private Date finishTime;

    /** 耗时毫秒 */
    private Long costMillis;

    /** 错误码 */
    private String errorCode;

    /** 错误信息 */
    private String errorMsg;

    /** 扩展信息 JSON */
    private String extJson;
}
