package org.javaup.ai.manage.support;

import java.util.List;

/**
 * {@link DocumentStructureSignalExtractor#extract} 的返回结果。
 * <p>
 * 同时携带两种数据：
 * <ul>
 *   <li><b>contextLines</b> — 清洗后的行文本列表（下标从 0 开始），
 *       供后续 {@link DocumentStructureAmbiguityResolver} 构造上下文窗口使用。</li>
 *   <li><b>signals</b> — 逐行分类后的结构信号列表（含 DOCUMENT_TITLE 占位信号和各行信号）。</li>
 * </ul>
 * <p>
 * 两者通过 lineNo 关联：signals[i].lineNo - 1 = contextLines 的下标。
 * signals 中的 DOCUMENT_TITLE 信号 lineNo=0，不对应任何 contextLines 行。
 *
 * @param contextLines 全文行列表（每行一个元素，空白行保留），下标从 0 开始
 * @param signals      按行号升序排列的信号列表，包含 lineNo=0 的文档标题信号
 */
public record DocumentStructureSignalBatch(
    List<String> contextLines,
    List<DocumentStructureSignal> signals
) {
}
