package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * 文档结构节点抽取器。
 * <p>
 * 接收清洗后的纯文本，经过"信号提取 → 歧义消解 → 层级构建 → 校验输出"
 * 四阶段流水线，还原文档的章节层级树（标题 → 子标题 → 段落边界）。
 * <p>
 * 管线说明：
 * <ol>
 *   <li><b>信号提取</b>（{@link DocumentStructureSignalExtractor}）—
 *       逐行扫描文本，识别可能为"章节标题"的行，输出原始信号列表。</li>
 *   <li><b>歧义消解</b>（{@link DocumentStructureAmbiguityResolver}）—
 *       对模糊信号做二次判断（例如：短句是标题还是正文首句？加粗行是标题还是强调？）。</li>
 *   <li><b>层级构建</b>（{@link DocumentStructureHierarchyResolver}）—
 *       根据消解后的信号，通过缩进/字号/编号模式推断父子层级关系，构建草稿树。</li>
 *   <li><b>校验输出</b>（{@link DocumentStructureTreeValidator}）—
 *       校验树结构合法性（无空节点、无循环引用、根唯一），生成最终节点列表。</li>
 * </ol>
 */
@AllArgsConstructor
@Component
public class DocumentStructureNodeExtractor {

    /** 信号抽取器 — 从原始文本行中提取结构信号（标题候选、列表项等）。 */
    private final DocumentStructureSignalExtractor signalExtractor;

    /** 歧义消解器 — 对不确定的信号做降噪和消歧处理。 */
    private final DocumentStructureAmbiguityResolver ambiguityResolver;

    /** 层级推断器 — 将扁平信号列表组装为带父子关系的章节树。 */
    private final DocumentStructureHierarchyResolver hierarchyResolver;

    /** 树结构校验器 — 校验并修复章节树，生成最终的候选节点列表。 */
    private final DocumentStructureTreeValidator treeValidator;

    /**
     * 从清洗后的纯文本中提取文档的章节结构树。
     * <p>
     * 这是整个结构抽取管线的入口，内部按固定顺序串联四个阶段：
     * <pre>
     *   信号提取 → 歧义消解 → 层级构建 → 校验输出
     * </pre>
     * 如果文本为空（例如解析失败或纯空白文档），仍然会返回一个单节点列表，
     * 包含一个根 DOCUMENT 节点，保证调用方不需要对空场景做特殊处理。
     *
     * @param documentTitle 文档标题（通常是文件名或无扩展名的主名），
     *                      用于填充根节点的标题字段。如果为空则兜底为"文档"。
     * @param parsedText    经 {@code TikaDocumentParserService} 清洗后的纯文本，
     *                      不可为空字符串，若为空则返回仅有根节点的列表。
     * @return 结构节点候选列表。
     *         列表的第一个元素始终是根 DOCUMENT 节点（depth=0）。
     *         子节点按深度优先顺序排列，每个节点携带其在原文中的行号区间，
     *         供后续切块阶段直接使用。
     */
    public List<DocumentStructureNodeCandidate> extract(String documentTitle, String parsedText) {

        // ── 第 1 步：入参规范化 ──────────────────────────────────────
        // 标题为空时使用"文档"作为兜底；文本去除首尾空白
        String normalizedTitle = StrUtil.blankToDefault(documentTitle, "文档").trim();
        String normalizedText = StrUtil.blankToDefault(parsedText, "").trim();

        // ── 第 2 步：空文本保护 ──────────────────────────────────────
        // 如果文本为空（解析失败或空白文档），仍返回一个包含根节点的列表。
        // 这样上游的切块流水线无需单独处理空场景，保证管线统一性。
        if (normalizedText.isBlank()) {
            return List.of(new DocumentStructureNodeCandidate(
                1,                                    // id=1，根节点固定 id
                DocumentStructureNodeTypeEnum.DOCUMENT.getCode(),  // 节点类型=文档根
                null,                                 // parentId=null，根节点无父节点
                0,                                    // depth=0，根节点层级
                0,                                    // startLine=0
                0,                                    // endLine=0
                "",                                   // identifier（无内容）
                normalizedTitle,                      // title=文档名/兜底标题
                normalizedTitle,                      // originalTitle=原始标题
                "/document",                          // canonicalPath=根路径
                "",                                   // numberingMark（无编号）
                "",                                   // styleKey（无样式）
                null                                  // rawSignal（无对应信号）
            ));
        }

        // ── 第 3 步：管线串联 ────────────────────────────────────────

        // 3-a) 信号提取：逐行扫描，识别章节标题候选，同时保留原始行上下文
        DocumentStructureSignalBatch signalBatch = signalExtractor.extract(
                normalizedTitle, normalizedText);

        // 从 batch 中拆出"信号列表"和"行上下文"；任一为空时使用空列表避免 NPE
        List<DocumentStructureSignal> rawSignals = signalBatch == null
                ? List.of()
                : signalBatch.signals();
        List<String> allLines = signalBatch == null
                ? List.of()
                : signalBatch.contextLines();

        // 3-b) 歧义消解：结合信号周围的行上下文，剔除误报、合并同类信号
        List<DocumentStructureSignal> resolvedSignals = ambiguityResolver.resolve(
                normalizedTitle, allLines, rawSignals);

        // 3-c) 层级构建：根据消解后的信号推断父子关系，构建层级树草稿
        List<DocumentStructureNodeDraft> drafts = hierarchyResolver.resolve(
                normalizedTitle, resolvedSignals);

        // 3-d) 校验输出：校验树结构合法性，补充必要字段，输出最终节点列表
        return treeValidator.validateAndBuild(normalizedTitle, drafts);
    }
}
