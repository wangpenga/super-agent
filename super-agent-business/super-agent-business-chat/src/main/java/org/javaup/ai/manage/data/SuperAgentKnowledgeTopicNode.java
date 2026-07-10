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
 * 知识主题节点表 (super_agent_knowledge_topic_node)
 * <p>
 * 知识主题（Knowledge Topic）是知识范围下的更细粒度分类，用于精确匹配用户问题。
 * 每个主题属于一个知识范围，包含主题编码、名称、描述、别名和典型问题示例。
 * <p>
 * 主题还包含执行偏好配置：
 * <ul>
 *   <li>answer_shape：建议回答形态（explain/list/steps/compare/structure）</li>
 *   <li>execution_preference：执行偏好（retrieval/graph_only/graph_then_evidence/graph_assist）</li>
 * </ul>
 * 这些配置帮助路由决策层选择最优的执行路径。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_knowledge_topic_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeTopicNode extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 主题编码 */
    private String topicCode;

    /** 主题名称 */
    private String topicName;

    /** 所属知识范围编码 */
    private String scopeCode;

    /** 主题描述 */
    private String description;

    /** 别名，英文逗号分隔 */
    private String aliases;

    /** 典型问题，JSON 数组 */
    private String examples;

    /**
     * 建议回答形态
     * explain / list / steps / compare / structure
     */
    private String answerShape;

    /**
     * 执行偏好
     * retrieval / graph_only / graph_then_evidence / graph_assist
     */
    private String executionPreference;

    /** 排序值 */
    private Integer sortOrder;
}
