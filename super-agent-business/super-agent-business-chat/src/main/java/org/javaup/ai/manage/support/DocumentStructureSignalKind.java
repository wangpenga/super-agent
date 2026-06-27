package org.javaup.ai.manage.support;

/**
 * 文档结构信号的类型枚举。
 * <p>
 * 每一种类型代表 {@link DocumentStructureSignalExtractor} 对某一行文本的初步分类。
 * 信号类型决定了后续在 {@link DocumentStructureHierarchyResolver} 中
 * 该行会被处理为标题节点、列表节点、正文内容还是被丢弃。
 * <p>
 * 分类优先级：结构化匹配（Markdown/数字/中文提纲）> 语义猜测（Plain Heading 候选）> 兜底正文。
 *
 * @see DocumentStructureSignalExtractor#classify 信号分类的完整逻辑
 * @see DocumentStructureHierarchyResolver#resolve 信号到树节点的映射
 */
public enum DocumentStructureSignalKind {

    /** 文档标题信号 — 来自文件名或上传时指定的标题，固定在 lineNo=0。 */
    DOCUMENT_TITLE,

    /** 确定性标题 — 能通过正则明确匹配（Markdown # / "第X章" / "一、" / "1.2.3"），置信度高。 */
    HEADING,

    /**
     * 标题候选 — 无法通过正则明确匹配，但 "看起来像" 标题的文本行。
     * 这类信号需要进入 {@link DocumentStructureAmbiguityResolver} 做二次判断，
     * 如果 ambiguty 消解不开启或不可用，则降级为 BODY。
     */
    HEADING_CANDIDATE,

    /** "第N步" / "步骤N" 类显式步骤项，会生成 STEP 类型的结构节点。 */
    STEP_ITEM,

    /** 列表项 — 数字编号列表（"1." / "2."）、中文编号（"一、" / "二、"）、无序列表（"-" / "*" / "•"）、复选框。 */
    LIST_ITEM,

    /** 表格行 — 以 "|" 开头结尾或包含制表符的行，原样保留不进一步解析。 */
    TABLE_ROW,

    /** 引用块 — 以 ">" 开头的行。 */
    QUOTE,

    /** 正文 — 非标题、非列表、非特殊格式的普通文本行，归属到最近的标题节点或根节点。 */
    BODY,

    /** 空行 — 仅空白字符的行，用于触发列表栈清除和上下文边界判断。 */
    BLANK,

    /** 噪声 — 页眉页脚、重复题名、版权声明、页码、版本脚注等，在构建阶段会被忽略。 */
    NOISE
}
