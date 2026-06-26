package org.javaup.ai.chatagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一引用来源模型
 * <p>
 * 表示一条检索引用，可以来自三种渠道：
 * <ul>
 *   <li><b>WEB</b>：联网搜索结果（Tavily 等搜索引擎）</li>
 *   <li><b>DOCUMENT</b>：知识库文档的切块（chunk）</li>
 *   <li><b>PARENT</b>：知识库文档的父块（parent block）</li>
 * </ul>
 * <p>
 * {@link #uniqueKey()} 方法根据来源类型生成去重键，
 * 用于在最终展示给用户前对引用列表做去重。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
public class SearchReference {

    /** 引用唯一标识（展示用编号） */
    private String referenceId;

    /**
     * 来源类型
     * WEB: 联网搜索 / DOCUMENT: 文档切块 / PARENT: 文档父块
     */
    private String sourceType;

    /** 引用标题（文档章节标题 或 网页 title） */
    private String title;

    /** 引用 URL（WEB 来源的网页地址） */
    private String url;

    /** 内容摘要/片段（前 N 字符预览） */
    private String snippet;

    // ═══════════════ 文档来源字段（DOCUMENT / PARENT 类型时填充）═══════════════

    /** 文档 ID */
    private Long documentId;

    /** 文档名称 */
    private String documentName;

    /** 切块 ID（DOCUMENT 类型时） */
    private Long chunkId;

    /** 父块 ID（PARENT 类型时） */
    private Long parentBlockId;

    /** 父块序号 */
    private Integer parentBlockNo;

    /** 切块序号 */
    private Integer chunkNo;

    /** 章节路径（如 "第三章 > 3.1 > 系统架构"） */
    private String sectionPath;

    /** 关联的结构节点 ID */
    private Long structureNodeId;

    /** 关联的结构节点类型（1:根 2:章节 3:步骤 4:列表项） */
    private Integer structureNodeType;

    /** 结构节点稳定路径 */
    private String canonicalPath;

    /** 列表项/步骤项序号 */
    private Integer itemIndex;

    // ═══════════════ 分数与排名 ═══════════════

    /** 相关性分数（rerank 后的最终分数） */
    private Double score;

    // ═══════════════ 检索上下文 ═══════════════

    /** 来源子问题序号（从 1 开始，表示这个引用回答的是第几个子问题） */
    private Integer subQuestionIndex;

    /** 来源子问题文本 */
    private String subQuestion;

    // ═══════════════ 来源通道 ═══════════════

    /**
     * 检索通道名称
     * vector: 向量语义检索 / keyword: 关键词检索 / web-search: 联网搜索
     */
    private String channel;

    /** 产生该引用的工具名称（如 tavily_search） */
    private String toolName;

    // ═══════════════ 知识范围 ═══════════════

    /** 所属知识范围编码（如 oa / crm） */
    private String knowledgeScopeCode;

    /** 所属知识范围名称（如 OA系统 / CRM系统） */
    private String knowledgeScopeName;

    // ═══════════════ 构造方法 ═══════════════

    /**
     * WEB 来源的快捷构造
     *
     * @param title   网页标题
     * @param url     网页地址
     * @param snippet 内容摘要
     */
    public SearchReference(String title, String url, String snippet) {
        this.sourceType = "WEB";
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.channel = "web-search";
        this.toolName = "tavily_search";
    }

    /**
     * 去重键（用于 LinkedHashMap.putIfAbsent 去重）
     * <p>
     * 优先级：父块 ID > 切块 ID > URL > sourceType+title+snippet
     */
    public String uniqueKey() {
        if (parentBlockId != null) {
            return "PARENT:" + parentBlockId;
        }
        if (chunkId != null) {
            return "DOCUMENT:" + chunkId;
        }
        if (url != null && !url.isBlank()) {
            return "WEB:" + url;
        }
        return (sourceType == null ? "UNKNOWN" : sourceType)
            + ":" + (title == null ? "" : title)
            + ":" + (snippet == null ? "" : snippet);
    }
}
