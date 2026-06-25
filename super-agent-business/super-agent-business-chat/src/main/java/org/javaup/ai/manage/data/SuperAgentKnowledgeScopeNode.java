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
 * 知识范围节点表 (super_agent_knowledge_scope_node)
 * <p>
 * 知识范围（Knowledge Scope）是知识库的组织维度，用于对文档进行分组管理。
 * 每个范围有唯一的编码和名称，支持树形层级结构（通过 parent_scope_code）。
 * 范围会关联别名、描述和典型问题示例，用于 AUTO_DOCUMENT 模式下的知识路由匹配。
 * <p>
 * 典型的知识范围：OA系统 / CRM系统 / 财务系统 / 技术文档 等。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_knowledge_scope_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeScopeNode extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 知识范围编码 */
    private String scopeCode;

    /** 知识范围名称 */
    private String scopeName;

    /** 父级知识范围编码 */
    private String parentScopeCode;

    /** 范围描述 */
    private String description;

    /** 别名，英文逗号分隔 */
    private String aliases;

    /** 典型问题，JSON 数组 */
    private String examples;

    /** 排序值 */
    private Integer sortOrder;
}
