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
 * 文档表 (super_agent_document)
 * <p>
 * 记录上传到知识库的所有文档的元信息，包括文件基本信息（名称、类型、大小）、
 * 存储信息（MinIO bucket/object）、解析状态、策略状态、索引状态、
 * 知识范围分类以及最近的解析/索引任务追踪。
 * <p>
 * 一个文档经过 上传 → 解析 → 策略推荐 → 索引构建 的完整生命周期，
 * 最终产生可用于 RAG 检索的结构节点、父块和切块数据。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocument extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档名称 */
    private String documentName;

    /** 原始文件名 */
    private String originalFileName;

    /**
     * 文件类型
     * 1:PDF 2:DOC 3:DOCX 4:TXT 5:MD 6:HTML
     */
    private Integer fileType;

    /** MIME 类型 */
    private String mimeType;

    /** 文件大小 (byte) */
    private Long fileSize;

    /**
     * 存储类型
     * 1:MinIO
     */
    private Integer storageType;

    /** Bucket 名称 */
    private String bucketName;

    /** 对象名称 */
    private String objectName;

    /** 文件访问地址 */
    private String objectUrl;

    /**
     * 解析状态
     * 1:待解析 2:解析中 3:解析成功 4:解析失败
     */
    private Integer parseStatus;

    /**
     * 策略状态
     * 1:待推荐 2:已推荐 3:已确认 4:已失效
     */
    private Integer strategyStatus;

    /**
     * 索引状态
     * 1:待构建 2:构建中 3:构建成功 4:构建失败
     */
    private Integer indexStatus;

    /** 解析后字符数 */
    private Integer charCount;

    /** 解析后 token 估算数 */
    private Integer tokenCount;

    /**
     * 结构化程度
     * 0:未知 1:低 2:中 3:高
     */
    private Integer structureLevel;

    /**
     * 内容质量
     * 0:未知 1:低 2:中 3:高
     */
    private Integer contentQualityLevel;

    /** 解析文本存储路径 */
    private String parseTextPath;

    /** 解析失败原因 */
    private String parseErrorMsg;

    /** 业务知识域编码，例如 oa / crm / finance */
    private String knowledgeScopeCode;

    /** 业务知识域名称，例如 OA系统 / CRM系统 */
    private String knowledgeScopeName;

    /** 业务分类，例如 流程 / 规则 / 操作手册 */
    private String businessCategory;

    /** 逗号分隔标签快照 */
    private String documentTags;

    /** 当前策略方案 id */
    private Long currentPlanId;

    /** 最近一次成功解析任务 id */
    private Long lastParseTaskId;

    /** 最近一次结构化解析生成的节点数 */
    private Integer structureNodeCount;

    /** 最近一次索引任务 id */
    private Long lastIndexTaskId;
}
