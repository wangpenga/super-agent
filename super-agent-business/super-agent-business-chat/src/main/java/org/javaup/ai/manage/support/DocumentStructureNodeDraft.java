package org.javaup.ai.manage.support;

import lombok.Data;
import org.javaup.enums.DocumentStructureNodeTypeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档结构节点草稿 — 树构建过程中的中间产物。
 * <p>
 * 由 {@link DocumentStructureHierarchyResolver} 根据信号创建，
 * 经 {@link DocumentStructureTreeValidator} 校验修复后，
 * 转换为最终的 {@link DocumentStructureNodeCandidate}。
 * <p>
 * 与最终候选的区别：草稿允许临时修改（如修复父节点、重算深度），
 * 并且通过 content 累加器收集下级正文行。
 *
 * @see DocumentStructureHierarchyResolver#resolve
 * @see DocumentStructureTreeValidator#validateAndBuild
 */
@Data
public class DocumentStructureNodeDraft {

    /** 节点编号 — 树内的唯一标识，根节点固定为 1。 */
    private Integer nodeNo;

    /** 信号行号（对应原文中的逻辑行号），用于排序和回溯。 */
    private Integer lineNo;

    /**
     * 节点类型枚举值。
     * <ul>
     *   <li>{@link DocumentStructureNodeTypeEnum#DOCUMENT} — 根节点</li>
     *   <li>{@link DocumentStructureNodeTypeEnum#SECTION} — 章节节点</li>
     *   <li>{@link DocumentStructureNodeTypeEnum#STEP} — 步骤节点</li>
     *   <li>{@link DocumentStructureNodeTypeEnum#LIST_ITEM} — 列表项节点</li>
     * </ul>
     */
    private Integer nodeType;

    /** 父节点编号。根节点的 parentNodeNo=null。 */
    private Integer parentNodeNo;

    /** 前一个同级节点的编号，没有则为 0（由 rebuildSiblingLinks 填充）。 */
    private Integer prevSiblingNodeNo;

    /** 后一个同级节点的编号，没有则为 0（由 rebuildSiblingLinks 填充）。 */
    private Integer nextSiblingNodeNo;

    /** 树深度 — 根节点为 0，一级标题为 1，以此类推（由 recomputeDepths 重算）。 */
    private Integer depth;

    /** 节点编码 — 标题的数字/章节编号，由信号中的 nodeCode 继承。 */
    private String nodeCode;

    /** 标题文本（不含编码前缀）。 */
    private String title;

    /**
     * 锚点文本 — 用于导航显示的完整标题。
     * 通常 = "编码 标题"，如 "1.2 数据校验"。
     */
    private String anchorText;

    /**
     * 规范路径 — 从根到当前节点的 URL 风格路径，用于唯一标识。
     * 例如 "/document/第一章/第一节"。
     * 由 {@link DocumentStructureTreeValidator#rebuildPaths} 构建。
     */
    private String canonicalPath;

    /**
     * 章节路径 — 从根到当前章节的标题路径，用于用户可读的上下文。
     * 例如 "第一章 > 第一节"。
     * 非章节节点继承父节点的 sectionPath。
     */
    private String sectionPath;

    /** 列表项/步骤项的序号索引，从信号的 itemIndex 继承。 */
    private Integer itemIndex;

    /**
     * 文本内容累加器 — 收集归属于该节点的所有正文行。
     * 层级构建阶段，正文行会被追加到最近的标题节点和/或列表项节点。
     */
    @Data
    private static final class ContentHolder {
        private final StringBuilder builder = new StringBuilder();
    }

    private final ContentHolder content = new ContentHolder();

    /** 数字路径 — 从信号的 numericPath 继承，用于精确层级定位。 */
    private List<Integer> numericPath = new ArrayList<>();

    /**
     * 来源家族标识 — 用于调试和后续处理。
     * <ul>
     *   <li>标题节点: "markdown" / "decimal" / "chapter" / "appendix" / "plain"</li>
     *   <li>列表节点: "list" / "step"</li>
     *   <li>根节点: "document"</li>
     * </ul>
     */
    private String sourceFamily;

    /** 置信度 — 从信号的 confidence 继承，表示该节点存在的确定性程度。 */
    private double confidence;

    /**
     * 追加一行文本到该节点的内容区。
     * <p>
     * 空行和纯空白行被忽略。非空行间用 \n 分隔。
     * 最终在 {@link #contentText()} 中 trim 后输出。
     *
     * @param line 要追加的文本行
     */
    public void appendLine(String line) {
        String normalized = line == null ? "" : line.trim();
        if (normalized.isBlank()) {
            return;
        }
        if (!content.builder.isEmpty()) {
            content.builder.append('\n');
        }
        content.builder.append(normalized);
    }

    /** 返回该节点收集的所有正文内容（首尾去空白）。 */
    public String contentText() {
        return content.builder.toString().trim();
    }

    /**
     * 判断当前节点是否为章节节点。
     * 章节节点具有标题、深度等属性，可以有子节点。
     */
    public boolean isSection() {
        return DocumentStructureNodeTypeEnum.SECTION.getCode().equals(nodeType);
    }

    /**
     * 判断当前节点是否为列表类节点（步骤项或列表项）。
     * 列表类节点是叶子节点，不会有子章节。
     */
    public boolean isListLike() {
        return DocumentStructureNodeTypeEnum.STEP.getCode().equals(nodeType)
            || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(nodeType);
    }
}
