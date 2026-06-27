package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档结构信号 — 文本中某一行经 {@link DocumentStructureSignalExtractor#classify} 分类后的结果。
 * <p>
 * 信号是整个结构解析流水线的核心中间产物，携带了该行的分类、标题文本、置信度等信息。
 * 信号可以在 {@link DocumentStructureAmbiguityResolver} 中被修改（HEADING_CANDIDATE → HEADING/BODY），
 * 最终在 {@link DocumentStructureHierarchyResolver} 中被映射为树节点。
 *
 * @see DocumentStructureSignalExtractor
 * @see DocumentStructureAmbiguityResolver
 * @see DocumentStructureHierarchyResolver
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureSignal {

    /** 逻辑行号，与 {@link DocumentStructureLogicalLine#lineNo} 一致。DOCUMENT_TITLE 信号固定为 0。 */
    private int lineNo;

    /** 该行的原始文本（未标准化）。 */
    private String rawText;

    /** 标准化后的文本（trim、空白压缩）。 */
    private String normalizedText;

    /** 信号类型 — 决定了该行在层级构建阶段被如何处理。 */
    private DocumentStructureSignalKind kind;

    /**
     * 节点编码。
     * <ul>
     *   <li>Markdown 标题 → "#" / "##"</li>
     *   <li>数字编号标题 → "1.2.3" / "1"</li>
     *   <li>"第X章" → "第一章"</li>
     *   <li>"附录" → "附录A"</li>
     *   <li>列表项 → 序号字符串（如 "1" / "一"）</li>
     *   <li>其他 → 空字符串</li>
     * </ul>
     */
    private String nodeCode;

    /**
     * 标题文本（去除编码前缀后的纯标题）。
     * 例如 "第一章 总则" → nodeCode="第一章", title="总则"。
     * 无编码时 title 等于 normalizedText。
     */
    private String title;

    /**
     * 层级提示 — 用于辅助判断该标题在树中的深度。
     * <ul>
     *   <li>Markdown "#" → 1, "##" → 2</li>
     *   <li>"1.2.3" → 3（句点数 + 1）</li>
     *   <li>"第X章" → 1</li>
     *   <li>Plain Heading 候选 → 根据上下文推断 1 或 2</li>
     *   <li>非标题 → null</li>
     * </ul>
     */
    private Integer levelHint;

    /** 缩进级别（空格数），仅对列表项有意义，用于嵌套深度推断。 */
    private Integer indentLevel;

    /**
     * 列表项的序号索引。
     * <ul>
     *   <li>阿拉伯数字 "1." → 1</li>
     *   <li>中文数字 "一、" → 1</li>
     *   <li>"第 1 步" → 1</li>
     *   <li>其他 → null</li>
     * </ul>
     */
    private Integer itemIndex;

    /**
     * 数字路径 — 用于数字编号标题的精确层级定位。
     * <p>
     * 例如标题 "1.2.3 数据校验" → numericPath=[1, 2, 3]。
     * 在 {@link DocumentStructureHierarchyResolver#resolveHeadingDepth} 中，
     * 通过 numericPath 可以找到精确的父节点（1.2），而不是简单按 depth 就近匹配。
     */
    @Builder.Default
    private List<Integer> numericPath = new ArrayList<>();

    /**
     * 分类原因标签列表 — 记录了该信号为什么被分为当前类型。
     * <p>
     * 常见值："markdown-heading", "decimal-heading", "chapter-heading",
     * "appendix-heading", "single-digit-ambiguous-heading", "chinese-outline-ambiguous-heading",
     * "explicit-step", "bullet-list", "checkbox-list", "table-row", "page-noise",
     * "duplicate-document-title", "body", "llm-disambiguated" 等。
     * <p>
     * 这些标签在 {@link DocumentStructureHierarchyResolver#resolveHeadingFamily} 中
     * 用于确定标题家族的归属（markdown / decimal / chapter / plain 等），
     * 不同家族有各自独立的层级推断策略。
     */
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    /** 置信度 [0, 1]，值越大分类越可靠。确定性匹配（Markdown/第X章）接近 1.0，Plain 候选约 0.58。 */
    private double confidence;

    /** 是否为标题类信号（确定性标题或标题候选），供歧义消解和层级构建快速判断。 */
    public boolean isHeadingLike() {
        return kind == DocumentStructureSignalKind.HEADING
            || kind == DocumentStructureSignalKind.HEADING_CANDIDATE;
    }

    /** 是否为列表类信号（步骤项或列表项），供层级构建分配节点类型时判断。 */
    public boolean isListLike() {
        return kind == DocumentStructureSignalKind.STEP_ITEM
            || kind == DocumentStructureSignalKind.LIST_ITEM;
    }

    /**
     * 是否为"歧义信号" — 仅当类型为 HEADING_CANDIDATE 时返回 true。
     * 这类信号需要进入 {@link DocumentStructureAmbiguityResolver} 做 LLM 消歧。
     */
    public boolean isAmbiguous() {
        return kind == DocumentStructureSignalKind.HEADING_CANDIDATE;
    }
}
