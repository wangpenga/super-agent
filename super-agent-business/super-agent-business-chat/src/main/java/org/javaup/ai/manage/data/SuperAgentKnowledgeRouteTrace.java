package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.math.BigDecimal;

/**
 * 知识路由影子追踪表 (super_agent_knowledge_route_trace)
 * <p>
 * 记录每次 AUTO_DOCUMENT 和 DOCUMENT 模式下的知识路由决策过程。
 * 包括原始问题、改写问题、候选知识范围/主题/文档列表（JSON）、
 * 最终选中的文档、是否命中、置信度、路由状态和失败原因。
 * <p>
 * "影子"（shadow）模式的意义：即使在实际文档问答中不使用 AUTO_DOCUMENT 的路由结果，
 * 也会记录一次 shadow route trace，用于分析路由的准确性和改进空间。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_knowledge_route_trace")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeRouteTrace extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 会话id */
    private String conversationId;

    /** 轮次id */
    private Long exchangeId;

    /** 原始问题 */
    private String question;

    /** 改写问题 */
    private String rewriteQuestion;

    /**
     * 运行模式
     * shadow:影子追踪 / auto:自动路由
     */
    private String mode;

    /** 候选知识范围，JSON 格式 */
    private String topScopesJson;

    /** 候选主题，JSON 格式 */
    private String topTopicsJson;

    /** 候选文档，JSON 格式 */
    private String topDocumentsJson;

    /** 当前实际使用文档id */
    private Long selectedDocumentId;

    /** 候选是否命中实际文档：1是 0否 */
    private Integer hitSelectedDocument;

    /** 整体置信度 */
    private BigDecimal confidence;

    /**
     * 路由状态
     * 1:成功 2:低置信 3:失败
     */
    private Integer routeStatus;

    /** 失败原因 */
    private String errorMsg;
}
