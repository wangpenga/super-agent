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
 * 文档结构节点表 (super_agent_document_structure_node)
 * <p>
 * 记录文档解析后的结构信息，以树形结构表示文档的章节层级关系。
 * 每个节点包含节点类型（根节点/章节/步骤/列表项）、父子关系、
 * 前后兄弟关系、深度、编码、标题、正文内容和锚文本。
 * <p>
 * 这些节点构成文档结构图（Structure Graph），用于：
 * <ul>
 *   <li>GRAPH_ONLY 模式：直接查询章节结构回答"有哪些章节"类问题</li>
 *   <li>GRAPH_THEN_EVIDENCE 模式：定位特定章节/编号项后校验证据</li>
 *   <li>RAG 检索：通过 structure_node_id 关联切块和父块，实现结构感知检索</li>
 * </ul>
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_structure_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStructureNode extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 解析任务id */
    private Long parseTaskId;

    /** 当前文档内的稳定顺序号 */
    private Integer nodeNo;

    /**
     * 节点类型
     * 1:文档根节点 2:章节节点 3:步骤节点 4:列表项节点
     */
    private Integer nodeType;

    /** 父节点id */
    private Long parentNodeId;

    /** 上一个同级节点id */
    private Long prevSiblingNodeId;

    /** 下一个同级节点id */
    private Long nextSiblingNodeId;

    /** 节点深度 */
    private Integer depth;

    /** 节点编码，例如 1.2 / 第一章 / 4 */
    private String nodeCode;

    /** 节点标题 */
    private String title;

    /** 供导航和锚点使用的短锚文本 */
    private String anchorText;

    /** 节点稳定路径 */
    private String canonicalPath;

    /** 兼容现有系统的章节路径文本 */
    private String sectionPath;

    /** 节点正文 */
    private String contentText;

    /** 列表项/步骤项序号 */
    private Integer itemIndex;
}
