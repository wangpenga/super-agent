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
 * 文档切块表 (super_agent_document_chunk)
 * <p>
 * 记录文档经过切块策略处理后的子块（chunk）信息。每个 chunk 属于一个父块 (parent_block)，
 * 包含切块文本内容、字符数和 Token 数、向量化状态、以及向量库存储信息。
 * <p>
 * 切块是 RAG 检索的最小单元，每个 chunk 会被向量化后存入 PGVector/Milvus/ES 等向量库，
 * 用于语义相似度检索。结构节点信息（structure_node_id, section_path）用于关联文档结构图。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_chunk")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentChunk extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档id */
    private Long documentId;

    /** 索引任务id */
    private Long taskId;

    /** 策略方案id */
    private Long planId;

    /** 所属父块id */
    private Long parentBlockId;

    /** 块序号 */
    private Integer chunkNo;

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

    /** 切块内容 */
    private String chunkText;

    /** 字符数 */
    private Integer charCount;

    /** token 数 */
    private Integer tokenCount;

    /**
     * 向量状态
     * 1:待向量化 2:向量化中 3:向量化成功 4:向量化失败
     */
    private Integer vectorStatus;

    /**
     * 向量库类型
     * 1:Milvus 2:PGVector 3:Elasticsearch
     */
    private Integer vectorStoreType;

    /** 向量库主键 */
    private String vectorId;
}
