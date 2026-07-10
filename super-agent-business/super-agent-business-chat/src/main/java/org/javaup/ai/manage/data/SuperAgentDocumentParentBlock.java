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
 * 文档父块表 (super_agent_document_parent_block)
 * <p>
 * 记录文档切块后的父块（parent block）信息。父块是切块的上层聚合单元，
 * 一个父块包含多个子块（chunk）。每个父块有完整的原文内容 (parent_text)
 * 和子块范围映射 (start_chunk_no ~ end_chunk_no)。
 * <p>
 * 在 RAG 检索中，当子块被匹配时，可以通过父块获取更完整的上下文（父子块提升策略）。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_parent_block")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentParentBlock extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 索引任务id */
    private Long taskId;

    /** 策略方案id */
    private Long planId;

    /** 父块序号 */
    private Integer parentNo;

    /**
     * 内容来源
     * 1:原文切块 2:后处理补全文本
     */
    private Integer sourceType;

    /** 章节路径 */
    private String sectionPath;

    /** 关联的结构节点id */
    private Long structureNodeId;

    /** 关联的结构节点类型 */
    private Integer structureNodeType;

    /** 结构节点稳定路径 */
    private String canonicalPath;

    /** 列表项/步骤项序号 */
    private Integer itemIndex;

    /** 父块完整内容 */
    private String parentText;

    /** 字符数 */
    private Integer charCount;

    /** token 数 */
    private Integer tokenCount;

    /** 父块内部 child 数量 */
    private Integer childCount;

    /** 父块映射到的第一个 child 序号 */
    private Integer startChunkNo;

    /** 父块映射到的最后一个 child 序号 */
    private Integer endChunkNo;
}
