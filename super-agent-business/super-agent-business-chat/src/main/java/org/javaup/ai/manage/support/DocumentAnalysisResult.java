package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析结果 — {@link TikaDocumentParserService#parse} 的统一输出。
 * <p>
 * 由 Tika 对原始文件做格式解析和文本提取后生成，包含清洗后的纯文本、
 * 基础统计（字符数、token 数）、结构评估（标题数、段落数、结构等级）、
 * 质量评估（乱码检测、内容等级）以及完整的章节树结构。
 * <p>
 * 下游消费者：
 * <ul>
 *   <li>{@link org.javaup.ai.manage.service.DocumentStrategyService#recommendStrategy} —
 *       根据结构等级和内容质量推荐切块策略</li>
 *   <li>{@link org.javaup.ai.manage.service.DocumentStructureNodeService#replaceDocumentNodes} —
 *       将章节树持久化到 MySQL</li>
 *   <li>{@link org.javaup.ai.manage.service.DocumentProfileService#generateProfile} —
 *       生成文档摘要、标签等画像数据</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysisResult {

    /**
     * 清洗后的纯文本全文。
     * <p>
     * 经过 {@link TikaDocumentParserService#cleanupText} 处理：
     * <ul>
     *   <li>统一换行符（\r\n → \n, \r → \n）</li>
     *   <li>移除空字符和零宽字符</li>
     *   <li>制表符/垂直制表符/换页符 → 空格</li>
     *   <li>连续 3+ 换行 → 保留 2 个（段落分界）</li>
     *   <li>连续 2+ 空格 → 保留 1 个</li>
     *   <li>首尾空白修剪</li>
     * </ul>
     * 下游切块阶段直接使用此文本进行切分。
     */
    private String parsedText;

    /**
     * 清洗后文本的总字符数。
     * <p>
     * 用于：
     * <ul>
     *   <li>{@link org.javaup.ai.manage.service.impl.DocumentStrategyServiceImpl#shouldUseRecursive} —
     *       判断是否需要用递归切块（超长文本）</li>
     *   <li>{@link org.javaup.ai.manage.service.impl.DocumentStrategyServiceImpl#shouldUseSemantic} —
     *       判断是否有足够长度做语义切块</li>
     * </ul>
     */
    private Integer charCount;

    /**
     * 估算的 token 数（中英混合近似公式）。
     * <p>
     * 估算公式：中文字符数 + 英文单词数 + (非中文字符数 / 4)。
     * 用于切块阶段的 token 预算控制，非精确值（不同模型 tokenizer 结果不同）。
     *
     * @see TikaDocumentParserService#estimateTokenCount
     */
    private Integer tokenCount;

    /**
     * 文档结构等级 — 反映文档的章节标题组织完整程度。
     * <p>
     * 取值范围（{@link org.javaup.enums.DocumentStructureLevelEnum}）：
     * <ul>
     *   <li>3 = HIGH（标题 ≥ 5）— 结构完整，适合精确分块</li>
     *   <li>2 = MEDIUM（标题 2-4）— 有一定结构，可以辅助分块</li>
     *   <li>1 = LOW（标题 < 2 且段落 ≥ 3）— 基本无结构</li>
     *   <li>0 = UNKNOWN（段落 < 3）— 内容过短无法判断</li>
     * </ul>
     * 直接影响 {@link DocumentStrategyServiceImpl#shouldUseStructure} 的决策：
     * HIGH/MEDIUM 且文件类型为富文本格式时推荐使用结构切块。
     * <p>
     * === 示例场景 ===
     * <pre>
     * HIGH（结构完整）：
     *   "1.1 背景\n内容...\n1.2 目标\n内容...\n1.3 方法\n内容...\n1.4 结果\n内容...\n1.5 结论\n内容..."
     *   → 标题 5 个，章节层次清晰，适合按标题边界做结构切块。
     *
     *   "# 概述\n## 背景\n内容...\n## 目标\n内容...\n### 近期目标\n### 远期目标\n## 方案\n内容..."
     *   → Markdown 文档，多级嵌套标题，结构丰富，适合结构感知分块。
     *
     * MEDIUM（有一定结构）：
     *   "一、前言\n内容...\n二、方案概述\n内容...\n三、实施计划\n内容..."
     *   → 3 个中文提纲标题，没有子标题，可以辅助分块但精度有限。
     *
     *   "第一章 总则\n内容...\n第二章 组织结构\n内容..."
     *   → 只有 2 个标题的短文档，结构简单但可识别。
     *
     * LOW（基本无结构）：
     *   "本项目旨在提升效率。主要方法包括自动化流程改造、数据治理和人员培训。
     *    项目周期为六个月，分为三个阶段实施。第一阶段为基础建设..."
     *   → 纯段落文本，无任何标题标记，只能按长度/语义切分。
     *
     *   "- 需求分析\n- 系统设计\n- 编码实现\n- 测试验收"
     *   → 列表式文档，无章节标题，结构等级低。
     *
     * UNKNOWN（内容过短）：
     *   "Hello World"
     *   → 不到 3 个段落，无法判断是否有结构。
     * </pre>
     */
    private Integer structureLevel;

    /**
     * 内容质量等级。
     * <p>
     * 取值范围（{@link org.javaup.enums.DocumentContentQualityLevelEnum}）：
     * <ul>
     *   <li>3 = HIGH（乱码率 ≤ 0.5% 且长度 ≥ 500）— 文本干净、长度充分</li>
     *   <li>2 = MEDIUM（乱码率 ≤ 2% 且长度 ≥ 100）— 局部乱码或内容不完整</li>
     *   <li>1 = LOW（乱码率 > 2% 或长度 < 100）— 很大概率是乱码或提取失败</li>
     * </ul>
     * 基于 Unicode 替换字符（U+FFFD "�"）的比例和文本长度综合判定。
     * 低质量文档会触发 LLM 智能切块推荐（{@link DocumentStrategyServiceImpl#shouldUseLlm}）。
     *
     * @see TikaDocumentParserService#evaluateContentQuality
     */
    private Integer contentQualityLevel;

    /**
     * 标题数量 — 清洗后文本中检测到的章节标题总数。
     * <p>
     * 统计方式：
     * <ol>
     *   <li>优先使用 {@link DocumentStructureNodeExtractor} 已识别的结构化标题节点</li>
     *   <li>结构化结果为空时退回到 {@link DocumentLineClassifier} 按行逐条分类</li>
     * </ol>
     * 用于 {@link DocumentStrategyServiceImpl#shouldUseStructure} 的辅助判断：
     * 标题 ≥ 2 时说明文档有一定结构，适合结构切块。
     *
     * @see TikaDocumentParserService#countHeadings
     */
    private Integer headingCount;

    /**
     * 段落数量 — 清洗后文本按双换行（空行）切分后的段落总数。
     * <p>
     * 空段落（纯空白）不计入。
     * 用于：
     * <ul>
     *   <li>{@link org.javaup.enums.DocumentStructureLevelEnum} 的等级评定</li>
     *   <li>{@link DocumentStrategyServiceImpl#shouldUseSemantic} 的判断（段落 ≥ 3 才有语义切分意义）</li>
     * </ul>
     *
     * @see TikaDocumentParserService#extractParagraphs
     */
    private Integer paragraphCount;

    /**
     * 最长段落的字符数 — 所有段落中长度最大的值。
     * <p>
     * 用于 {@link DocumentStrategyServiceImpl#shouldUseRecursive} 的判断：
     * 如果存在超长段落（超过 recursiveMaxChars），必须使用递归切块来切分它。
     * 0 表示文本为空或没有段落。
     *
     * @see TikaDocumentParserService#extractParagraphs
     */
    private Integer maxParagraphLength;

    /**
     * 章节树节点列表 — 从文本中还原的文档章节层级结构。
     * <p>
     * 由四阶段流水线生成：
     * <ol>
     *   <li>{@link DocumentStructureSignalExtractor} — 逐行信号提取</li>
     *   <li>{@link DocumentStructureAmbiguityResolver} — LLM 歧义消解</li>
     *   <li>{@link DocumentStructureHierarchyResolver} — 层级树构建</li>
     *   <li>{@link DocumentStructureTreeValidator} — 校验输出</li>
     * </ol>
     * 列表按深度优先顺序排列，第一个元素始终是根 DOCUMENT 节点。
     * 每个节点携带章节路径、规范路径、正文内容、行号范围等信息，
     * 供下游 {@link DocumentStrategyServiceImpl#buildParentBlocks} 按章节边界切块使用。
     *
     * @see DocumentStructureNodeExtractor#extract
     */
    private List<DocumentStructureNodeCandidate> structureNodes = new ArrayList<>();
}
