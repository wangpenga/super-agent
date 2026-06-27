package org.javaup.ai.manage.support;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.util.StrUtil;                          // Hutool 字符串工具（isBlank / blankToDefault）
import org.javaup.ai.manage.config.DocumentManageProperties;  // 应用配置：结构解析相关阈值
import org.springframework.stereotype.Component;              // Spring 组件注解

import java.util.ArrayList;          // 动态数组
import java.util.LinkedHashMap;      // 有序哈希表（保持插入顺序）
import java.util.List;               // 列表接口
import java.util.Locale;             // 地区/语言设置（用于 toLowerCase）
import java.util.Map;                // 映射接口
import java.util.regex.Matcher;      // 正则匹配器
import java.util.regex.Pattern;      // 正则编译模式

/**
 * 文档结构信号提取器 — 管线第一站。
 * <p>
 * 职责：接收清洗后的纯文本，经过"逻辑行构建 → 行频统计 → 逐行分类"三步，
 * 将文本行转换为带类型标签的结构信号列表，供后续消歧和层级构建使用。
 * <p>
 * === 工作流程 ===
 * <ol>
 *   <li><b>逻辑行构建</b>（{@link #buildLogicalLines}）：将原始文本按 \n 拆分为行，
 *       再对每行按内联步骤边界做二次拆分，生成逻辑行列表。</li>
 *   <li><b>行频统计</b>（{@link #buildLineFrequency}）：统计每条标准化文本出现的次数，
 *       用于识别重复噪声（页眉页脚）。</li>
 *   <li><b>逐行分类</b>（{@link #classify}）：对每个逻辑行，按正则匹配链逐级判断类型，
 *       匹配规则优先级：Markdown 标题 > 显式步骤 > 第X章 > 附录 > 数字多级 > 表格 > 引用 > 复选框 > 无序列表 >
 *       单级数字（判定为标题候选或列表项）> 中文提纲（判定为标题候选或列表项）> 行分类器 > 兜底正文。</li>
 * </ol>
 *
 * === 设计要点 ===
 * <ul>
 *   <li>确定性匹配（Markdown/"第X章"）置信度 0.92-0.98，直接定为 HEADING。</li>
 *   <li>模糊匹配（单级数字/中文提纲/Plain 文本）置信度 0.58-0.62，定为 HEADING_CANDIDATE，
 *       留给 LLM 消歧做最终判断。</li>
 *   <li>连续数字列表（1/2/3）且未以冒号结尾的上一行引导 → 判为 LIST_ITEM。</li>
 *   <li>重复行（≥2 次）且符合版权/版本/分隔符特征 → 判为 NOISE。</li>
 * </ul>
 *
 * @see DocumentStructureAmbiguityResolver 第二站：对 HEADING_CANDIDATE 做 LLM 消歧
 * @see DocumentStructureHierarchyResolver 第三站：将信号组装为层级树
 */
@Component  // 声明为 Spring 组件，由包扫描自动注册
public class DocumentStructureSignalExtractor {

    // ═══════════════════════════════════════════════════════════
    //  正则匹配模式（按分类优先级排列）
    //  每个 Pattern 对应一条分类规则，按编译后复用提高性能
    // ═══════════════════════════════════════════════════════════

    /** Markdown 标题：^#{1,6}\s+ 任意文本 — 匹配 "# 标题" 到 "###### 标题" */
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    /** 多级数字编号：^(\d+(?:\.\d+)+)\s*[、.]?\s*标题 — 匹配 "1.2.3 标题" */
    private static final Pattern DECIMAL_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[、.]?\\s*(.+)$");
    /** 单级数字编号：^(\d+)\s*[、.]\s*标题 — 匹配 "1. 标题" / "1、标题" */
    private static final Pattern SINGLE_LEVEL_DIGIT_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");
    /** 中文章节：第[一二三四五六七八九十百\d]+[章节条部分] — 匹配 "第一章 总则" */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("^(第([一二三四五六七八九十百\\d]+)[章节条部分])\\s*(.+)$");
    /** 附录：附录[A-Z/中文] 标题 — 匹配 "附录A" / "附录一" */
    private static final Pattern APPENDIX_PATTERN = Pattern.compile("^(附录\\s*([A-Za-z一二三四五六七八九十百\\d]+))(?:\\s+(.+))?$");
    /** 中文提纲编号：^[一二三四五六七八九十百]+[、.]\s*标题 — 匹配 "一、背景" */
    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");
    /** 显式步骤：第N步 / 步骤N — 匹配 "第一步 登录" / "步骤1 验证" */
    private static final Pattern EXPLICIT_STEP_PATTERN = Pattern.compile("^(?:第\\s*([0-9一二三四五六七八九十百]+)\\s*步|步骤\\s*([0-9一二三四五六七八九十百]+))\\s*[:：、.]?\\s*(.+)$");
    /** 无序列表标记：-, *, • — 匹配 "- 列表项" / "* 列表项" */
    private static final Pattern BULLET_PATTERN = Pattern.compile("^([-*+•])\\s+(.+)$");
    /** 复选框：[- [x] ] — 匹配 "[ ] 待办" / "[x] 已完成" */
    private static final Pattern CHECKBOX_PATTERN = Pattern.compile("^\\[(?: |x|X)]\\s+(.+)$");
    /** 页码噪声：第N页 / Page N / N/M — 匹配 "第 1 页" / "Page 3" / "1/5" */
    private static final Pattern PAGE_NOISE_PATTERN = Pattern.compile("^(?:第\\s*\\d+\\s*页|Page\\s*\\d+|\\d+\\s*/\\s*\\d+)$", Pattern.CASE_INSENSITIVE);
    /** 版权噪声：版权所有 / 未经授权 / 内部使用 / copyright / all rights reserved / 保密 */
    private static final Pattern COPYRIGHT_NOISE_PATTERN = Pattern.compile(".*(?:版权所有|未经授权|内部使用|copyright|all rights reserved|保密).*", Pattern.CASE_INSENSITIVE);
    /** 版本脚注：V1.0 / 版本 / 修订 / Rev.1 */
    private static final Pattern VERSION_FOOTER_PATTERN = Pattern.compile(".*(?:\\bV\\d+(?:\\.\\d+)*\\b|版本|修订|Rev\\.?\\s*\\d+).*", Pattern.CASE_INSENSITIVE);
    /** 内联步骤切分边界：用于将 "第一步...第二步..." 拆为多个逻辑行 */
    private static final Pattern INLINE_EXPLICIT_STEP_BOUNDARY_PATTERN = Pattern.compile("(?=(?:第\\s*[0-9一二三四五六七八九十百]+\\s*步|步骤\\s*[0-9一二三四五六七八九十百]+)\\s*[:：、.])");
    /** 表格行判断辅助：检测 "|" 分隔符个数 */
    private static final Pattern TABLE_SPLIT_PATTERN = Pattern.compile("\\|");

    /**
     * 应用配置 — 用于读取结构解析相关的阈值参数，包括：
     * maxPlainHeadingChars：纯文本标题最大字符数（默认32）
     * contextWindowLines：歧义消解的上下文窗口行数
     * ambiguityConfidenceFloor/Ceil：歧义信号置信度上下界
     */
    private final DocumentManageProperties properties;

    /**
     * 行级分类器 — 用于兜底场景。
     * 当所有正则都无法匹配时，由 DocumentLineClassifier 统计判断该行是否为标题。
     * 判据包括：行长度、中英文比例、标点特征等。
     */
    private final DocumentLineClassifier documentLineClassifier;

    /**
     * 构造函数 — Spring 依赖注入。
     * @param properties             应用配置（结构解析相关阈值）
     * @param documentLineClassifier 行级分类器（兜底标题判断）
     */
    public DocumentStructureSignalExtractor(DocumentManageProperties properties,
                                            DocumentLineClassifier documentLineClassifier) {
        // 保存配置引用，用于读取 maxPlainHeadingChars 等阈值
        this.properties = properties;
        // 保存行分类器引用，用于 classify 方法的兜底分支
        this.documentLineClassifier = documentLineClassifier;
    }

    // ═══════════════════════════════════════════════════════════
    //  入口方法：extract — 从纯文本中提取结构信号
    // ═══════════════════════════════════════════════════════════

    /**
     * 从清洗后的纯文本中提取结构信号 — 这是整个结构解析管线的入口。
     * <p>
     * 内部流程（三步）：
     * <ol>
     *   <li><b>构建逻辑行</b>：将原始文本按 \n 拆分，再对内联步骤行做二次拆分，
     *       生成逻辑行列表（每条逻辑行有独立的 lineNo）。</li>
     *   <li><b>统计行频</b>：遍历逻辑行，统计每条标准化文本的出现次数，
     *       用于后续识别重复噪声（如页眉页脚、重复标题）。</li>
     *   <li><b>逐行分类</b>：对每个逻辑行，按 16 级正则匹配链判断类型，
     *       生成对应的结构信号（HEADING / LIST_ITEM / BODY / NOISE 等）。</li>
     * </ol>
     * <p>
     * 返回结果包含两样东西：
     * <ul>
     *   <li><b>contextLines</b> — 全文行列表，下标从 0 开始，供歧义消解器构造上下文</li>
     *   <li><b>signals</b> — 信号列表，lineNo=0 是 DOCUMENT_TITLE，后续与 contextLines 通过 lineNo-1 关联</li>
     * </ul>
     *
     * @param documentTitle 文档标题（通常是文件名），用于填充 DOCUMENT_TITLE 信号
     *                      以及与正文中的重复标题做去重判断
     * @param parsedText    经 TikaDocumentParserService 清洗后的纯文本全文
     * @return DocumentStructureSignalBatch — 包含全文行列表和信号列表
     *         - 如果 parsedText 为空，返回的 signals 仅包含 DOCUMENT_TITLE（如果标题非空）
     *         - 如果 parsedText 不为空，返回 signals 按 lineNo 升序排列
     */
    public DocumentStructureSignalBatch extract(String documentTitle, String parsedText) {

        // ── Step 1: 入参规范化 ──────────────────────────────────────
        // 对文档标题做安全处理：null→空字符串，并去除首尾空白
        String normalizedTitle = safeText(documentTitle);

        // ── Step 2: 构建逻辑行列表 ────────────────────────────────────
        // 将原始文本拆分为逻辑行（LogicalLine），每行包含 lineNo/rawText/normalizedText/indentLevel 等信息
        // 内部会处理内联步骤拆分（"第一步...第二步..."→ 两行）和缩进计算
        List<DocumentStructureLogicalLine> logicalLines = buildLogicalLines(parsedText);

        // ── Step 3: 统计行频 ──────────────────────────────────────────
        // 遍历所有逻辑行，统计每条 normalizedText 出现的总次数
        // 结果用于 isRepeatedNoise() 方法判断重复行是否为页眉/页脚噪声
        Map<String, Integer> lineFrequency = buildLineFrequency(logicalLines);

        // ── Step 4: 准备信号容器 ─────────────────────────────────────
        // 预分配容量 = 逻辑行数 + 1（为 DOCUMENT_TITLE 信号预留一个位置）
        List<DocumentStructureSignal> signals = new ArrayList<>(logicalLines.size() + 1);

        // ── Step 5: 添加文档标题信号 ─────────────────────────────────
        // lineNo=0 的 DOCUMENT_TITLE 信号，代表整个文档的根标题
        // 这个信号将作为层级构建阶段根节点的标题来源
        if (StrUtil.isNotBlank(normalizedTitle)) {
            // 只有标题非空时才添加，避免产生无意义的空标题信号
            signals.add(DocumentStructureSignal.builder()
                .lineNo(0)                              // 行号固定为 0，不与任何物理行对应
                .rawText(normalizedTitle)               // 原始文本 = 文档标题
                .normalizedText(normalizedTitle)        // 标准化文本 = 文档标题（已 trim）
                .kind(DocumentStructureSignalKind.DOCUMENT_TITLE)  // 类型 = 文档标题
                .title(normalizedTitle)                 // 标题文本
                .levelHint(0)                           // 层级提示 = 0（根级别）
                .confidence(1.0D)                       // 置信度 = 1.0（完全确定）
                .build());                              // 构建信号对象
        }

        // ── Step 6: 逐行分类 ─────────────────────────────────────────
        // 遍历所有逻辑行，为每一行调用 classify() 方法进行类型判定
        for (int index = 0; index < logicalLines.size(); index++) {
            // 获取当前逻辑行对象（包含行号、原始文本、标准化文本、缩进级别）
            DocumentStructureLogicalLine logicalLine = logicalLines.get(index);
            // 构建当前行的上下文信息（前后非空行、前后空白标记）
            // 上下文用于辅助判定：如单级数字是标题还是列表项、纯文本行是否是标题候选
            LineContext context = buildContext(logicalLines, index);
            // 调用 classify() 进行分类，传入文档标题、当前行、上下文、行频统计
            // classify() 内部按 16 级正则匹配链逐一尝试，返回判定后的信号
            signals.add(classify(normalizedTitle, logicalLine, context, lineFrequency));
        }

        // ── Step 7: 提取纯文本行列表 ──────────────────────────────────
        // 从逻辑行列表中提取 normalizedText 字段，形成顺序的上下文行列表
        // 这个列表传递给歧义消解器（AmbiguityResolver），用于构建 LLM 提示中的上下文窗口
        // 列表下标从 0 开始，signals[i].lineNo - 1 = contextLines 的下标
        List<String> contextLines = logicalLines.stream()
            .map(DocumentStructureLogicalLine::normalizedText)  // 提取标准化文本
            .toList();                                          // 收集为不可变列表

        // ── Step 8: 返回结果 ─────────────────────────────────────────
        // DocumentStructureSignalBatch 同时携带：
        //   - contextLines：全文行列表（给歧义消解用）
        //   - signals：信号列表（给层级构建用）
        return new DocumentStructureSignalBatch(contextLines, signals);
    }

    // ═══════════════════════════════════════════════════════════
    //  核心分类方法（16 级分类链）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对单条逻辑行进行分类判定 — 整个 extract 方法的核心。
     * <p>
     * 按照 16 级优先级链逐一尝试匹配，一旦匹配立即返回。
     * <pre>
     *  ① 空行 → BLANK
     *  ② 重复噪声（页眉/页脚/版权/版本脚注）→ NOISE
     *  ③ 页码噪声 → NOISE
     *  ④ Markdown 标题（# ~ ######）→ HEADING
     *  ⑤ 显式步骤（第N步/步骤N）→ STEP_ITEM
     *  ⑥ 第X章/第X节 → HEADING
     *  ⑦ 附录 → HEADING
     *  ⑧ 多级数字编号（1.2.3）→ HEADING
     *  ⑨ 表格行（|分隔/制表符）→ TABLE_ROW
     *  ⑩ 引用块（>开头）→ QUOTE
     *  ⑪ 复选框（[x]）→ LIST_ITEM
     *  ⑫ 无序列表（- / * / •）→ LIST_ITEM
     *  ⑬ 单级数字编号（1. / 2.）→ HEADING_CANDIDATE（标题候选）或 LIST_ITEM（列表项）
     *  ⑭ 中文提纲编号（一、/二、）→ HEADING_CANDIDATE（标题候选）或 LIST_ITEM（列表项）
     *  ⑮ 兜底行分类器判定 → HEADING_CANDIDATE（标题候选）或 BODY
     *  ⑯ 以上均不匹配 → BODY（普通正文）
     * </pre>
     * <p>
     * 设计要点：
     * <ul>
     *   <li>确定性匹配（如 Markdown、"第X章"）直接返回 HEADING，置信度 0.92-0.98</li>
     *   <li>模糊匹配（单级数字/中文提纲/Plain 文本）返回 HEADING_CANDIDATE，置信度 0.58-0.62，
     *       留给 LLM 消歧或默认降级为 BODY</li>
     *   <li>连续数字序列且上下文不符合标题特征 → LIST_ITEM</li>
     *   <li>重复行（≥2 次）且符合噪声特征 → NOISE（丢弃）</li>
     * </ul>
     *
     * @param documentTitle 文档标题（用于与正文中的重复标题做去重）
     * @param logicalLine   当前逻辑行（含行号、原始文本、标准化文本、缩进级别）
     * @param context       上下文信息（前后非空行、前后是否有空行）
     * @param lineFrequency 全文行频统计映射（键=normalizedText，值=出现次数）
     * @return 分类后的信号对象（含类型、标题、编码、置信度等信息）
     */
    private DocumentStructureSignal classify(String documentTitle,
                                             DocumentStructureLogicalLine logicalLine,
                                             LineContext context,
                                             Map<String, Integer> lineFrequency) {

        // ── 提取当前行的基本信息 ──────────────────────────────────
        int lineNo = logicalLine.lineNo();                      // 逻辑行号（从 1 开始递增）
        String rawText = logicalLine.rawText();                 // 原始文本（未经任何处理）
        String normalized = logicalLine.normalizedText();       // 标准化文本（已 trim + 空白压缩）

        // ── 提取上下文中的前后非空行文本（用于辅助判定） ──────────
        // 前一非空行的标准化文本（没有则为空字符串）
        String previousNonBlank = context.previousNonBlank() == null
            ? ""
            : context.previousNonBlank().normalizedText();
        // 后一非空行的标准化文本（没有则为空字符串）
        String nextNonBlank = context.nextNonBlank() == null
            ? ""
            : context.nextNonBlank().normalizedText();

        // ═══════════════════════════════════════════════════════════
        //  ① 空行 → BLANK
        //  ═══════════════════════════════════════════════════════════
        // 空行本身没有内容价值，但作为段落边界信号在层级构建阶段有用：
        // 它可以触发列表栈清除（listStack.clear()），确保后续内容不受前序列表影响
        if (normalized.isBlank()) {
            // 返回 BLANK 信号，不提取任何编码/标题，置信度 1.0
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.BLANK, "", "", 0, null, List.of(), 1.0D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ② 重复噪声（页眉/页脚/版权声明/版本脚注）→ NOISE
        //  ═══════════════════════════════════════════════════════════
        // 调用 isRepeatedNoise 判断当前行是否属于页眉/页脚噪声：
        //   - 与文档标题完全相同的行（如 PDF 每页顶部的文件名）
        //   - 匹配版权声明的行（如 "版权所有 © 2024"）
        //   - 出现≥3次且匹配版本脚注或包含分隔符的行
        if (isRepeatedNoise(documentTitle, normalized, lineFrequency.getOrDefault(normalized, 0))) {
            // 返回 NOISE 信号，该行在层级构建阶段会被忽略
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.NOISE, "", "", 0, null,
                List.of("repeated-running-header-or-footer"), 0.99D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ③ 页码噪声 → NOISE
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "第 1 页" / "Page 3" / "1/5" 等页码格式
        // 页码在文档正文中没有结构含义，直接丢弃
        if (PAGE_NOISE_PATTERN.matcher(normalized).matches()) {
            // 返回 NOISE 信号，标注原因为 "page-noise"
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.NOISE, "", "", 0, null,
                List.of("page-noise"), 0.98D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ④ Markdown 标题（# ~ ######）→ HEADING
        //  ═══════════════════════════════════════════════════════════
        // 匹配 Markdown 风格的标题：一个或多个 # 后跟空格和标题内容
        // group(1) = # 的个数（如 "##"），group(2) = 标题内容
        Matcher markdown = MARKDOWN_HEADING_PATTERN.matcher(normalized);
        if (markdown.matches()) {
            // 提取标题内容（去掉 # 前缀后的纯文本）
            String title = markdown.group(2).trim();
            // 检查标题是否与文档标题重复（如 Markdown 文件首行的 "# 文件名"）
            if (sameDocumentTitle(documentTitle, title)) {
                // 与文档标题相同 → 判为噪声（避免产生与根节点重复的章节）
                return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                    DocumentStructureSignalKind.NOISE, "", title, 0, null,
                    List.of("duplicate-document-title"), 0.99D);
            }
            // 构建 HEADING 信号，levelHint = # 的个数（1~6）
            DocumentStructureSignal signal = signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.HEADING,
                extractCode(title),               // 尝试从标题中提取编码（如 "1.2"）
                title,                            // 标题文本
                markdown.group(1).length(),       // # 个数作为层级提示
                null,                             // 列表项序号 = null（不是列表）
                List.of("markdown-heading"),      // 分类原因标签
                0.98D);                           // 置信度 0.98（非常可靠）
            // 设置数字路径（如 "1.2.3" → [1, 2, 3]），供精确层级定位
            signal.setNumericPath(extractNumericPath(signal.getNodeCode()));
            return signal;
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑤ 显式步骤（第N步 / 步骤N）→ STEP_ITEM
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "第一步 登录" / "步骤 1: 验证" 等操作步骤描述
        // group(1)=数字/中文序号, group(2)=步骤序号（英文数字）, group(3)=步骤内容
        Matcher explicitStep = EXPLICIT_STEP_PATTERN.matcher(normalized);
        if (explicitStep.matches()) {
            // 解析步骤序号：优先用 group(1)（"第一步"→"一"），否则用 group(2)（"步骤1"→"1"）
            Integer itemIndex = parseLooseNumber(
                StrUtil.blankToDefault(explicitStep.group(1), explicitStep.group(2)));
            // 返回 STEP_ITEM 信号，携带步骤序号和步骤内容
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.STEP_ITEM,    // 类型 = 步骤项
                "",                                       // 无编码
                explicitStep.group(3).trim(),             // 步骤内容
                null,                                     // levelHint = null（不由levelHint决定层级）
                itemIndex,                                // 步骤序号
                List.of("explicit-step"),                 // 分类原因标签
                0.96D);                                   // 置信度 0.96
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑥ 第X章 → HEADING
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "第一章 总则" / "第1节 概述" / "第二条 定义" 等中文章节标题
        // group(1)="第一章", group(2)="一"/"1", group(3)="总则"
        Matcher chapter = CHAPTER_PATTERN.matcher(normalized);
        if (chapter.matches()) {
            // 提取编码前缀（如 "第一章"）和标题内容（如 "总则"）
            String code = chapter.group(1).trim();
            String title = chapter.group(3).trim();
            // 检查标题是否与文档标题重复
            if (sameDocumentTitle(documentTitle, title)) {
                return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                    DocumentStructureSignalKind.NOISE, code, title, 0, null,
                    List.of("duplicate-document-title"), 0.99D);
            }
            // 构建 HEADING 信号，levelHint=1（一级标题）
            DocumentStructureSignal signal = signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.HEADING,
                code, title, 1, null,
                List.of("chapter-heading"), 0.96D);
            // 解析章号（如 "一"→1, "1"→1），设置为数字路径
            Integer chapterNo = parseLooseNumber(chapter.group(2));
            if (chapterNo != null && chapterNo > 0) {
                // 数字路径 = [章号]，后续子节通过 "章号.节号" 路径定位
                signal.setNumericPath(List.of(chapterNo));
            }
            return signal;
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑦ 附录 → HEADING
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "附录A 相关表格" / "附录一 数据" 等附录标题
        // group(1)="附录A", group(2)="A"/"一", group(3)="相关表格"
        Matcher appendix = APPENDIX_PATTERN.matcher(normalized);
        if (appendix.matches()) {
            // 提取附录编码（如 "附录A"）
            String code = appendix.group(1).trim();
            // 标题默认用 group(3)，如果没有标题内容则回退到编码本身
            String title = StrUtil.blankToDefault(appendix.group(3), code).trim();
            // 返回 HEADING 信号，levelHint=1，置信度 0.92
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.HEADING,
                code, title, 1, null,
                List.of("appendix-heading"), 0.92D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑧ 多级数字编号（如 1.2.3）→ HEADING
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "1.2.3 数据校验" / "2.1 背景" 等由句点分隔的多级编号标题
        // group(1)="1.2.3", group(2)="数据校验"
        Matcher decimal = DECIMAL_HEADING_PATTERN.matcher(normalized);
        if (decimal.matches()) {
            // 提取编号（如 "1.2.3"）和标题内容（如 "数据校验"）
            String code = decimal.group(1).trim();
            String title = decimal.group(2).trim();
            // levelHint = 句点数 + 1（如 "1.2.3" 有 2 个句点 → levelHint=3）
            // 这只是一个初始提示，层级构建阶段会根据已有父节点进行精确调整
            DocumentStructureSignal signal = signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.HEADING,
                code, title,
                Math.max(1, code.split("\\.").length),  // levelHint = 段数
                null,                                     // 不是列表项
                List.of("decimal-heading"),               // 分类原因标签
                0.95D);                                   // 置信度 0.95
            // 设置数字路径（如 "1.2.3" → [1, 2, 3]），供精确层级定位
            signal.setNumericPath(extractNumericPath(code));
            return signal;
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑨ 表格行 → TABLE_ROW
        //  ═══════════════════════════════════════════════════════════
        // 通过 isTableRow() 判断：以|开头结尾、或含制表符、或含3+个|分隔符、或是表格分隔行
        // 表格行原样保留，不进一步解析其内部结构
        if (isTableRow(normalized)) {
            // 返回 TABLE_ROW 信号，内容原样保留
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.TABLE_ROW, "", normalized, null, null,
                List.of("table-row"), 0.90D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑩ 引用块 > → QUOTE
        //  ═══════════════════════════════════════════════════════════
        // 匹配以 > 开头的行（Markdown/邮件风格的引用块）
        if (normalized.startsWith(">")) {
            // 返回 QUOTE 信号，内容原样保留
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.QUOTE, "", normalized, null, null,
                List.of("quote"), 0.88D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑪ 复选框 → LIST_ITEM
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "[ ] 待办事项" / "[x] 已完成" 等复选框格式
        Matcher checkbox = CHECKBOX_PATTERN.matcher(normalized);
        if (checkbox.matches()) {
            // group(1) = 复选框后的文本内容
            // 返回 LIST_ITEM 信号，不携带序号（复选框没有数字序号）
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.LIST_ITEM, "",
                checkbox.group(1).trim(),  // 复选框后的实际内容
                null,     // levelHint = null
                null,     // itemIndex = null（无序号）
                List.of("checkbox-list"),   // 分类原因标签
                0.92D);                     // 置信度 0.92
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑫ 无序列表（- / * / •）→ LIST_ITEM
        //  ═══════════════════════════════════════════════════════════
        // 匹配 "- 条目" / "* 条目" / "• 条目" 等无序列表格式
        Matcher bullet = BULLET_PATTERN.matcher(normalized);
        if (bullet.matches()) {
            // group(1) = 列表标记符号（-/*/•），group(2) = 列表内容
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.LIST_ITEM, "",
                bullet.group(2).trim(),  // 列表内容
                null,     // levelHint = null
                null,     // itemIndex = null（无序）
                List.of("bullet-list"),   // 分类原因标签
                0.90D);                   // 置信度 0.90
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑬ 单级数字编号（1. / 2. / 3.）→ HEADING_CANDIDATE 或 LIST_ITEM
        //  ═══════════════════════════════════════════════════════════
        // 这是最模糊的情况："1. 背景" 既可能是标题，也可能是列表项
        // 通过三个判断因子决定：
        //   a) 与前后行构成连续序列（如 1/2/3 连续出现）→ 列表项
        //   b) 上一行以冒号结尾（如 "条款如下："）→ 列表项
        //   c) 满足纯文本标题特征（孤立行+简短+无标点）→ 标题候选
        Matcher singleDigit = SINGLE_LEVEL_DIGIT_PATTERN.matcher(normalized);
        if (singleDigit.matches()) {
            // 提取标题内容和数字序号
            String title = singleDigit.group(2).trim();
            Integer itemIndex = parseLooseNumber(singleDigit.group(1));

            // 因子 a：与前后行构成连续序列（如当前是 2，前一行是 1）→ 判为列表项
            boolean sequential = isNeighborSequence(itemIndex, OrderedMarkerFamily.ARABIC_SINGLE, context);

            // 因子 b：上一行以冒号结尾（如 "以下是清单："）→ 判为列表项
            boolean introducedByLeadIn = previousIntroducesList(context.previousNonBlank());

            // 因子 c：既不连续也不由冒号引导，且满足纯文本标题特征 → 标题候选
            boolean headingLike = !sequential
                && !introducedByLeadIn
                && looksLikePlainHeading(title, context);

            // 构建信号：标题候选用 HEADING_CANDIDATE（低置信度），列表项用 LIST_ITEM（高置信度）
            DocumentStructureSignal signal = signal(
                lineNo,
                rawText,
                normalized,
                logicalLine.indentLevel(),
                headingLike
                    ? DocumentStructureSignalKind.HEADING_CANDIDATE  // 标题候选
                    : DocumentStructureSignalKind.LIST_ITEM,         // 列表项
                singleDigit.group(1).trim(),  // 编码 = 数字（如 "1"）
                title,                        // 标题/内容
                headingLike ? 1 : null,       // 标题候选的 levelHint=1
                itemIndex,                    // 序号
                List.of(headingLike
                    ? "single-digit-ambiguous-heading"   // 标题候选的模糊标记
                    : sequential
                        ? "single-digit-sequence-list"   // 连续序列列表
                        : "single-digit-list"),          // 普通数字列表
                headingLike
                    ? 0.62D           // 标题候选：低置信度 0.62
                    : sequential || introducedByLeadIn
                        ? 0.93D       // 连续/引导列表：高置信度 0.93
                        : 0.88D       // 普通列表：中置信度 0.88
            );
            // 如果判为标题候选且有序号，设置数字路径用于层级定位
            if (headingLike && itemIndex != null && itemIndex > 0) {
                signal.setNumericPath(List.of(itemIndex));
            }
            return signal;
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑭ 中文提纲编号（一、 / 二、 / 三、）→ HEADING_CANDIDATE 或 LIST_ITEM
        //  ═══════════════════════════════════════════════════════════
        // 逻辑与单级数字相同：根据连续性和上下文判断是标题还是列表
        Matcher chineseOutline = CHINESE_OUTLINE_PATTERN.matcher(normalized);
        if (chineseOutline.matches()) {
            // 提取标题内容和中文序号
            String title = chineseOutline.group(2).trim();
            Integer index = parseLooseNumber(chineseOutline.group(1));

            // 因子 a：与前后行构成连续序列（一/二/三）
            boolean sequential = isNeighborSequence(index, OrderedMarkerFamily.CHINESE_OUTLINE, context);
            // 因子 b：上一行以冒号结尾
            boolean introducedByLeadIn = previousIntroducesList(context.previousNonBlank());
            // 因子 c：满足纯文本标题特征
            boolean headingLike = !sequential
                && !introducedByLeadIn
                && looksLikePlainHeading(title, context);

            // 构建信号
            DocumentStructureSignal signal = signal(
                lineNo,
                rawText,
                normalized,
                logicalLine.indentLevel(),
                headingLike
                    ? DocumentStructureSignalKind.HEADING_CANDIDATE
                    : DocumentStructureSignalKind.LIST_ITEM,
                chineseOutline.group(1).trim(),  // 编码 = 中文数字（如 "一"）
                title,
                headingLike ? 1 : null,
                index,
                List.of(headingLike
                    ? "chinese-outline-ambiguous-heading"
                    : sequential
                        ? "chinese-outline-sequence-list"
                        : "chinese-outline-list"),
                headingLike
                    ? 0.60D
                    : sequential || introducedByLeadIn
                        ? 0.92D
                        : 0.86D
            );
            // 标题候选设置数字路径
            if (headingLike && index != null && index > 0) {
                signal.setNumericPath(List.of(index));
            }
            return signal;
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑮ 兜底分类：行分类器判断 + 语义猜测
        //  ═══════════════════════════════════════════════════════════
        // 当所有正则都无法匹配时，先用 DocumentLineClassifier 判断是否为标题
        DocumentLineClassifier.LineClassification fallback = documentLineClassifier.classify(normalized);
        // 如果行分类器说"不是标题"，但 looksLikePlainHeading 说"看起来像"：
        // 说明文本有明显的标题特征（孤立行/简短/无标点），但分类器过于保守
        // 这种情况以低置信度返回 HEADING_CANDIDATE
        if (!fallback.isHeading() && looksLikePlainHeading(normalized, context)) {
            // 返回 HEADING_CANDIDATE，置信度仅 0.58
            // 这个信号在 LLM 消歧不可用时会被降级为 BODY，不会造成破坏
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
                DocumentStructureSignalKind.HEADING_CANDIDATE,
                "", normalized, inferPlainHeadingLevel(context), null,
                List.of("plain-heading-candidate"), 0.58D);
        }

        // ═══════════════════════════════════════════════════════════
        //  ⑯ 以上均不匹配 → BODY（普通正文）
        //  ═══════════════════════════════════════════════════════════
        // 默认分类：所有无法匹配的行都被视为普通正文
        // 正文行在层级构建阶段会被追加到最近的标题节点或列表项节点
        return signal(lineNo, rawText, normalized, logicalLine.indentLevel(),
            DocumentStructureSignalKind.BODY,
            "", normalized, null, null, List.of("body"), 1.0D);
    }

    // ═══════════════════════════════════════════════════════════
    //  信号构造辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 快速构造一个信号对象 — classify 方法中所有返回都通过此方法创建。
     * <p>
     * 统一使用 builder 模式，确保每个字段都有合理的默认值：
     * <ul>
     *   <li>nodeCode：null → 空字符串</li>
     *   <li>title：null → 回退到 normalizedText</li>
     *   <li>reasons：包装为新的 ArrayList（可变，方便后续添加标签如 "llm-disambiguated"）</li>
     * </ul>
     *
     * @param lineNo      逻辑行号
     * @param rawText     原始文本
     * @param normalized  标准化文本
     * @param indentLevel 缩进级别
     * @param kind        信号类型
     * @param code        节点编码（如 "1.2" / "第一章" / "1"）
     * @param title       标题文本
     * @param levelHint   层级提示
     * @param itemIndex   列表项序号
     * @param reasons     分类原因标签列表（会被包装为可变的 ArrayList）
     * @param confidence  置信度 [0, 1]
     * @return 构造完成的信号对象
     */
    private DocumentStructureSignal signal(int lineNo,
                                           String rawText,
                                           String normalized,
                                           int indentLevel,
                                           DocumentStructureSignalKind kind,
                                           String code,
                                           String title,
                                           Integer levelHint,
                                           Integer itemIndex,
                                           List<String> reasons,
                                           double confidence) {
        // 使用 Lombok @Builder 构建信号对象
        return DocumentStructureSignal.builder()
            .lineNo(lineNo)                                                      // 逻辑行号
            .rawText(rawText)                                                     // 原始文本
            .normalizedText(normalized)                                           // 标准化文本
            .kind(kind)                                                           // 信号类型
            .nodeCode(StrUtil.blankToDefault(code, ""))                          // 编码（空→""）
            .title(StrUtil.blankToDefault(title, normalized))                    // 标题（空→回退到normalized）
            .levelHint(levelHint)                                                 // 层级提示
            .indentLevel(indentLevel)                                             // 缩进级别
            .itemIndex(itemIndex)                                                 // 列表项序号
            .reasons(new ArrayList<>(reasons))                                   // 原因标签（可变副本）
            .confidence(confidence)                                               // 置信度
            .build();
    }

    /**
     * 从标题文本中提取编码前缀 — 用于从 Markdown 标题中再提取数字编码。
     * <p>
     * 例如 Markdown 标题 "# 1.2 数据校验" → decode="# 1.2 数据校验",
     * extractCode("1.2 数据校验") → "1.2"。
     * <p>
     * 遍历三种模式，返回第一个匹配到的编码，都不匹配则返回空字符串。
     */
    private String extractCode(String title) {
        // 尝试匹配多级数字编号（如 "1.2 标题"→"1.2"）
        Matcher decimal = DECIMAL_HEADING_PATTERN.matcher(title);
        if (decimal.matches()) {
            return decimal.group(1).trim();
        }
        // 尝试匹配中文章节（如 "第一章 标题"→"第一章"）
        Matcher chapter = CHAPTER_PATTERN.matcher(title);
        if (chapter.matches()) {
            return chapter.group(1).trim();
        }
        // 尝试匹配附录（如 "附录A 标题"→"附录A"）
        Matcher appendix = APPENDIX_PATTERN.matcher(title);
        if (appendix.matches()) {
            return appendix.group(1).trim();
        }
        // 都不匹配 → 无编码
        return "";
    }

    /**
     * 从编码字符串中提取数字路径 — 用于精确层级定位。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>"1.2.3" → [1, 2, 3]（多级数字编号）</li>
     *   <li>"第一章" → [1]（中文章节号）</li>
     *   <li>"1" → [1]（单级数字）</li>
     *   <li>空字符串 → []（空列表）</li>
     *   <li>非数字路径 → []（返回空列表防止错误）</li>
     * </ul>
     * <p>
     * 数字路径在层级构建阶段用于找到精确的父节点（如 1.2.3 的父节点是 1.2），
     * 而不是简单按 depth 就近匹配，大大提高了多级编号文档的结构还原准确度。
     */
    private List<Integer> extractNumericPath(String code) {
        // 安全获取编码文本
        String normalized = safeText(code);
        // 空字符串 → 空路径
        if (normalized.isBlank()) {
            return List.of();
        }
        // 包含句点 → 多级数字编号（如 "1.2.3"）
        if (normalized.contains(".")) {
            List<Integer> path = new ArrayList<>();
            // 按句点分割，逐段解析为整数
            for (String segment : normalized.split("\\.")) {
                // 非纯数字段落 → 路径解析失败，返回空
                if (!segment.chars().allMatch(Character::isDigit)) {
                    return List.of();
                }
                // 添加到数字路径
                path.add(Integer.parseInt(segment));
            }
            return path;
        }
        // 尝试匹配 "第X章" 模式（在末尾附加占位文本以匹配 Pattern）
        Matcher chapter = CHAPTER_PATTERN.matcher(normalized + " 标题");
        if (chapter.find()) {
            // 解析章号（如 "一"→1, "1"→1）
            Integer chapterNo = parseLooseNumber(chapter.group(2));
            if (chapterNo != null && chapterNo > 0) {
                // 返回 [章号] 作为数字路径
                return List.of(chapterNo);
            }
        }
        // 无法解析 → 返回空路径
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════
    //  行类型判断辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断一行文本是否为表格行 — 用于 classify 的第 ⑨ 步。
     * <p>
     * 四种情况会被判定为表格行：
     * <ol>
     *   <li>以 | 开头且以 | 结尾（标准 Markdown/文本表格）</li>
     *   <li>包含制表符（TSV/CSV 风格列分隔）</li>
     *   <li>包含 ≥ 3 个 | 分隔符（多列表格）</li>
     *   <li>仅由 ":"、"-"、"|" 和空格组成（表格分隔行，如 "|---|---|"）</li>
     * </ol>
     * <p>
     * 表格行在信号阶段不做进一步解析，原样保留，
     * 后续切块阶段可以根据需要做表格感知处理。
     */
    private boolean isTableRow(String normalized) {
        // 条件1：以|开头且以|结尾（标准表格行）
        if (normalized.startsWith("|") && normalized.endsWith("|")) {
            return true;
        }
        // 条件2：包含制表符（TSV 格式）
        if (normalized.contains("\t")) {
            return true;
        }
        // 条件3：包含3+个|分隔符（说明至少3列）
        if (TABLE_SPLIT_PATTERN.split(normalized).length >= 3 && normalized.contains("|")) {
            return true;
        }
        // 条件4：纯表格分隔行（如 "|:---|---:|"）
        // 注意：这里用 matches() 要求整行都匹配，避免误判
        return normalized.matches("^[:\\-\\s|]+$");
    }

    /**
     * 判断一行文本是否"看起来像"纯文本标题 — 用于 classify 的第 ⑬⑭⑮ 步。
     * <p>
     * 当没有明显的 Markdown/数字编号标记时，通过以下特征综合判断：
     * <ol>
     *   <li><b>长度限制</b>：≤ maxPlainHeadingChars（配置项，默认 32 字符）</li>
     *   <li><b>无句号标点结尾</b>：不以 。！？；.!?; 结尾</li>
     *   <li><b>无 URL</b>：不包含 http/https 链接</li>
     *   <li><b>非表格</b>：不以 | 开头或结尾</li>
     *   <li><b>非分隔符</b>：不是纯 --- / === / ___ 行</li>
     *   <li><b>孤立行</b>：前后至少有一侧是空行（说明它是段落标题）</li>
     *   <li><b>后续有内容</b>：下一非空行不是分隔符行</li>
     *   <li><b>名词性短语</b>：不含中文逗号/分号/句号/冒号（标题通常不含内部标点）</li>
     * </ol>
     * 所有条件必须同时满足才会返回 true，这是一个保守的启发式规则。
     */
    private boolean looksLikePlainHeading(String text,
                                          LineContext context) {
        // 安全获取文本
        String normalized = safeText(text);
        // 空文本 → 不可能是标题
        if (text.isBlank()) {
            return false;
        }
        // ① 长度必须 ≤ 配置的最大标题字符数（默认 32）
        if (normalized.length() > properties.getStructureParsing().getMaxPlainHeadingChars()) {
            return false;
        }
        // ② 不能以句号/叹号/问号/分号结尾（完整句子 → 正文）
        if (endsWithSentencePunctuation(normalized)) {
            return false;
        }
        // ③ 不能包含 URL（链接 → 正文/参考信息）
        if (normalized.contains("http://") || normalized.contains("https://")) {
            return false;
        }
        // ④ 不能是表格行
        if (normalized.startsWith("|") || normalized.endsWith("|")) {
            return false;
        }
        // ⑤ 不能是纯分隔符行（如 "---" / "===" / "___"）
        if (normalized.matches("^[\\-=_]{3,}$")) {
            return false;
        }
        // ⑥ 孤立行判断：前后至少有一侧是空行
        // 标题通常被空行包围（或至少一侧有空行），正文则通常连续
        boolean isolated = context.blankBefore() || context.blankAfter();
        // ⑦ 下一非空行必须是内容行（不是分隔符）
        boolean nextLooksContent = context.nextNonBlank() != null
            && StrUtil.isNotBlank(context.nextNonBlank().normalizedText())
            && !context.nextNonBlank().normalizedText().matches("^[:\\-\\s|]+$");
        // ⑧ 名词性短语：不含句中标点（逗号、分号、句号、冒号）
        boolean nounLike = !normalized.contains("，")
            && !normalized.contains("；")
            && !normalized.contains("。")
            && !normalized.contains("：")
            && !normalized.toLowerCase(Locale.ROOT).startsWith("http");
        // 所有条件同时满足 → 看起来像标题
        return isolated && nextLooksContent && nounLike;
    }

    /**
     * 推断纯文本标题候选的层级 — 用于 classify 兜底分支的 levelHint 计算。
     * <p>
     * 启发式规则：
     * <ul>
     *   <li>如果上文是空行（即该行在段落之后出现）→ 视为一级标题（levelHint=1）</li>
     *   <li>如果上文不是空行（紧跟在上一段落后）→ 视为二级标题（levelHint=2）</li>
     * </ul>
     * <p>
     * 注意：这只是初始提示，最终层级由 HierarchyResolver 根据已构建的树结构进一步调整。
     */
    private int inferPlainHeadingLevel(LineContext context) {
        // 上文是空行（或没有上下文）→ 一级标题
        if (context == null || context.blankBefore()) {
            return 1;
        }
        // 上文不是空行 → 二级标题
        return 2;
    }

    /**
     * 判断文本是否以句尾标点结尾 — 用于 looksLikePlainHeading 的过滤条件。
     * <p>
     * 同时支持中英文句尾标点：
     * 中文：。！？；
     * 英文：.!?;
     */
    private boolean endsWithSentencePunctuation(String text) {
        return text.endsWith("。")
            || text.endsWith("！")
            || text.endsWith("？")
            || text.endsWith("；")
            || text.endsWith(".")
            || text.endsWith("!")
            || text.endsWith("?")
            || text.endsWith(";");
    }

    // ═══════════════════════════════════════════════════════════
    //  逻辑行构建 — extract 调用的第 1 步
    // ═══════════════════════════════════════════════════════════

    /**
     * 将原始文本转换为逻辑行列表 — extract 方法的第一步。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>按 \n 将原始文本切分为原始行</li>
     *   <li>对每行按内联步骤边界做二次拆分（如 "第一步A第二步B" → 两行）</li>
     *   <li>对每个最终片段计算缩进级别（空格/制表符计数）</li>
     *   <li>为每个逻辑行分配递增的 lineNo</li>
     * </ol>
     * <p>
     * 为什么需要"逻辑行"概念？
     * 有些文档将多个步骤写在同一行（如 "第一步：登录。第二步：验证。"），
     * 如果按原始行处理，会丢失每个步骤的独立结构信息。
     * 通过内联步骤拆分，我们能在不修改原文的情况下"发现"隐藏的结构。
     *
     * @param parsedText 清洗后的纯文本全文
     * @return 逻辑行列表，按 lineNo 升序排列，从 1 开始
     */
    private List<DocumentStructureLogicalLine> buildLogicalLines(String parsedText) {
        // 按 \n 切分为原始行，保留末尾空行（-1 参数）
        String[] rawLines = StrUtil.blankToDefault(parsedText, "").split("\n", -1);
        // 预分配容量 = 原始行数（可能有拆分，但先按1:1分配）
        List<DocumentStructureLogicalLine> logicalLines = new ArrayList<>(rawLines.length);
        // 逻辑行号从 1 开始递增
        int logicalLineNo = 1;

        // 遍历每一行原始文本
        for (int index = 0; index < rawLines.length; index++) {
            // 获取当前原始行（null→空字符串）
            String rawLine = StrUtil.blankToDefault(rawLines[index], "");
            // 尝试按内联步骤边界拆分为多个片段
            List<String> segments = splitInlineSegments(rawLine);

            // 如果拆分为空（原始行是空行）→ 生成一个空逻辑行
            if (segments.isEmpty()) {
                logicalLines.add(new DocumentStructureLogicalLine(
                    logicalLineNo++,  // 行号递增
                    index + 1,        // 原始行号（从1开始）
                    1,                // 段内序号（空行无拆分，=1）
                    0,                // 缩进级别（空行为0）
                    rawLine,          // 原始文本（保留原样）
                    safeText(rawLine) // 标准化文本
                ));
                continue;
            }

            // 遍历拆分后的每个片段，每个生成一条逻辑行
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                String segment = segments.get(segmentIndex);
                logicalLines.add(new DocumentStructureLogicalLine(
                    logicalLineNo++,               // 行号递增
                    index + 1,                     // 原始行号
                    segmentIndex + 1,              // 段内序号（从1开始）
                    countIndentLevel(segment),     // 计算缩进级别
                    segment,                       // 片段原始文本
                    safeText(segment)              // 片段标准化文本
                ));
            }
        }
        // 返回完整逻辑行列表
        return logicalLines;
    }

    /**
     * 将一行原始文本按内联步骤边界拆分为多个片段 — buildLogicalLines 的子方法。
     * <p>
     * 只在遇到 "第一步" / "步骤 1" 等显式步骤标记时拆分。
     * Markdown 标题、表格行、引用块等特殊格式不拆分（保持完整性）。
     * <p>
     * 示例：
     * <pre>
     *   "第一步：登录。第二步：验证。" → ["第一步：登录。", "第二步：验证。"]
     *   "# 第一章 总则" → ["# 第一章 总则"]（不拆分）
     *   "普通正文行" → ["普通正文行"]（不拆分）
     * </pre>
     *
     * @param rawLine 一行原始文本
     * @return 拆分后的片段列表。如果不需拆分，返回包含整行的单元素列表。
     *         如果原始行为空或空白，返回空列表。
     */
    private List<String> splitInlineSegments(String rawLine) {
        // null 行 → 空列表
        if (rawLine == null) {
            return List.of();
        }
        // 空白行（trim后为空）→ 空列表
        if (rawLine.trim().isEmpty()) {
            return List.of();
        }
        // 获取 trim 后的文本用于格式判断
        String trimmed = rawLine.trim();

        // ── 特殊行不做拆分 ──
        // 这些行的格式是完整的，拆分会破坏其结构意义
        if (trimmed.startsWith("#")      // Markdown 标题
            || trimmed.startsWith("|")   // 表格行
            || trimmed.startsWith(">")   // 引用块
            || trimmed.matches("^[:\\-\\s|]+$")) {  // 表格分隔行
            return List.of(rawLine);  // 整行作为一个片段
        }

        // ── 查找内联步骤边界 ──
        List<Integer> boundaries = new ArrayList<>();  // 存储拆分边界位置
        boundaries.add(0);                              // 起始边界 = 0
        Matcher matcher = INLINE_EXPLICIT_STEP_BOUNDARY_PATTERN.matcher(rawLine);
        while (matcher.find()) {                       // 找到每个 "第N步" / "步骤N" 的出现位置
            if (matcher.start() > 0) {                 // 不在行首（忽略行首的匹配）
                boundaries.add(matcher.start());        // 记录拆分位置
            }
        }

        // ── 没有找到拆分边界 → 整行作为一个片段 ──
        if (boundaries.size() == 1) {
            return List.of(rawLine);
        }

        // ── 按边界拆分 ──
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < boundaries.size(); index++) {
            int start = boundaries.get(index);                                  // 当前片段起始位置
            int end = index == boundaries.size() - 1
                ? rawLine.length()                                              // 最后一个边界到行尾
                : boundaries.get(index + 1);                                    // 否则到下一个边界
            String segment = rawLine.substring(start, end).trim();              // 截取并去空白
            if (StrUtil.isNotBlank(segment)) {                                  // 忽略空片段
                segments.add(segment);
            }
        }
        // 如果所有片段都是空的（极边缘情况）→ 回退到整行
        return segments.isEmpty() ? List.of(rawLine) : segments;
    }

    /**
     * 计算一段文本的前导缩进级别 — 用于 buildLogicalLines 的逻辑行构建。
     * <p>
     * 规则：
     * <ul>
     *   <li>空格按 1:1 计数（一个空格=1级缩进）</li>
     *   <li>制表符按 1:4 折算（一个制表符=4级缩进）</li>
     *   <li>遇到非空白字符立即停止</li>
     * </ul>
     * <p>
     * 缩进级别在层级构建阶段用于判断列表项的嵌套深度。
     */
    private int countIndentLevel(String text) {
        // null 或空字符串 → 缩进为 0
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int indent = 0;               // 累计缩进级别
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == ' ') {     // 空格：+1
                indent++;
                continue;
            }
            if (current == '\t') {    // 制表符：+4
                indent += 4;
                continue;
            }
            break;                    // 非空白字符 → 结束计数
        }
        return indent;
    }

    // ═══════════════════════════════════════════════════════════
    //  上下文与行频统计 — extract 调用的第 2 步
    // ═══════════════════════════════════════════════════════════

    /**
     * 统计全文各行标准化文本的出现频率 — extract 方法的第二步。
     * <p>
     * 遍历所有逻辑行，统计每条 normalizedText 的出现次数。
     * 结果用于 isRepeatedNoise() 判断重复行是否为页眉/页脚噪声。
     * <p>
     * 使用 LinkedHashMap 保持首次出现的插入顺序，方便调试时按原文顺序查看。
     */
    private Map<String, Integer> buildLineFrequency(List<DocumentStructureLogicalLine> logicalLines) {
        // LinkedHashMap：保持插入顺序
        Map<String, Integer> frequency = new LinkedHashMap<>();
        // 遍历所有逻辑行
        for (DocumentStructureLogicalLine logicalLine : logicalLines) {
            // 跳过空行和 null 行
            if (logicalLine == null || StrUtil.isBlank(logicalLine.normalizedText())) {
                continue;
            }
            // 行频 +1（如果首次出现则初始化为 1）
            frequency.merge(logicalLine.normalizedText(), 1, Integer::sum);
        }
        return frequency;
    }

    /**
     * 为指定行构建上下文信息 — 用于 classify 方法的辅助判定。
     * <p>
     * 上下文包含四项信息：
     * <ul>
     *   <li><b>previousNonBlank</b>：前一个非空行（text.trim().length() > 0）</li>
     *   <li><b>nextNonBlank</b>：后一个非空行</li>
     *   <li><b>blankBefore</b>：当前行之前是否有空行（即与前一个非空行之间有分隔）</li>
     *   <li><b>blankAfter</b>：当前行之后是否有空行</li>
     * </ul>
     * <p>
     * 这些信息在 classify() 中有三个关键用途：
     * <ol>
     *   <li>单级数字是标题还是列表 → 通过 isNeighborSequence 检查前后行的连续性</li>
     *   <li>纯文本是否是标题候选 → 通过 blankBefore/blankAfter 判断是否孤立行</li>
     *   <li>列表项是否由冒号引导 → 通过 previousNonBlank 检查是否以 ：或 : 结尾</li>
     * </ol>
     *
     * @param logicalLines 完整逻辑行列表
     * @param currentIndex 当前行在列表中的索引
     * @return 上下文对象（包含前后非空行和空白标记）
     */
    private LineContext buildContext(List<DocumentStructureLogicalLine> logicalLines, int currentIndex) {
        // ── 向前查找前一个非空行 ──
        DocumentStructureLogicalLine previousNonBlank = null;  // 前一个非空行
        boolean blankBefore = false;                           // 当前行之前是否有空行
        for (int index = currentIndex - 1; index >= 0; index--) {
            DocumentStructureLogicalLine candidate = logicalLines.get(index);
            if (StrUtil.isBlank(candidate.normalizedText())) {
                blankBefore = true;   // 遇到空行 → 标记 blankBefore
                continue;             // 继续向前查找
            }
            previousNonBlank = candidate;  // 找到非空行 → 记录
            break;                         // 停止查找
        }

        // ── 向后查找后一个非空行 ──
        DocumentStructureLogicalLine nextNonBlank = null;  // 后一个非空行
        boolean blankAfter = false;                        // 当前行之后是否有空行
        for (int index = currentIndex + 1; index < logicalLines.size(); index++) {
            DocumentStructureLogicalLine candidate = logicalLines.get(index);
            if (StrUtil.isBlank(candidate.normalizedText())) {
                blankAfter = true;    // 遇到空行 → 标记 blankAfter
                continue;             // 继续向后查找
            }
            nextNonBlank = candidate;    // 找到非空行 → 记录
            break;                       // 停止查找
        }

        // 返回构建好的上下文对象
        return new LineContext(previousNonBlank, nextNonBlank, blankBefore, blankAfter);
    }

    // ═══════════════════════════════════════════════════════════
    //  噪声识别 — classify 的第 ② 步调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断一行是否属于重复噪声（页眉/页脚/版权/版本脚注）— classify 的第②步。
     * <p>
     * 判定规则（短路逻辑，满足任一即返回 true）：
     * <ul>
     *   <li>出现 ≥ 2 次且与文档标题相同 → 噪声（PDF 的重复文件名页眉）</li>
     *   <li>出现 ≥ 2 次且匹配版权模式 → 噪声（每页底部的版权声明）</li>
     *   <li>出现 ≥ 3 次、长度 ≤ 120、且匹配版本脚注或包含 | 分隔符 → 噪声</li>
     * </ul>
     * <p>
     * 注意：出现 2 次但不匹配任何模式的行不会被当作噪声。
     * 因为有些合法的标题/列表可能恰好出现两次（如文档前后的摘要）。
     */
    private boolean isRepeatedNoise(String documentTitle,
                                    String normalized,
                                    int frequency) {
        // 出现次数 < 2 或空行 → 不是噪声
        if (frequency < 2 || StrUtil.isBlank(normalized)) {
            return false;
        }
        // 与文档标题相同（如 PDF 每页顶部的文件名）→ 噪声
        if (sameDocumentTitle(documentTitle, normalized)) {
            return true;
        }
        // 匹配版权模式（如 "版权所有 © 2024"）→ 噪声
        if (COPYRIGHT_NOISE_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        // 出现 ≥ 3 次、不太长、且匹配版本脚注或包含 |（脚注分隔符）→ 噪声
        return frequency >= 3
            && normalized.length() <= 120
            && (VERSION_FOOTER_PATTERN.matcher(normalized).matches() || normalized.contains("|"));
    }

    // ═══════════════════════════════════════════════════════════
    //  文档标题比对 — 多个地方调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断一段文本是否与文档标题相同（标准化后比较）。
     * 用于去重：当正文中的标题与文档标题完全相同时，判为噪声。
     */
    private boolean sameDocumentTitle(String documentTitle, String candidate) {
        // 对文档标题做标准化
        String left = normalizeComparableTitle(documentTitle);
        // 对候选文本做标准化
        String right = normalizeComparableTitle(candidate);
        // 非空且完全相同 → true
        return StrUtil.isNotBlank(left) && left.equals(right);
    }

    /**
     * 标准化标题文本以进行比较 — sameDocumentTitle 的辅助方法。
     * <p>
     * 标准化步骤：
     * <ol>
     *   <li>移除 Markdown 前缀（如 "# "、"## "）</li>
     *   <li>移除文件扩展名（如 ".pdf"、".md"）</li>
     *   <li>移除所有空白字符</li>
     *   <li>转为小写</li>
     * </ol>
     * <p>
     * 例如：
     * <ul>
     *   <li>"# 王者荣耀综合介绍.md" → "王者荣耀综合介绍"</li>
     *   <li>"王者荣耀综合介绍" → "王者荣耀综合介绍"</li>
     * </ul>
     */
    private String normalizeComparableTitle(String text) {
        // 安全获取文本
        String normalized = safeText(text);
        // 空文本 → 返回空字符串
        if (normalized.isBlank()) {
            return "";
        }
        return normalized
            .replaceAll("^#+\\s*", "")           // 移除 Markdown # 前缀
            .replaceAll("\\.[A-Za-z0-9]{1,6}$", "")  // 移除文件扩展名（如 .pdf / .docx）
            .replaceAll("\\s+", "")              // 移除所有空白
            .toLowerCase(Locale.ROOT);           // 转为小写
    }

    // ═══════════════════════════════════════════════════════════
    //  列表上下文判断 — classify 的第 ⑬⑭ 步调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断上一行是否以冒号结尾 — 用于区分标题和列表项。
     * <p>
     * 规则："以下是主要条款：" 这类行以冒号结尾，后面应该是列表项而非标题。
     * 同时支持中英文冒号（: 和 ：）。
     */
    private boolean previousIntroducesList(DocumentStructureLogicalLine previousNonBlank) {
        // 没有上一行 → 不引导
        if (previousNonBlank == null) {
            return false;
        }
        // 获取上一行的标准化文本
        String previous = safeText(previousNonBlank.normalizedText());
        // 以冒号结尾 → 引导列表
        return previous.endsWith("：") || previous.endsWith(":");
    }

    /**
     * 判断指定序号的列表项与上下行是否构成连续序列 — 用于区分标题和列表项。
     * <p>
     * 例如：当前行是 "2. xxx"，前一行是 "1. yyy"
     * → 2 = 1 + 1，构成连续序列 → 判定为列表项。
     * <p>
     * 同时检查前一行（offset=-1）和后一行（offset=+1），
     * 只要有一侧构成连续即视为序列。
     *
     * @param itemIndex 当前行的序号
     * @param family    序号家族（ARABIC_SINGLE / CHINESE_OUTLINE），不同家族不交叉
     * @param context   上下文
     * @return 是否构成连续序列
     */
    private boolean isNeighborSequence(Integer itemIndex,
                                       OrderedMarkerFamily family,
                                       LineContext context) {
        // 序号或家族为 null → 无法判断
        if (itemIndex == null || family == null) {
            return false;
        }
        // 前一行是 itemIndex-1（offset=-1）或后一行是 itemIndex+1（offset=+1）
        return isSequenceNeighbor(context.previousNonBlank(), itemIndex, family, -1)
            || isSequenceNeighbor(context.nextNonBlank(), itemIndex, family, 1);
    }

    /**
     * 判断相邻行是否是指定偏移量的连续序号 — isNeighborSequence 的子方法。
     * <p>
     * 从前/后相邻行中解析出同一个家族的数字序号，
     * 检查是否等于 itemIndex + offset。
     *
     * @param candidate 相邻行
     * @param itemIndex 当前行的序号
     * @param family    序号家族
     * @param offset    偏移方向（-1 向前，+1 向后）
     * @return 是否构成偏移量为 offset 的连续序列
     */
    private boolean isSequenceNeighbor(DocumentStructureLogicalLine candidate,
                                       Integer itemIndex,
                                       OrderedMarkerFamily family,
                                       int offset) {
        // 候选行或序号为 null → 不连续
        if (candidate == null || itemIndex == null) {
            return false;
        }
        // 从候选行中解析出指定家族的序号
        Integer candidateIndex = resolveOrderedIndex(candidate.normalizedText(), family);
        // 候选行序号 = 当前序号 + offset → 连续
        return candidateIndex != null && candidateIndex.intValue() == itemIndex.intValue() + offset;
    }

    /**
     * 从文本中解析指定家族的数字序号 — isSequenceNeighbor 的辅助方法。
     * <p>
     * 示例：
     * <ul>
     *   <li>"1. xxx"（ARABIC_SINGLE）→ 1</li>
     *   <li>"一、xxx"（CHINESE_OUTLINE）→ 1</li>
     *   <li>"普通正文" → null（无法解析）</li>
     * </ul>
     *
     * @param text   待解析的文本行
     * @param family 序号家族
     * @return 解析出的序号，无法解析返回 null
     */
    private Integer resolveOrderedIndex(String text,
                                        OrderedMarkerFamily family) {
        // 安全获取文本
        String normalized = safeText(text);
        // 空文本 → 无法解析
        if (normalized.isBlank()) {
            return null;
        }
        // 根据家族类型选择解析策略
        return switch (family) {
            case ARABIC_SINGLE -> {
                // 阿拉伯数字：匹配 "1. xxx" 模式
                Matcher matcher = SINGLE_LEVEL_DIGIT_PATTERN.matcher(normalized);
                yield matcher.matches() ? parseLooseNumber(matcher.group(1)) : null;
            }
            case CHINESE_OUTLINE -> {
                // 中文数字：匹配 "一、xxx" 模式
                Matcher matcher = CHINESE_OUTLINE_PATTERN.matcher(normalized);
                yield matcher.matches() ? parseLooseNumber(matcher.group(1)) : null;
            }
        };
    }

    // ═══════════════════════════════════════════════════════════
    //  中文 ↔ 数字解析 — 跨模块公用方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 宽松解析数字：同时支持阿拉伯数字和中文字数字 — 多步调用。
     * <p>
     * 支持的格式（含边界情况）：
     * <ul>
     *   <li>"1" → 1</li>
     *   <li>"一" → 1</li>
     *   <li>"十" → 10</li>
     *   <li>"十一" → 11</li>
     *   <li>"二十" → 20</li>
     *   <li>"二十一" → 21</li>
     *   <li>"三" → 3</li>
     *   <li>"" → null</li>
     *   <li>"abc" → null</li>
     * </ul>
     * <p>
     * 注意：不支持"亿"和"万"级别的大数（文档章节编号基本不会超过 99）。
     */
    private Integer parseLooseNumber(String text) {
        // 安全获取文本
        String normalized = safeText(text);
        // 空文本 → 无法解析
        if (normalized.isBlank()) {
            return null;
        }
        // ── 阿拉伯数字直接解析 ──
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }

        // ── 中文数字映射表 ──
        // 一~九 映射为 1~9
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );

        // ── 中文数字组合解析 ──
        // "十" = 10
        if ("十".equals(normalized)) {
            return 10;
        }
        // "十一"、"十二"... = 11, 12...
        if (normalized.length() == 2 && normalized.startsWith("十")) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        // "二十"、"三十"... = 20, 30...
        if (normalized.length() == 2 && normalized.endsWith("十")) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        // "二十一"、"三十二"... = 21, 32...
        if (normalized.length() == 3 && normalized.contains("十")) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10
                + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        // 单字数字：直接查表
        return digitMap.get(normalized.charAt(0));
    }

    /**
     * 安全获取文本 — 统一处理 null 值。
     * null → 空字符串，并去除首尾空白。
     */
    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * 有序序列标记的"家族"类型 — 用于 isNeighborSequence 判断序列连续性。
     * <p>
     * 不同家族的序号体系独立判断连续性：
     * <ul>
     *   <li>阿拉伯数字（1, 2, 3）不与中文数字（一, 二, 三）交叉序列</li>
     *   <li>即使 "一" = 1 且 "1" = 1，它们也不构成连续序列</li>
     * </ul>
     */
    private enum OrderedMarkerFamily {
        /** 单级阿拉伯数字：1., 2., 3. */
        ARABIC_SINGLE,
        /** 中文提纲数字：一、二、三 */
        CHINESE_OUTLINE
    }

    /**
     * 行上下文记录 — 当前行在原文中的前后文信息。
     * <p>
     * 用于 classify 方法中判断：
     * <ul>
     *   <li>单级数字是标题还是列表项（通过连续性）</li>
     *   <li>纯文本行是否是标题候选（通过孤立性）</li>
     * </ul>
     *
     * @param previousNonBlank 前一个非空行（没有则为 null）
     * @param nextNonBlank     后一个非空行（没有则为 null）
     * @param blankBefore      当前行之前是否有空行
     * @param blankAfter       当前行之后是否有空行
     */
    private record LineContext(
        DocumentStructureLogicalLine previousNonBlank,
        DocumentStructureLogicalLine nextNonBlank,
        boolean blankBefore,
        boolean blankAfter
    ) {
    }
}
