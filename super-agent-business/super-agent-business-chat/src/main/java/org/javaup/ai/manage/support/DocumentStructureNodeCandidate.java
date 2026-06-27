package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档结构节点候选 — 结构解析管线的最终输出产物。
 * <p>
 * 由 {@link DocumentStructureTreeValidator#validateAndBuild} 将
 * {@link DocumentStructureNodeDraft} 转换而来，是给下游切块阶段使用的不可变快照。
 * <p>
 * 切块时根据 sectionPath 和 depth 确定章节归属，根据 contentText 进行切分。
 * nodeCode 和 numericPath 用于辅助确定标题的编号层级关系。
 * <p>
 * {@link DocumentStructureNodeExtractor#extract} 返回的 List&lt;DocumentStructureNodeCandidate&gt;
 * 是排序后的深度优先遍历，第一个元素始终是根 DOCUMENT 节点。
 *
 * @see DocumentStructureNodeExtractor
 * @see DocumentStructureTreeValidator#toCandidate
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureNodeCandidate {

    /** 节点编号 — 树内的唯一标识，根节点固定为 1。 */
    private Integer nodeNo;

    /**
     * 节点类型枚举值。
     * <ul>
     *   <li>1 — DOCUMENT（根）</li>
     *   <li>2 — SECTION（章节）</li>
     *   <li>3 — STEP（步骤项）</li>
     *   <li>4 — LIST_ITEM（列表项）</li>
     * </ul>
     */
    private Integer nodeType;

    /** 父节点编号，根节点为 null。 */
    private Integer parentNodeNo;

    /** 前一个同级节点编号（按 lineNo 排序），没有则为 0。 */
    private Integer prevSiblingNodeNo;

    /** 后一个同级节点编号（按 lineNo 排序），没有则为 0。 */
    private Integer nextSiblingNodeNo;

    /** 树深度 — 根节点为 0，一级标题为 1，列表项相对于父节点 +1。 */
    private Integer depth;

    /** 节点编码 — 标题的数字/章节前缀，如 "1.2" / "第一章" / "一"。 */
    private String nodeCode;

    /** 标题文本（不含编码前缀）。 */
    private String title;

    /** 锚点文本 — 用于导航显示的完整标题，如 "1.2 数据校验"。 */
    private String anchorText;

    /**
     * 规范路径 — 从根到当前节点的 URL 风格路径。
     * 例如 "/document/第一章/第一节"。
     * 列表项路径："/document/第一章/item-1"。
     */
    private String canonicalPath;

    /**
     * 章节路径 — 从根到当前章节的标题路径。
     * 例如 "第一章 > 第一节"。
     * 非章节节点继承父节点的 sectionPath（不叠加自身）。
     */
    private String sectionPath;

    /**
     * 归属于该节点的正文内容（trim 后）。
     * <p>
     * 正文内容由直接子行组成，不包含子标题下的内容。
     * 例如：
     * <pre>
     * 1.1 背景           ← SECTION 节点，contentText 为空
     * 本项目旨在...      ← 归属于 1.1 的正文行
     * 主要目标包括...    ← 归属于 1.1 的正文行
     * 1.2 范围           ← 新 SECTION 节点
     * 项目覆盖...        ← 归属于 1.2 的正文行
     * </pre>
     */
    private String contentText;

    /** 列表项/步骤项的序号索引，非列表节点为 null。 */
    private Integer itemIndex;
}
