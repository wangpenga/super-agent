package org.javaup.ai.manage.support;

/**
 * 文档结构解析过程中的"逻辑行"。
 * <p>
 * 原始文本的一行（\n 分隔）可能因为内联步骤（如 "第一步...第二步..."）被拆分为多条逻辑行，
 * 也可能因为空行被保留为一个逻辑行。
 * 逻辑行是 {@link DocumentStructureSignalExtractor} 逐行分类的最小单位。
 *
 * @param lineNo         逻辑行号（从 1 开始计数，等同信号中的 lineNo）
 * @param sourceLineNo   原始行号（parsedText 按 \n 切分后的行号，从 1 开始）
 * @param segmentIndex   段内序号（当一行被拆分为多个逻辑行时，1=第一段，2=第二段…；未拆分的行 =1）
 * @param indentLevel    缩进级别（连续空格数，制表符按 4 空格折算），用于列表嵌套深度判断
 * @param rawText        原始文本（未经标准化处理）
 * @param normalizedText 标准化后的文本（trim + 空白压缩），用于后续的模式匹配和消歧判断
 * @see DocumentStructureSignalExtractor#buildLogicalLines 逻辑行的构建逻辑
 */
public record DocumentStructureLogicalLine(
    int lineNo,
    int sourceLineNo,
    int segmentIndex,
    int indentLevel,
    String rawText,
    String normalizedText
) {
}
