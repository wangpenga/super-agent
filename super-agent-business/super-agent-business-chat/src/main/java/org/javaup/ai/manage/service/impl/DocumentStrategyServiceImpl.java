package org.javaup.ai.manage.service.impl;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.util.StrUtil;                            // Hutool 字符串工具
import com.fasterxml.jackson.core.type.TypeReference;           // Jackson 泛型类型引用
import com.fasterxml.jackson.databind.ObjectMapper;             // Jackson JSON 解析器
import lombok.AllArgsConstructor;                               // Lombok：全参数构造函数
import lombok.extern.slf4j.Slf4j;                              // Lombok：日志
import org.javaup.ai.manage.config.DocumentManageProperties;   // 应用配置（切块参数）
import org.javaup.ai.manage.data.SuperAgentDocument;           // 文档实体
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;   // 策略方案实体
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;   // 策略步骤实体
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;  // 结构节点实体
import org.javaup.ai.manage.service.DocumentStrategyService;       // 策略服务接口
import org.javaup.ai.manage.service.DocumentStructureNodeService;  // 结构节点服务
import org.javaup.ai.manage.support.ChunkCandidate;            // 切块候选（中间数据结构）
import org.javaup.ai.manage.support.DocumentAnalysisResult;    // 文档解析结果（Tika 输出）
import org.javaup.ai.manage.support.DocumentLineClassifier;    // 行级分类器
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft; // 策略方案草稿（推荐阶段输出）
import org.javaup.ai.manage.support.DocumentStrategyStepDraft; // 策略步骤草稿
import org.javaup.ai.manage.support.ParentBlockCandidate;      // 父块候选
import org.javaup.ai.prompt.PromptTemplateNames;               // 提示词模板名称常量
import org.javaup.ai.prompt.PromptTemplateService;             // 提示词模板渲染服务
import org.javaup.enums.DocumentChunkSourceTypeEnum;           // 切块来源类型
import org.javaup.enums.DocumentContentQualityLevelEnum;       // 内容质量等级
import org.javaup.enums.DocumentFileTypeEnum;                  // 文件类型枚举
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;     // 策略执行状态
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;      // 策略管线类型（PARENT/CHILD）
import org.javaup.enums.DocumentStrategyRoleEnum;              // 策略角色（PRIMARY/FALLBACK/OPTIMIZE/ENHANCE）
import org.javaup.enums.DocumentStrategySourceTypeEnum;        // 策略来源类型（系统推荐/用户添加/用户保留）
import org.javaup.enums.DocumentStrategyTypeEnum;              // 策略类型（STRUCTURE/RECURSIVE/SEMANTIC/LLM）
import org.javaup.enums.DocumentStructureLevelEnum;            // 文档结构等级
import org.javaup.enums.DocumentStructureNodeTypeEnum;         // 结构节点类型枚举
import org.springframework.ai.chat.client.ChatClient;          // Spring AI 聊天客户端
import org.springframework.ai.chat.model.ChatModel;            // Spring AI 聊天模型
import org.springframework.beans.factory.ObjectProvider;       // Spring Bean 延迟获取
import org.springframework.stereotype.Service;                 // Spring 服务注解

import java.util.ArrayDeque;       // 数组双端队列（用作栈）
import java.util.ArrayList;        // 动态数组
import java.util.Arrays;           // 数组工具
import java.util.Comparator;       // 比较器（排序）
import java.util.Deque;            // 双端队列接口
import java.util.LinkedHashMap;    // 有序哈希表
import java.util.LinkedHashSet;    // 有序哈希集
import java.util.List;             // 列表接口
import java.util.Locale;           // 地区设置
import java.util.Map;              // 映射接口
import java.util.Set;              // 集合接口
import java.util.regex.Matcher;    // 正则匹配器
import java.util.regex.Pattern;    // 正则编译模式
import java.util.stream.Collectors; // 流收集器

/**
 * 文档策略服务实现 — 负责两件核心工作：
 * <ol>
 *   <li><b>策略推荐</b>（{@link #recommendStrategy}）：根据文档解析结果，自动推荐切块方案
 *       （用哪些策略、按什么顺序执行）</li>
 *   <li><b>切块执行</b>（{@link #buildParentBlocks}）：根据确认后的策略方案，
 *       对纯文本执行多阶段切块流水线，生成 Parent-Child 结构的切块候选</li>
 * </ol>
 * <p>
 * === 策略类型体系 ===
 * <ul>
 *   <li><b>STRUCTURE（结构感知切块）</b>：按章节标题边界切分，保留语义完整的大段落</li>
 *   <li><b>RECURSIVE（递归切块）</b>：按段落→行→句→固定窗口逐级递降切分，保证不超过最大长度</li>
 *   <li><b>SEMANTIC（语义切块）</b>：基于 Jaccard 相似度检测主题边界，在主题切换处分块</li>
 *   <li><b>LLM（大模型智能切块）</b>：调用 LLM 理解文本语义后做智能分块，兜底回退到语义切块</li>
 * </ul>
 * <p>
 * === Parent-Child 双层结构 ===
 * <ul>
 *   <li><b>PARENT（父块）</b>：大粒度单元（上限 2200 字符），是回答阶段的"证据片段"，
 *       保留足够的上下文语义</li>
 *   <li><b>CHILD（子块）</b>：小粒度单元（上限 800 字符），是检索阶段的"匹配单元"，
 *       颗粒度细、匹配精度高</li>
 * </ul>
 */
@Slf4j      // Lombok：生成 log 字段
@AllArgsConstructor  // Lombok：为所有 final 字段生成构造函数（Spring 自动注入）
@Service    // 声明为 Spring 服务
public class DocumentStrategyServiceImpl implements DocumentStrategyService {

    // ═══════════════════════════════════════════════════════════
    //  常量与正则
    // ═══════════════════════════════════════════════════════════

    /** 英文/数字token提取正则：匹配 2 个以上连续的字母数字（用于语义 Jaccard 计算） */
    private static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("[A-Za-z0-9]{2,}");

    /**
     * 父块最大字符数（2200）— Parent-Child 结构中父块的上限。
     * 父块是回答阶段写入 prompt 的最小证据单位，太大会撑爆上下文，太小会丢失上下文。
     */
    private static final int PARENT_BLOCK_MAX_CHARS = 2200;

    /** 父块递归切块时的重叠字符数（180）— 缓解切块边界断裂 */
    private static final int PARENT_BLOCK_OVERLAP_CHARS = 180;

    /** 父块语义切块的目标最大字符数（1600） */
    private static final int PARENT_SEMANTIC_MAX_CHARS = 1600;

    /** 父块语义切块的最小字符数（480），低于此值不再继续切分 */
    private static final int PARENT_SEMANTIC_MIN_CHARS = 480;

    // ═══════════════════════════════════════════════════════════
    //  依赖注入
    // ═══════════════════════════════════════════════════════════

    /** 应用配置 — 读取切块参数（recursiveMaxChars、semanticMaxChars、阈值等） */
    private final DocumentManageProperties properties;

    /** Jackson JSON 解析器 — 用于解析 LLM 智能切块的返回结果 */
    private final ObjectMapper objectMapper;

    /** ChatModel 延迟获取器 — 用于 LLM 智能切块，不可用时降级到语义切块 */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 行级分类器 — 用于结构切块时判断某行是否为标题 */
    private final DocumentLineClassifier documentLineClassifier;

    /** 结构节点服务 — 查询文档的章节树节点，供结构切块使用 */
    private final DocumentStructureNodeService structureNodeService;

    /** 提示词模板服务 — 渲染 LLM 智能切块的提示词 */
    private final PromptTemplateService promptTemplateService;

    // ═══════════════════════════════════════════════════════════
    //  策略推荐入口：recommendStrategy
    //  被 DocumentAsyncProcessServiceImpl.handleParseRoute 调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据文档解析结果自动推荐切块策略方案 — 流水线的"大脑"。
     * <p>
     * 调用时机：文档解析完成后，异步流水线 {@code handleParseRoute} 调用此方法。
     * <p>
     * === 推荐逻辑 ===
     * <ol>
     *   <li><b>父块策略选择</b>（回答单元或证据单元的大粒度切块）：
     *       <ul>
     *         <li>结构完整（PDF/DOC/MD/HTML + 标题≥2）→ STRUCTURE（按章节切）</li>
     *         <li>其他 → RECURSIVE（递归切，保证长度可控）</li>
     *       </ul></li>
     *   <li><b>子块策略选择</b>（检索匹配单元的小粒度切块）：
     *       <ul>
     *         <li>文档质量低且内容充分 → LLM 智能切块（兜底→SEMANTIC）</li>
     *         <li>主题边界明确 → SEMANTIC 语义切块</li>
     *         <li>兜底 → RECURSIVE 递归切块（保证总有东西）</li>
     *       </ul></li>
     * </ol>
     *
     * @param document      文档实体（含文件类型等信息）
     * @param analysisResult Tika 解析结果（含文本、结构等级、内容质量、段落数等）
     * @return 策略方案草稿（含快照、推荐理由、父+子步骤列表）
     */
    @Override
    public DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document,
                                                       DocumentAnalysisResult analysisResult) {

        // ── Step 1: 收集推荐理由（拼接为字符串供前端展示）───────────────
        List<String> reasonList = new ArrayList<>();

        // 获取文件类型，用于判断是否适合使用结构感知切块
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getRc(document.getFileType());

        // ── Step 2: 四项策略适用性判断 ──────────────────────────────
        // 这些 shouldXxx 方法各自按独立规则打分，互不影响
        boolean structureRecommended = shouldUseStructure(fileType, analysisResult);   // 是否适用结构切块
        boolean recursiveRecommended = shouldUseRecursive(analysisResult);              // 是否适用递归切块（超长文本）
        boolean semanticRecommended = shouldUseSemantic(analysisResult);                // 是否适用语义切块
        boolean llmRecommended = shouldUseLlm(analysisResult);                         // 是否推荐 LLM 智能切块

        // ── Step 3: 决定父块流水线的策略类型 ─────────────────────────
        // 父块 = 大粒度回答单元，优先保语义完整性而非尺寸控制
        List<Integer> parentStrategyTypes = new ArrayList<>();           // 策略类型列表（有序）
        Map<Integer, String> parentReasonMap = new LinkedHashMap<>();    // 每步的推荐理由

        if (structureRecommended) {
            // 文档结构完整（标题≥2）→ 按章节边界切块
            // 优点：保留天然语义边界，回答时有完整上下文
            parentStrategyTypes.add(DocumentStrategyTypeEnum.STRUCTURE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                "检测到文档具有较明显的标题或章节结构，父块优先保留天然章节边界。");
            reasonList.add("父块流水线优先采用基于文档结构切块，保留回答阶段需要的大语义单元。");
        } else {
            // 无稳定结构 → 使用大粒度递归切块兜底
            // 保证父块长度不超过 2200 字符，同时通过重叠缓解边界断裂
            parentStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "未识别出稳定结构时，父块先使用较大粒度的递归分块作为稳定回答单元。");
            reasonList.add("父块流水线未命中明显结构信号，默认使用较大粒度递归分块作为回答单元。");
        }

        // ── Step 4: 决定子块流水线的策略类型 ─────────────────────────
        // 子块 = 小粒度检索单元，优先精确匹配而非完整性
        List<Integer> childStrategyTypes = new ArrayList<>();           // 策略类型列表（有序）
        Map<Integer, String> childReasonMap = new LinkedHashMap<>();    // 每步的推荐理由

        if (llmRecommended) {
            // 文档质量低（乱码率高/文字过短）→ 用 LLM 理解后切块
            // LLM 能根据语义而非固定规则做切分，适合低质量/非结构化文本
            childStrategyTypes.add(DocumentStrategyTypeEnum.LLM.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.LLM.getCode(),
                "文档质量偏低或结构识别不稳定，子块先使用大模型智能切块增强复杂场景。");
            reasonList.add("子块流水线追加大模型智能切块，处理低质量或结构不稳定文本。");
        } else if (semanticRecommended) {
            // 内容足够、主题边界明确 → 用 Jaccard 相似度检测主题切换
            // 在主题切换处分块，既能控制长度又能保持语义完整性
            childStrategyTypes.add(DocumentStrategyTypeEnum.SEMANTIC.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.SEMANTIC.getCode(),
                "文本主题边界相对明确，子块先使用语义分块优化召回边界。");
            reasonList.add("子块流水线优先采用语义分块，优化召回边界和主题完整性。");
        }

        // 递归切块作为子块兜底：要么因为文本超长需要切，要么前序策略后还需要进一步控制长度
        // 条件：文本超长（recursiveRecommended）或 LLM 推荐（LLM 后也需要递归兜底）或没有任何其他子块策略
        if (recursiveRecommended || llmRecommended || childStrategyTypes.isEmpty()) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "文档整体较长、存在超长段落，或需要在增强切块后追加长度兜底。");
            reasonList.add("子块流水线追加递归分块，控制召回单元长度并作为兜底。");
        }

        // ── Step 5: 构建策略步骤草稿 ──────────────────────────────
        // 将策略类型列表转为具体的步骤对象（含角色：主策略/兜底/优化/增强）
        List<DocumentStrategyStepDraft> parentSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.PARENT, parentStrategyTypes, parentReasonMap);
        List<DocumentStrategyStepDraft> childSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.CHILD, childStrategyTypes, childReasonMap);

        // ── Step 6: 构建策略快照字符串（用于持久化和前端展示）─────────
        // 格式示例："PARENT:2;CHILD:4,3,2"
        // 含义：父块流水线 = 结构切块，子块流水线 = LLM → 语义 → 递归
        String strategySnapshot = buildCombinedStrategySnapshot(parentSteps, childSteps);

        // ── Step 7: 返回方案草稿 ──────────────────────────────────
        // 包含：快照字符串、推荐理由（中文分号拼接）、父步骤列表、子步骤列表
        return new DocumentStrategyPlanDraft(
            strategySnapshot,                       // 策略快照
            String.join("；", reasonList),          // 推荐理由汇总
            parentSteps,                            // 父块步骤
            childSteps                              // 子块步骤
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  策略规范化入口：normalizeSteps
    //  被 DocumentManageServiceImpl.confirmStrategy 调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 规范化用户确认/修改后的策略步骤 — 将前端的策略选择与数据库中的基础方案合并。
     * <p>
     * 调用时机：用户在界面上确认或修改推荐方案后，此方法将用户的选择
     * 转换为完整的步骤列表（保留用户手动添加/删除的步骤）。
     * <p>
     * 字段说明：
     * <ul>
     *   <li>用户保留的步骤 → sourceType = USER_KEEP（沿用基础方案的推荐理由）</li>
     *   <li>用户新增的步骤 → sourceType = USER_ADD（推荐理由="用户手动追加"）</li>
     * </ul>
     *
     * @param basePlan                  数据库中的基础方案（系统推荐方案）
     * @param baseSteps                 基础方案的步骤列表
     * @param requestParentStrategyTypes 用户确认的父块策略类型列表（可能增删排序）
     * @param requestChildStrategyTypes  用户确认的子块策略类型列表
     * @param documentId                文档 ID
     * @return 规范化后的步骤列表（已去重+排序）
     */
    @Override
    public List<SuperAgentDocumentStrategyStep> normalizeSteps(
            SuperAgentDocumentStrategyPlan basePlan,
            List<SuperAgentDocumentStrategyStep> baseSteps,
            List<Integer> requestParentStrategyTypes,
            List<Integer> requestChildStrategyTypes,
            Long documentId) {

        // ── Step 1: 去重 → 去除前端传入列表中可能的重复策略类型 ──
        List<Integer> normalizedParentTypes = normalizePipelineTypes(requestParentStrategyTypes);
        List<Integer> normalizedChildTypes = normalizePipelineTypes(requestChildStrategyTypes);

        // ── Step 2: 按管线类型 + 策略类型建立快速查找索引 ──────────
        // MAP: pipelineType → strategyType → step
        Map<String, Map<Integer, SuperAgentDocumentStrategyStep>> baseStepMap = new LinkedHashMap<>();
        for (SuperAgentDocumentStrategyStep baseStep : baseSteps) {
            String pipelineType = baseStep.getPipelineType();
            // 兼容旧的步骤数据：pipelineType 为空时默认为 CHILD
            if (StrUtil.isBlank(pipelineType)) {
                pipelineType = DocumentStrategyPipelineTypeEnum.CHILD.getCode();
            }
            baseStepMap.computeIfAbsent(pipelineType, ignored -> new LinkedHashMap<>())
                .put(baseStep.getStrategyType(), baseStep);
        }

        // ── Step 3: 分别构建 PARENT 和 CHILD 的规范化步骤列表 ──────
        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();

        // 父块步骤
        normalizedStepList.addAll(buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum.PARENT,
            normalizedParentTypes,
            baseStepMap.getOrDefault(DocumentStrategyPipelineTypeEnum.PARENT.getCode(), Map.of()),
            documentId));

        // 子块步骤
        normalizedStepList.addAll(buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum.CHILD,
            normalizedChildTypes,
            baseStepMap.getOrDefault(DocumentStrategyPipelineTypeEnum.CHILD.getCode(), Map.of()),
            documentId));

        return normalizedStepList;
    }

    // ═══════════════════════════════════════════════════════════
    //  切块执行入口：buildParentBlocks
    //  被 DocumentAsyncProcessServiceImpl.handleIndexBuild 调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据确认后的策略方案对纯文本执行切块 — 流水线的"双手"。
     * <p>
     * 调用时机：用户确认方案后，异步流水线 {@code handleIndexBuild} 调用此方法。
     * <p>
     * === 执行流程 ===
     * <ol>
     *   <li>将方案步骤按管线类型拆分为 parentSteps 和 childSteps</li>
     *   <li>执行父块流水线（buildParentSeedList）：按步骤链逐步切分，得到父块种子列表</li>
     *   <li>对每个父块，执行子块流水线（buildChildSeedList）：在父块内部再切分为更小的子块</li>
     *   <li>组装为 ParentBlockCandidate 列表（每个父块携带其子块列表）</li>
     * </ol>
     *
     * @param document   文档实体
     * @param plan       已确认的策略方案
     * @param steps      方案下的步骤列表（已排序）
     * @param parsedText 清洗后的全文纯文本
     * @return 父块候选列表（每个父块包含多个子块），即可入库的切块结果
     */
    @Override
    public List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                        SuperAgentDocumentStrategyPlan plan,
                                                        List<SuperAgentDocumentStrategyStep> steps,
                                                        String parsedText) {

        // ── Step 1: 拆分 PARENT 和 CHILD 步骤 ─────────────────────
        // 按 pipelineType 过滤 + 按 stepNo 排序
        List<SuperAgentDocumentStrategyStep> parentSteps =
            sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.PARENT);
        List<SuperAgentDocumentStrategyStep> childSteps =
            sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.CHILD);

        // 父块/子块步骤不能为空 — 没有切块策略就没法生成切块
        if (parentSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少父块流水线，无法生成 Parent-Child 结构。");
        }
        if (childSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少子块流水线，无法生成 Parent-Child 结构。");
        }

        // ── Step 2: 查询文档的结构节点 ────────────────────────────
        // 用于 STRUCTURE 策略：按章节边界切分
        List<SuperAgentDocumentStructureNode> structureNodes =
            structureNodeService.listDocumentNodes(
                document == null ? null : document.getId(),
                document == null ? null : document.getLastParseTaskId());

        // ── Step 3: 执行父块流水线 ────────────────────────────────
        // 输入：全文纯文本 → 输出：若干父块种子
        List<ChunkCandidate> parentSeedList =
            buildParentSeedList(parsedText, parentSteps, structureNodes);

        // ── Step 4: 对每个父块，执行子块流水线 ─────────────────────
        // 每个父块内部按子块策略再次切分，形成 Parent-Child 双层结构
        List<ParentBlockCandidate> parentBlockList = new ArrayList<>();
        for (ChunkCandidate parentSeed : cleanupChunkList(parentSeedList)) {
            // 跳过空父块
            if (parentSeed == null || StrUtil.isBlank(parentSeed.getText())) {
                continue;
            }

            // 对当前父块执行子块流水线
            List<ChunkCandidate> childSeedList =
                buildChildSeedList(parentSeed, childSteps, structureNodes);

            // 清理：去重、过滤空白
            List<ChunkCandidate> finalChildren = cleanupChunkList(childSeedList);

            // 防退化：如果子块被全部过滤掉（文本太短全部空白），
            // 将父块本身作为唯一的子块保底，保证检索建索引不会失败
            if (finalChildren.isEmpty()) {
                finalChildren = List.of(cloneChunkCandidate(parentSeed, parentSeed.getText().trim()));
            }

            // 组装为 ParentBlockCandidate
            parentBlockList.add(new ParentBlockCandidate(
                parentSeed.getSectionPath(),              // 章节路径
                parentSeed.getStructureNodeId(),          // 结构节点 ID
                parentSeed.getStructureNodeType(),        // 结构节点类型
                parentSeed.getCanonicalPath(),            // 规范路径
                parentSeed.getItemIndex(),                // 列表项序号
                parentSeed.getText().trim(),              // 父块正文
                parentSeed.getSourceType(),               // 来源类型
                finalChildren                             // 子块列表
            ));
        }

        // ── Step 5: 最终清理（去重）后返回 ─────────────────────────
        return cleanupParentBlockList(parentBlockList);
    }

    // ═══════════════════════════════════════════════════════════
    //  父块种子构建
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建父块种子列表 — buildParentBlocks 的第 3 步。
     * <p>
     * 执行逻辑：
     * <ul>
     *   <li>如果方案包含 STRUCTURE 策略且结构节点非空：
     *       先按结构节点切分（buildStructureParentSeeds），
     *       再对结构切分结果执行剩余策略（如 RECURSIVE 控制长度）。</li>
     *   <li>如果不包含 STRUCTURE 策略：
     *       直接将全文作为一个种子，执行全部父块策略。</li>
     * </ul>
     */
    private List<ChunkCandidate> buildParentSeedList(
            String parsedText,
            List<SuperAgentDocumentStrategyStep> parentSteps,
            List<SuperAgentDocumentStructureNode> structureNodes) {

        // ── 分支 A：包含 STRUCTURE 策略且有结构节点 ────────────────
        if (containsStructureStep(parentSteps)
                && structureNodes != null
                && !structureNodes.isEmpty()) {

            // 按章节边界切分为结构种子
            List<ChunkCandidate> structureSeeds = buildStructureParentSeeds(structureNodes);

            // 没有产出结构种子（所有章节都无内容）→ 回退到纯文本策略
            if (structureSeeds.isEmpty()) {
                return executePipeline(
                    List.of(new ChunkCandidate("", parsedText,
                        DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
                    parentSteps,
                    DocumentStrategyPipelineTypeEnum.PARENT);
            }

            // 移除 STRUCTURE 步骤（已执行完），剩余步骤继续处理
            List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(parentSteps);

            // 没有剩余步骤 → 直接返回结构种子
            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }

            // 有剩余步骤（如 RECURSIVE 进一步控制长度）→ 执行管线
            return executePipeline(structureSeeds, remainingSteps,
                DocumentStrategyPipelineTypeEnum.PARENT);
        }

        // ── 分支 B：不含 STRUCTURE 策略 → 直接从全文开始执行管线 ──
        return executePipeline(
            List.of(new ChunkCandidate("", parsedText,
                DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
            parentSteps,
            DocumentStrategyPipelineTypeEnum.PARENT);
    }

    /**
     * 构建子块种子列表 — buildParentBlocks 的第 4 步。
     * <p>
     * 在单个父块内部按子块策略进一步切分。
     * 逻辑与父块类似：如果有 STRUCTURE 策略且父块关联了结构节点，
     * 则按章节子节点切分，再执行剩余策略；否则直接对父块全文执行子块管线。
     */
    private List<ChunkCandidate> buildChildSeedList(
            ChunkCandidate parentSeed,
            List<SuperAgentDocumentStrategyStep> childSteps,
            List<SuperAgentDocumentStructureNode> structureNodes) {

        // ── 分支 A：包含 STRUCTURE 策略且有结构节点 ────────────────
        if (containsStructureStep(childSteps)
                && parentSeed != null
                && parentSeed.getStructureNodeId() != null
                && structureNodes != null
                && !structureNodes.isEmpty()) {

            // 按父块下的子结构节点切分（如小标题、步骤）
            List<ChunkCandidate> structureSeeds =
                buildStructureChildSeeds(parentSeed, structureNodes);
            List<SuperAgentDocumentStrategyStep> remainingSteps =
                stripStructureSteps(childSteps);

            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }
            return executePipeline(structureSeeds, remainingSteps,
                DocumentStrategyPipelineTypeEnum.CHILD);
        }

        // ── 分支 B：不含 STRUCTURE 策略 → 直接对父块全文执行子块管线 ──
        return executePipeline(
            List.of(cloneChunkCandidate(parentSeed, parentSeed.getText())),
            childSteps,
            DocumentStrategyPipelineTypeEnum.CHILD);
    }

    /** 判断步骤列表是否包含 STRUCTURE 策略。 */
    private boolean containsStructureStep(List<SuperAgentDocumentStrategyStep> steps) {
        return steps != null
            && steps.stream().anyMatch(step ->
                DocumentStrategyTypeEnum.STRUCTURE.getCode().equals(step.getStrategyType()));
    }

    /** 从步骤列表中移除 STRUCTURE 类型的步骤（已执行完）。 */
    private List<SuperAgentDocumentStrategyStep> stripStructureSteps(
            List<SuperAgentDocumentStrategyStep> steps) {
        return steps == null ? List.of() : steps.stream()
            .filter(step -> !DocumentStrategyTypeEnum.STRUCTURE.getCode()
                .equals(step.getStrategyType()))
            .toList();
    }

    // ═══════════════════════════════════════════════════════════
    //  结构切块（STRUCTURE）
    // ═══════════════════════════════════════════════════════════

    /**
     * 从结构节点列表中构建父块种子 — 按章节边界切分。
     * <p>
     * 只选择"有正文内容"的章节节点作为种子：
     * <ul>
     *   <li>叶子章节（无子章节）→ 直接作为父块</li>
     *   <li>有子章节的父章节 → 仅当自身有独立正文内容时才作为父块
     *       （即标题下方有正文，而非只有子标题）</li>
     * </ul>
     */
    private List<ChunkCandidate> buildStructureParentSeeds(
            List<SuperAgentDocumentStructureNode> structureNodes) {

        // ── Step 1: 建立"父节点是否有子章节"的索引 ────────────────
        // 用于 isContentBearingSection 判断：如果父节点有子章节且自身无正文，则跳过
        Map<Long, Boolean> parentHasChildSection = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || node.getParentNodeId() == null) {
                continue;
            }
            if (DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())) {
                parentHasChildSection.put(node.getParentNodeId(), true);
            }
        }

        // ── Step 2: 筛选有正文内容的章节节点 → 转为 ChunkCandidate ──
        List<ChunkCandidate> seeds = new ArrayList<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            // 只处理章节节点（DOCUMENT 根节点不参与切块）
            if (node == null
                || !DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())) {
                continue;
            }
            // 跳过无正文内容的章节（如只有子标题没有正文的"空壳"章节）
            if (!isContentBearingSection(
                    node, parentHasChildSection.getOrDefault(node.getId(), false))) {
                continue;
            }
            seeds.add(toChunkCandidate(node));
        }
        return seeds;
    }

    /**
     * 在父块内部按子结构节点做精细切分 — 用于子块流水线的 STRUCTURE 策略。
     * <p>
     * 以父块的 structureNodeId 查找其下所有子节点（子标题/步骤/列表项），
     * 将每个子节点作为独立的子块种子。
     * <p>
     * 如果父块下没有子节点（退化情况），则返回父块全文作为唯一的子块。
     */
    private List<ChunkCandidate> buildStructureChildSeeds(
            ChunkCandidate parentSeed,
            List<SuperAgentDocumentStructureNode> structureNodes) {

        // ── Step 1: 建立父子节点索引 ──────────────────────────────
        Map<Long, List<SuperAgentDocumentStructureNode>> childrenByParent = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || node.getParentNodeId() == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(node.getParentNodeId(),
                ignored -> new ArrayList<>()).add(node);
        }

        // ── Step 2: 提取父块下的直接子节点 → 转为 ChunkCandidate ─────
        List<ChunkCandidate> seeds = new ArrayList<>();
        for (SuperAgentDocumentStructureNode child :
                childrenByParent.getOrDefault(parentSeed.getStructureNodeId(), List.of())) {
            // 跳过空内容节点
            if (child == null || StrUtil.isBlank(child.getContentText())) {
                continue;
            }
            // 只取章节、步骤、列表项三种节点（纯正文段落不作为子块拆分点）
            DocumentStructureNodeTypeEnum nodeType =
                DocumentStructureNodeTypeEnum.getRc(child.getNodeType());
            if (nodeType == DocumentStructureNodeTypeEnum.SECTION
                || nodeType == DocumentStructureNodeTypeEnum.STEP
                || nodeType == DocumentStructureNodeTypeEnum.LIST_ITEM) {
                seeds.add(toChunkCandidate(child));
            }
        }

        // ── Step 3: 退化保护 ──────────────────────────────────────
        // 没有子节点 → 返回父块全文作为唯一的子块
        if (!seeds.isEmpty()) {
            return seeds;
        }
        return List.of(cloneChunkCandidate(parentSeed, parentSeed.getText()));
    }

    /**
     * 判断一个章节节点是否"承载了正文内容"。
     * <p>
     * 条件：
     * <ul>
     *   <li>节点非空且 contentText 非空 → 候选</li>
     *   <li>无子章节 → 直接算有内容</li>
     *   <li>有子章节 → 正文内容不能只是标题本身的重复（如标题="背景"，正文="背景"）</li>
     * </ul>
     * <p>
     * 为什么要做这个判断？
     * 一个章节在章节树中既是父标题又是正文节点。如果它只有子标题（子节点）而没有自己的正文，
     * 那么它不应该成为父块——否则父块内容与子块重叠，影响去重和检索精度。
     */
    private boolean isContentBearingSection(
            SuperAgentDocumentStructureNode node, boolean hasChildSection) {
        // 空节点或空内容 → 不承载内容
        if (node == null || StrUtil.isBlank(node.getContentText())) {
            return false;
        }
        String content = node.getContentText().trim();

        // 没有子章节 → 肯定是内容节点
        if (!hasChildSection) {
            return true;
        }

        // 有子章节：检查正文是否只是标题的重复
        // 例如：标题="1.1 背景"，正文只有"1.1 背景"（没有其他文字）→ 无内容
        String headingText =
            StrUtil.blankToDefault(node.getAnchorText(), node.getTitle()).trim();
        if (content.equals(headingText)) {
            return false;  // 正文只有标题本身 → 无有效内容
        }

        // 正文长度明显大于标题，或包含换行（多个段落）→ 有内容
        return content.length() > headingText.length() + 16 || content.contains("\n");
    }

    /**
     * 将结构节点（SuperAgentDocumentStructureNode）转换为切块候选（ChunkCandidate）。
     * 转换只取结构节点中的路径、内容、类型信息，丢掉数据库无关字段。
     */
    private ChunkCandidate toChunkCandidate(SuperAgentDocumentStructureNode node) {
        return new ChunkCandidate(
            node.getSectionPath(),                                      // 章节路径
            node.getId(),                                               // 结构节点 ID（作为 structureNodeId）
            node.getNodeType(),                                         // 结构节点类型
            StrUtil.blankToDefault(node.getCanonicalPath(), ""),       // 规范路径
            node.getItemIndex(),                                        // 列表项序号
            node.getContentText(),                                      // 正文内容
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode()              // 来源=原文
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  草稿与快照构建
    // ═══════════════════════════════════════════════════════════

    /**
     * 将策略类型列表构建为策略步骤草稿列表 — recommendStrategy 的第 5 步。
     * <p>
     * 为每个策略类型分配角色（role）：
     * <ul>
     *   <li>第一个步骤 → PRIMARY（主策略）</li>
     *   <li>RECURSIVE → FALLBACK（兜底）</li>
     *   <li>SEMANTIC → OPTIMIZE（优化）</li>
     *   <li>LLM → ENHANCE（增强）</li>
     * </ul>
     *
     * @param pipelineType  管线类型（PARENT/CHILD）
     * @param strategyTypes 策略类型列表（有序，按执行顺序排列）
     * @param reasonMap     策略类型→推荐理由的映射
     * @return 策略步骤草稿列表
     */
    private List<DocumentStrategyStepDraft> buildDraftSteps(
            DocumentStrategyPipelineTypeEnum pipelineType,
            List<Integer> strategyTypes,
            Map<Integer, String> reasonMap) {

        List<DocumentStrategyStepDraft> draftList = new ArrayList<>();
        for (int index = 0; index < strategyTypes.size(); index++) {
            Integer strategyType = strategyTypes.get(index);
            draftList.add(new DocumentStrategyStepDraft(
                pipelineType.getCode(),                                         // 管线类型
                strategyType,                                                   // 策略类型
                resolveRole(index, strategyType),                               // 角色
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),      // 来源=系统推荐
                reasonMap.getOrDefault(strategyType, "系统为当前流水线生成的推荐步骤。")  // 推荐理由
            ));
        }
        return draftList;
    }

    // ═══════════════════════════════════════════════════════════
    //  策略类型规范化（去重）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对前端传入的策略类型列表做规范化处理（去重+有效验证）。
     * 使用 LinkedHashSet 保留插入顺序同时去重。
     */
    private List<Integer> normalizePipelineTypes(List<Integer> requestStrategyTypes) {
        LinkedHashSet<Integer> requestTypeSet = new LinkedHashSet<>();
        for (Integer strategyType :
                requestStrategyTypes == null ? List.<Integer>of() : requestStrategyTypes) {
            // 只保留有效的策略类型枚举值
            if (DocumentStrategyTypeEnum.getRc(strategyType) != null) {
                requestTypeSet.add(strategyType);
            }
        }
        return new ArrayList<>(requestTypeSet);
    }

    /**
     * 构建规范化后的数据库步骤实体 — normalizeSteps 的子方法。
     * <p>
     * 将规范化的策略类型列表转为 SuperAgentDocumentStrategyStep 数据库实体，
     * 同时判断每个步骤是"用户保留原有步骤"还是"用户新增步骤"。
     *
     * @param pipelineType    管线类型
     * @param normalizedTypes 规范化后的策略类型列表
     * @param baseStepMap     基础方案中该管线类型的步骤映射（strategyType → step）
     * @param documentId      文档 ID
     * @return 数据库步骤实体列表
     */
    private List<SuperAgentDocumentStrategyStep> buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum pipelineType,
            List<Integer> normalizedTypes,
            Map<Integer, SuperAgentDocumentStrategyStep> baseStepMap,
            Long documentId) {

        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();
        for (int index = 0; index < normalizedTypes.size(); index++) {
            Integer strategyType = normalizedTypes.get(index);
            SuperAgentDocumentStrategyStep baseStep = baseStepMap.get(strategyType);

            SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
            step.setDocumentId(documentId);                            // 文档 ID
            step.setPipelineType(pipelineType.getCode());              // 管线类型
            step.setStepNo(index + 1);                                 // 步骤序号（从 1 开始）
            step.setStrategyType(strategyType);                        // 策略类型
            step.setStrategyRole(resolveRole(index, strategyType));    // 角色

            // 来源判断：基础方案中有此步骤 → USER_KEEP（用户保留）
            //          基础方案中无此步骤 → USER_ADD（用户新增）
            step.setSourceType(baseStep == null
                ? DocumentStrategySourceTypeEnum.USER_ADD.getCode()
                : DocumentStrategySourceTypeEnum.USER_KEEP.getCode());

            step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());  // 待执行
            step.setRecommendReason(baseStep == null
                ? "用户手动追加该策略。"
                : baseStep.getRecommendReason());

            normalizedStepList.add(step);
        }
        return normalizedStepList;
    }

    // ═══════════════════════════════════════════════════════════
    //  流水线步骤排序
    // ═══════════════════════════════════════════════════════════

    /**
     * 按管线类型过滤并排序步骤 — 从步骤列表中提取指定管线类型的步骤。
     *
     * @param steps        步骤列表
     * @param pipelineType 管线类型
     * @return 按 stepNo 升序排列的步骤列表
     */
    private List<SuperAgentDocumentStrategyStep> sortPipelineSteps(
            List<SuperAgentDocumentStrategyStep> steps,
            DocumentStrategyPipelineTypeEnum pipelineType) {
        return steps.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(),
                    DocumentStrategyPipelineTypeEnum.CHILD.getCode())))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .toList();
    }

    // ═══════════════════════════════════════════════════════════
    //  管线执行引擎（executePipeline）— 按顺序执行策略
    // ═══════════════════════════════════════════════════════════

    /**
     * 按顺序执行策略管线 — 切块流水线的核心引擎。
     * <p>
     * 输入一个 ChunkCandidate 列表，每个策略依次处理：
     * 每个策略接收上一步的输出作为输入，处理后将结果传给下一步。
     * <p>
     * 例如管线 [STRUCTURE → RECURSIVE]：
     * 全文 → STRUCTURE（按章节切为 5 块）→ RECURSIVE（对超长的块继续切）→ 最终结果
     *
     * @param sourceList    输入的切块候选列表
     * @param orderedSteps  按 stepNo 排序的策略步骤
     * @param pipelineType  管线类型（决定不同策略的阈值参数）
     * @return 管线执行后的最终切块候选列表
     */
    private List<ChunkCandidate> executePipeline(
            List<ChunkCandidate> sourceList,
            List<SuperAgentDocumentStrategyStep> orderedSteps,
            DocumentStrategyPipelineTypeEnum pipelineType) {

        // 初始清理：输入去重、过滤空白
        List<ChunkCandidate> currentChunks = cleanupChunkList(sourceList);

        // 按顺序执行每个策略
        for (SuperAgentDocumentStrategyStep step : orderedSteps) {
            DocumentStrategyTypeEnum strategyType =
                DocumentStrategyTypeEnum.getRc(step.getStrategyType());
            if (strategyType == null) {
                continue;  // 未知策略类型 → 跳过
            }

            // 根据策略类型分发到具体的切块方法
            currentChunks = switch (strategyType) {
                case STRUCTURE -> applyStructureChunking(currentChunks, pipelineType);
                case RECURSIVE -> applyRecursiveChunking(currentChunks, pipelineType);
                case SEMANTIC -> applySemanticChunking(currentChunks, pipelineType);
                case LLM -> applyLlmChunking(currentChunks, pipelineType);
            };

            // 每步之后清理：去重、过滤空白
            currentChunks = cleanupChunkList(currentChunks);
        }

        return cleanupChunkList(currentChunks);
    }

    // ═══════════════════════════════════════════════════════════
    //  策略快照构建
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建组合策略快照字符串 — 格式 "PARENT:类型码;CHILD:类型码"。
     * 类型码对应 DocumentStrategyTypeEnum 中的 code 值：
     * 1=RECURSIVE, 2=STRUCTURE, 3=SEMANTIC, 4=LLM
     */
    private String buildCombinedStrategySnapshot(
            List<DocumentStrategyStepDraft> parentSteps,
            List<DocumentStrategyStepDraft> childSteps) {
        String parentSnapshot = buildPipelineSnapshot(parentSteps.stream()
            .map(DocumentStrategyStepDraft::getStrategyType).toList());
        String childSnapshot = buildPipelineSnapshot(childSteps.stream()
            .map(DocumentStrategyStepDraft::getStrategyType).toList());
        return "PARENT:" + parentSnapshot + ";CHILD:" + childSnapshot;
    }

    /** 构建单条管线快照 — 策略类型编码用逗号拼接。 */
    private String buildPipelineSnapshot(List<Integer> strategyTypes) {
        return strategyTypes.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    }

    // ═══════════════════════════════════════════════════════════
    //  四项策略适用性判断
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断是否应使用结构感知切块（STRUCTURE）。
     * <p>
     * 条件：
     * <ol>
     *   <li>文件类型适合提取结构：PDF/DOC/DOCX/MD/HTML（富文本格式）</li>
     *   <li>且 结构等级 ≥ MEDIUM（标题 ≥ 2 个）</li>
     * </ol>
     * TXT 文件即使内容有标题结构也无法可靠识别结构，不推荐。
     */
    private boolean shouldUseStructure(
            DocumentFileTypeEnum fileType, DocumentAnalysisResult analysisResult) {
        // 文件类型是否适合结构识别（富文本格式才有可靠的标题元数据）
        boolean suitableType = fileType == DocumentFileTypeEnum.PDF
            || fileType == DocumentFileTypeEnum.DOC
            || fileType == DocumentFileTypeEnum.DOCX
            || fileType == DocumentFileTypeEnum.MD
            || fileType == DocumentFileTypeEnum.HTML;

        // 结构等级足够（标题≥2 或 structureLevel ≥ MEDIUM）
        return suitableType
            && (analysisResult.getStructureLevel() >= DocumentStructureLevelEnum.MEDIUM.getCode()
                || analysisResult.getHeadingCount() >= 2);
    }

    /**
     * 判断是否应使用递归切块（RECURSIVE）作为兜底。
     * <p>
     * 条件（任一满足）：
     * <ul>
     *   <li>全文长度超过递归切块 maxChars</li>
     *   <li>存在超长段落（超过递归切块 maxChars）</li>
     * </ul>
     * 超长文本必须经过递归切块控制长度，否则无法入库和检索。
     */
    private boolean shouldUseRecursive(DocumentAnalysisResult analysisResult) {
        return analysisResult.getCharCount() >= properties.getChunk().getRecursiveMaxChars()
            || analysisResult.getMaxParagraphLength() >= properties.getChunk().getRecursiveMaxChars();
    }

    /**
     * 判断是否应使用语义切块（SEMANTIC）。
     * <p>
     * 条件（同时满足）：
     * <ul>
     *   <li>内容长度 ≥ semanticMinChars（有足够的文本可切）</li>
     *   <li>段落数 ≥ 3（有多个主题段落可以检测边界）</li>
     *   <li>内容质量 ≥ MEDIUM（乱码率不高，语义距离计算才有意义）</li>
     * </ul>
     */
    private boolean shouldUseSemantic(DocumentAnalysisResult analysisResult) {
        return analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars()
            && analysisResult.getParagraphCount() >= 3
            && analysisResult.getContentQualityLevel()
                >= DocumentContentQualityLevelEnum.MEDIUM.getCode();
    }

    /**
     * 判断是否应推荐 LLM 智能切块（LLM）。
     * <p>
     * 条件（同时满足）：
     * <ul>
     *   <li>配置允许推荐（recommendLlmWhenLowQuality = true）</li>
     *   <li>内容质量为 LOW（乱码多/过短/提取质量差，常规算法不靠谱）</li>
     *   <li>内容有足够长度（≥ semanticMinChars）</li>
     * </ul>
     * LLM 切块成本高、速度慢，只在常规方法不可靠时推荐。
     */
    private boolean shouldUseLlm(DocumentAnalysisResult analysisResult) {
        return Boolean.TRUE.equals(properties.getChunk().getRecommendLlmWhenLowQuality())
            && analysisResult.getContentQualityLevel()
                .equals(DocumentContentQualityLevelEnum.LOW.getCode())
            && analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars();
    }

    // ═══════════════════════════════════════════════════════════
    //  结构切块执行（applyStructureChunking）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对全文直接执行结构切块 — 被 {@link #applyStructureChunking(List, DocumentStrategyPipelineTypeEnum)} 调用。
     * 这里保留了一个单参数的重载，供其他调用点使用（默认 PARENT 管线）。
     */
    private List<ChunkCandidate> applyStructureChunking(String parsedText) {
        return applyStructureChunking(parsedText,
            DocumentStrategyPipelineTypeEnum.PARENT, "",
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
    }

    /**
     * 对 ChunkCandidate 列表执行结构切块 — 每个候选块分别处理。
     * 遍历输入列表，对每块调用基于字符串分析的结构切分。
     */
    private List<ChunkCandidate> applyStructureChunking(
            List<ChunkCandidate> sourceList,
            DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            resultList.addAll(applyStructureChunking(
                candidate.getText(), pipelineType,
                candidate.getSectionPath(), candidate.getSourceType()));
        }
        return resultList;
    }

    /**
     * 核心：对一段文本按行级标题边界执行结构切分。
     * <p>
     * 算法：
     * <ol>
     *   <li>按 \n 逐行扫描</li>
     *   <li>如果当前行被 {@link DocumentLineClassifier} 判定为标题：
     *       <ul>
     *         <li>将累积的上一段内容 flush 为一个切块</li>
     *         <li>根据标题层级管理标题栈（退栈到对应层级）</li>
     *         <li>构建新的 sectionPath</li>
     *       </ul></li>
     *   <li>非标题行 → 累积到当前切块</li>
     * </ol>
     * <p>
     * 如果切分后没有产出任何块（全文无标题），退化到递归切块。
     *
     * @param parsedText       待切分的文本
     * @param pipelineType     管线类型（影响后续递归的阈值）
     * @param baseSectionPath  基础章节路径（用于拼接子章节路径）
     * @param sourceType       来源类型
     * @return 结构切分后的 ChunkCandidate 列表
     */
    private List<ChunkCandidate> applyStructureChunking(
            String parsedText,
            DocumentStrategyPipelineTypeEnum pipelineType,
            String baseSectionPath,
            Integer sourceType) {

        List<ChunkCandidate> candidateList = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();    // 标题栈，用于跟踪当前嵌套层级
        StringBuilder currentChunk = new StringBuilder();   // 累积当前块的内容
        String currentSectionPath = StrUtil.blankToDefault(baseSectionPath, "");

        // ── 逐行扫描 ──
        for (String line : parsedText.split("\n")) {
            String trimmed = line.trim();
            DocumentLineClassifier.LineClassification classification =
                documentLineClassifier.classify(trimmed);

            if (classification.isHeading()) {
                // ── 遇到标题行：flush 前一块 → 推进标题栈 → 开始新块 ──
                flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

                // 标题栈管理：当前标题层级 ≤ 栈顶层级 → 退栈
                while (headingStack.size() >= classification.level()) {
                    headingStack.removeLast();
                }
                headingStack.addLast(classification.title());
                currentSectionPath = composeSectionPath(baseSectionPath,
                    String.join(" > ", headingStack));
                currentChunk.append(trimmed).append('\n');
                continue;
            }

            // ── 非标题行：累积 ──
            currentChunk.append(line).append('\n');
        }
        // 最后一波内容 flush
        flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

        // ── 退化保护：全文中无标题 → 使用递归切块替代 ──
        if (candidateList.isEmpty()) {
            return applyRecursiveChunking(
                List.of(new ChunkCandidate(baseSectionPath, parsedText, sourceType)),
                pipelineType);
        }

        return candidateList;
    }

    // ═══════════════════════════════════════════════════════════
    //  递归切块执行（applyRecursiveChunking）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对 ChunkCandidate 列表执行递归切块（默认 CHILD 管线）。
     */
    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList) {
        return applyRecursiveChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    /**
     * 对 ChunkCandidate 列表执行递归切块 — 按长度逐级递降切分。
     * <p>
     * 对每个超过 maxChars 的块，调用 {@link #recursiveSplit} 做递归切分。
     * 父管线和子管线使用不同的 maxChars/overlap 配置：
     * <ul>
     *   <li>PARENT: maxChars=2200, overlap=180</li>
     *   <li>CHILD: maxChars=800（配置可调）, overlap=120（配置可调）</li>
     * </ul>
     */
    private List<ChunkCandidate> applyRecursiveChunking(
            List<ChunkCandidate> sourceList,
            DocumentStrategyPipelineTypeEnum pipelineType) {

        List<ChunkCandidate> resultList = new ArrayList<>();
        int maxChars = resolveRecursiveMaxChars(pipelineType);
        int overlapChars = resolveRecursiveOverlap(maxChars, pipelineType);

        for (ChunkCandidate candidate : sourceList) {
            List<String> splitTextList = recursiveSplit(
                candidate.getText(), maxChars, overlapChars);
            for (String splitText : splitTextList) {
                resultList.add(cloneChunkCandidate(candidate, splitText));
            }
        }
        return resultList;
    }

    // ═══════════════════════════════════════════════════════════
    //  语义切块执行（applySemanticChunking）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对 ChunkCandidate 列表执行语义切块（默认 CHILD 管线）。
     */
    private List<ChunkCandidate> applySemanticChunking(List<ChunkCandidate> sourceList) {
        return applySemanticChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    /**
     * 对 ChunkCandidate 列表执行语义切块 — 用 Jaccard 相似度检测主题边界。
     * <p>
     * 对每个块的文本长度超过 semanticMinChars 才做语义切分（太短的文本无意义），
     * 否则直接保留原块不变。
     */
    private List<ChunkCandidate> applySemanticChunking(
            List<ChunkCandidate> sourceList,
            DocumentStrategyPipelineTypeEnum pipelineType) {

        List<ChunkCandidate> resultList = new ArrayList<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType);

        for (ChunkCandidate candidate : sourceList) {
            // 文本太短 → 不处理，直接保留原始块
            if (StrUtil.isBlank(candidate.getText())
                    || candidate.getText().length() <= semanticMinChars) {
                resultList.add(candidate);
                continue;
            }

            resultList.addAll(semanticSplit(candidate, pipelineType));
        }
        return resultList;
    }

    // ═══════════════════════════════════════════════════════════
    //  LLM 智能切块执行（applyLlmChunking）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对 ChunkCandidate 列表执行 LLM 智能切块（默认 CHILD 管线）。
     */
    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList) {
        return applyLlmChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    /**
     * 对 ChunkCandidate 列表执行 LLM 智能切块 — 用大模型理解语义后分块。
     * <p>
     * 流程：
     * <ol>
     *   <li>检查 LLM 是否可用，不可用 → 降级到语义切块</li>
     *   <li>对每个块，如果长度超过 llmMaxChars 先做递归切分（降维）</li>
     *   <li>对每个子段调用 llmSplit → 模型返回切分后的块列表（JSON 数组）</li>
     *   <li>LLM 返回为空 → 降级到语义切块</li>
     * </ol>
     * <p>
     * 注意：LLM 切块成本高（每次调用消耗大量 token），
     * 且只在文档质量低（LOW）时才被推荐启用。
     */
    private List<ChunkCandidate> applyLlmChunking(
            List<ChunkCandidate> sourceList,
            DocumentStrategyPipelineTypeEnum pipelineType) {

        // ── 检查 LLM 是否可用 ──
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!Boolean.TRUE.equals(properties.getChunk().getLlmEnabled()) || chatModel == null) {
            // LLM 未启用或不可用 → 降级到语义切块
            return applySemanticChunking(sourceList, pipelineType);
        }

        List<ChunkCandidate> resultList = new ArrayList<>();
        int llmMaxChars = resolveLlmMaxChars(pipelineType);

        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())) {
                continue;
            }

            // 如果文本超长，先递归切分到 LLM 可接受的长度
            List<String> sourceTextList = candidate.getText().length() > llmMaxChars
                ? recursiveSplit(candidate.getText(), llmMaxChars, 0)
                : List.of(candidate.getText());

            // 逐段调用 LLM 切分
            for (String sourceText : sourceTextList) {
                List<String> llmChunkList = llmSplit(chatModel, sourceText);
                if (llmChunkList.isEmpty()) {
                    // LLM 切分失败 → 降级到语义切块
                    resultList.addAll(semanticSplit(
                        cloneChunkCandidate(candidate, sourceText), pipelineType));
                    continue;
                }
                for (String llmChunk : llmChunkList) {
                    resultList.add(cloneChunkCandidate(candidate, llmChunk));
                }
            }
        }
        return resultList;
    }

    // ═══════════════════════════════════════════════════════════
    //  语义切块核心算法（semanticSplit）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对单个 ChunkCandidate 执行语义切块 — 基于 Jaccard 相似度的主题边界检测。
     * <p>
     * === 算法 ===
     * <ol>
     *   <li>将文本按句号 split 为句子列表</li>
     *   <li>逐句扫描，累积当前块的 token 集合</li>
     *   <li>对每个新句子，计算当前块与新句子的 Jaccard 相似度</li>
     *   <li>如果相似度 < 阈值（默认 0.18）且当前块长度 ≥ minChars → 切分</li>
     *   <li>如果累积长度超过 maxChars → 强制切分</li>
     * </ol>
     * <p>
     * Jaccard 相似度 = 交集大小 / 并集大小。
     * 当新句子与当前块的主题词汇高度重合时，相似度高，不分块；
     * 当新句子引入了大量新词汇时，相似度骤降，说明主题切换，应在此时分块。
     *
     * @param candidate    待切分的块
     * @param pipelineType 管线类型（决定 minChars/maxChars 阈值）
     * @return 切分后的块列表
     */
    private List<ChunkCandidate> semanticSplit(
            ChunkCandidate candidate,
            DocumentStrategyPipelineTypeEnum pipelineType) {

        List<ChunkCandidate> resultList = new ArrayList<>();

        // ── Step 1: 按句号切分为句子 ──
        List<String> sentenceList = splitSentences(candidate.getText());

        // 只有一个句子 → 无需切分
        if (sentenceList.size() <= 1) {
            resultList.add(candidate);
            return resultList;
        }

        // ── Step 2: 逐句扫描 ──
        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentTokenSet = new LinkedHashSet<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType);
        int semanticMaxChars = resolveSemanticMaxChars(pipelineType);

        for (String sentence : sentenceList) {
            // 提取新句子的 token 集合（英文单词 + 中文字符）
            Set<String> sentenceTokenSet = extractTokens(sentence);

            // ── 判断是否需要切分 ──
            // 条件 A：累积长度 + 新句子 > maxChars → 超长，强制切
            boolean exceedMaxChars =
                currentChunk.length() + sentence.length() > semanticMaxChars;
            // 条件 B：累积长度 ≥ minChars 且 Jaccard 相似度 < 阈值 → 主题切换，语义切
            double similarity = currentTokenSet.isEmpty()
                ? 1D
                : jaccard(currentTokenSet, sentenceTokenSet);
            boolean semanticBreak = currentChunk.length() >= semanticMinChars
                && similarity < properties.getChunk().getSemanticSimilarityThreshold();

            // ── 执行切分 ──
            if (currentChunk.length() > 0 && (exceedMaxChars || semanticBreak)) {
                resultList.add(cloneChunkCandidate(
                    candidate, currentChunk.toString().trim()));
                currentChunk.setLength(0);          // 清空当前块
                currentTokenSet.clear();            // 清空 token 集合
            }

            // 累积当前句子
            currentChunk.append(sentence);
            currentTokenSet.addAll(sentenceTokenSet);
        }

        // ── Step 3: 最后一波 flush ──
        if (currentChunk.length() > 0) {
            resultList.add(cloneChunkCandidate(
                candidate, currentChunk.toString().trim()));
        }

        return resultList;
    }

    // ═══════════════════════════════════════════════════════════
    //  递归切块核心算法（recursiveSplit）
    // ═══════════════════════════════════════════════════════════

    /**
     * 递归切分文本 — 按"段落 → 行 → 句子 → 固定窗口"逐级递降。
     * <p>
     * 四级递降策略：
     * <ol>
     *   <li><b>按段落切</b>（双换行分隔）：段落列表 → mergeAndSplit 合并</li>
     *   <li><b>按行切</b>（单换行）：行列表 → mergeAndSplit</li>
     *   <li><b>按句子切</b>（句号分隔）：句子列表 → mergeAndSplit</li>
     *   <li><b>固定窗口切</b>：按 maxChars 定长滑动窗口切分，步长 = maxChars - overlap</li>
     * </ol>
     * <p>
     * 每级如果只剩 1 个片段（没有被拆开）就继续降级到更细的粒度。
     * 保证任何长度文本都能被切到 ≤ maxChars。
     *
     * @param text         待切分文本
     * @param maxChars     每块最大字符数
     * @param overlapChars 相邻块重叠字符数
     * @return 切分后的文本列表（所有片段长度 ≤ maxChars）
     */
    private List<String> recursiveSplit(String text, int maxChars, int overlapChars) {
        String trimmed = text == null ? "" : text.trim();
        if (StrUtil.isBlank(trimmed)) {
            return List.of();
        }
        // 已达标 → 直接返回
        if (trimmed.length() <= maxChars) {
            return List.of(trimmed);
        }

        // ── 第 1 级：按段落切 ──
        List<String> paragraphList = splitByRegex(trimmed, "\\n\\s*\\n");
        if (paragraphList.size() > 1) {
            return mergeAndSplit(paragraphList, maxChars, overlapChars);
        }

        // ── 第 2 级：按行切 ──
        List<String> lineList = splitByRegex(trimmed, "\\n");
        if (lineList.size() > 1) {
            return mergeAndSplit(lineList, maxChars, overlapChars);
        }

        // ── 第 3 级：按句子切 ──
        List<String> sentenceList = splitSentences(trimmed);
        if (sentenceList.size() > 1) {
            return mergeAndSplit(sentenceList, maxChars, overlapChars);
        }

        // ── 第 4 级：定长滑动窗口（兜底） ──
        List<String> fixedWindowList = new ArrayList<>();
        int start = 0;
        int step = Math.max(1, maxChars - overlapChars);  // 步长 = maxChars - overlap
        while (start < trimmed.length()) {
            int end = Math.min(trimmed.length(), start + maxChars);
            fixedWindowList.add(trimmed.substring(start, end).trim());
            if (end >= trimmed.length()) {
                break;
            }
            start += step;  // 滑动
        }
        return fixedWindowList;
    }

    // ═══════════════════════════════════════════════════════════
    //  合并与切分 + 重叠处理
    // ═══════════════════════════════════════════════════════════

    /**
     * 将片段列表合并为不超过 maxChars 的块 — recursiveSplit 的子方法。
     * <p>
     * 贪婪合并：从前往后依次合并片段，直到合并后长度超过 maxChars，
     * 则 flush 当前块，开始新块。
     * <p>
     * 如果一个片段本身超过 maxChars，递归调用 recursiveSplit 单独处理。
     * 最后调用 applyOverlap 为相邻块添加重叠。
     */
    private List<String> mergeAndSplit(
            List<String> segmentList, int maxChars, int overlapChars) {

        List<String> rawResultList = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String segment : segmentList) {
            String trimmed = segment.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }

            // 片段本身超长 → 先切分当前累积块，再递归处理该片段
            if (trimmed.length() > maxChars) {
                if (current.length() > 0) {
                    rawResultList.add(current.toString().trim());
                    current.setLength(0);
                }
                rawResultList.addAll(recursiveSplit(trimmed, maxChars, overlapChars));
                continue;
            }

            // 合并后超长 → flush 当前块，新块从当前片段开始
            if (current.length() + trimmed.length() + 1 > maxChars) {
                rawResultList.add(current.toString().trim());
                current.setLength(0);
            }

            current.append(trimmed).append('\n');
        }

        // 最后一波 flush
        if (current.length() > 0) {
            rawResultList.add(current.toString().trim());
        }

        // 添加重叠
        return applyOverlap(rawResultList, maxChars, overlapChars);
    }

    /**
     * 为切分后的块列表添加重叠内容 — 缓解切块边界导致的信息断裂。
     * <p>
     * 除了第一个块，每个块在其前面拼接上一块的尾部字符（最多 overlapChars）。
     * 例如 overlap=120，当前块开头会附加上一块的最后 120 个字符。
     * <p>
     * 这样在检索时，即使切分边界刚好切断了关键信息，重叠区域能"夹住"它。
     */
    private List<String> applyOverlap(
            List<String> rawChunkList, int maxChars, int overlapChars) {
        if (rawChunkList.isEmpty() || overlapChars <= 0) {
            return rawChunkList;  // 无重叠需求 → 直接返回
        }

        List<String> overlappedChunkList = new ArrayList<>(rawChunkList.size());
        for (int index = 0; index < rawChunkList.size(); index++) {
            String current = rawChunkList.get(index);
            if (StrUtil.isBlank(current)) {
                continue;
            }
            if (index == 0) {
                overlappedChunkList.add(current);  // 第一个块不加前缀重叠
                continue;
            }

            String previous = rawChunkList.get(index - 1);
            String overlapPrefix = buildOverlapPrefix(previous, current, maxChars, overlapChars);
            if (StrUtil.isNotBlank(overlapPrefix)) {
                overlappedChunkList.add(overlapPrefix + "\n" + current);
            } else {
                overlappedChunkList.add(current);
            }
        }
        return overlappedChunkList;
    }

    /**
     * 构建重叠前缀 — 取上一块尾部最多 allowedChars 个字符。
     * <p>
     * allowedChars = min(overlapChars, maxChars - current.length - 1)。
     * 确保当前块加上重叠后不超过 maxChars。
     */
    private String buildOverlapPrefix(
            String previous, String current, int maxChars, int overlapChars) {
        if (StrUtil.isBlank(previous) || StrUtil.isBlank(current)) {
            return "";
        }
        // 允许的最大重叠字符数（保证加上后不超 maxChars）
        int allowedChars = Math.min(overlapChars,
            Math.max(0, maxChars - current.length() - 1));
        if (allowedChars <= 0) {
            return "";
        }
        // 取上一块尾部 allowedChars 个字符
        String suffix = previous.length() <= allowedChars
            ? previous
            : previous.substring(previous.length() - allowedChars);
        return suffix.trim();
    }

    // ═══════════════════════════════════════════════════════════
    //  阈值解析（PARENT vs CHILD 有不同的阈值）
    // ═══════════════════════════════════════════════════════════

    /** 解析递归切块的重叠字符数（默认 CHILD 管线） */
    private int resolveRecursiveOverlap(int maxChars) {
        return resolveRecursiveOverlap(maxChars, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    /**
     * 根据管线类型解析递归切块的重叠字符数。
     * <ul>
     *   <li>PARENT：固定 180（硬编码常量）</li>
     *   <li>CHILD：取配置中的 recursiveOverlapChars（默认 120）</li>
     * </ul>
     */
    private int resolveRecursiveOverlap(
            int maxChars, DocumentStrategyPipelineTypeEnum pipelineType) {
        if (pipelineType == DocumentStrategyPipelineTypeEnum.PARENT) {
            return Math.min(PARENT_BLOCK_OVERLAP_CHARS, Math.max(0, maxChars - 1));
        }
        Integer configuredOverlap = properties.getChunk().getRecursiveOverlapChars();
        if (configuredOverlap == null || configuredOverlap <= 0) {
            return 0;
        }
        return Math.min(configuredOverlap, Math.max(0, maxChars - 1));
    }

    /**
     * 根据管线类型解析递归切块的最大字符数。
     * <ul>
     *   <li>PARENT：固定 2200（硬编码常量 PARENT_BLOCK_MAX_CHARS）</li>
     *   <li>CHILD：取配置中的 recursiveMaxChars（默认 800）</li>
     * </ul>
     */
    private int resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? PARENT_BLOCK_MAX_CHARS
            : properties.getChunk().getRecursiveMaxChars();
    }

    /** 解析语义切块的最大字符数 */
    private int resolveSemanticMaxChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(PARENT_SEMANTIC_MAX_CHARS, properties.getChunk().getSemanticMaxChars())
            : properties.getChunk().getSemanticMaxChars();
    }

    /** 解析语义切块的最小字符数（低于此值不做语义切分） */
    private int resolveSemanticMinChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(PARENT_SEMANTIC_MIN_CHARS, properties.getChunk().getSemanticMinChars())
            : properties.getChunk().getSemanticMinChars();
    }

    /** 解析 LLM 切块的最大字符数（超长文本先递归切到此尺寸再送 LLM） */
    private int resolveLlmMaxChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(properties.getChunk().getLlmMaxChars(), PARENT_BLOCK_MAX_CHARS)
            : properties.getChunk().getLlmMaxChars();
    }

    // ═══════════════════════════════════════════════════════════
    //  文本分割工具
    // ═══════════════════════════════════════════════════════════

    /** 按正则分割文本并过滤空白片段。 */
    private List<String> splitByRegex(String text, String regex) {
        return Arrays.stream(text.split(regex))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    /**
     * 按句号分割句子 — 使用零宽断言保留句号。
     * 支持中英文句号、感叹号、问号、分号。
     * 分割后"你好。世界。" → ["你好。", "世界。"]（句号保留在前一句末尾）
     */
    private List<String> splitSentences(String text) {
        // 零宽断言 (?<=...)：在句尾标点之后分割，标点留在前一句
        return Arrays.stream(text.split("(?<=[。！？!?；;\\.])"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    // ═══════════════════════════════════════════════════════════
    //  Token 提取与 Jaccard 相似度
    // ═══════════════════════════════════════════════════════════

    /**
     * 从文本中提取 token 集合 — 用于 Jaccard 相似度计算。
     * <p>
     * 提取策略：
     * <ul>
     *   <li>英文/数字 token：2 个以上连续字母数字（如 "document", "chunk", "v2"）</li>
     *   <li>中文 token：每个独立汉字作为一个 token</li>
     *   <li>标点和单字母词被忽略</li>
     * </ul>
     * 使用 LinkedHashSet 保持 token 提取顺序（调试友好）。
     */
    private Set<String> extractTokens(String text) {
        LinkedHashSet<String> tokenSet = new LinkedHashSet<>();
        // 提取英文/数字词
        Matcher matcher = ENGLISH_WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokenSet.add(matcher.group());
        }
        // 提取中文字符（每个字独立）
        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                tokenSet.add(String.valueOf(current));
            }
        }
        return tokenSet;
    }

    /**
     * 计算两个集合的 Jaccard 相似度 — 用于语义切块的主题边界检测。
     * <p>
     * J(A, B) = |A ∩ B| / |A ∪ B|
     * <p>
     * 范围 [0, 1]：
     * <ul>
     *   <li>1.0 → 完全相同（主题未变）</li>
     *   <li>0.0 → 完全不同（主题切换）</li>
     * </ul>
     * 阈值默认 0.18：低于此值表示新句子引入了大量新词，主题已变化，应切分。
     * 如果任一集合为空，返回 0（无法比较 → 保守切分）。
     */
    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0D;
        }
        // 并集
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        // 交集
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return union.isEmpty() ? 0D : (double) intersection.size() / (double) union.size();
    }

    // ═══════════════════════════════════════════════════════════
    //  LLM 切块调用
    // ═══════════════════════════════════════════════════════════

    /**
     * 调用 LLM 对文本做语义切块。
     * <p>
     * 使用 PromptTemplateService 渲染切块提示词，将文本发送给 ChatModel，
     * 期望模型返回格式如：["段落1","段落2","段落3"] 的 JSON 数组。
     * <p>
     * 任何异常（网络超时、JSON 解析失败、模型返回格式不对）都回退到空列表，
     * 由调用方降级到语义切块。
     *
     * @param chatModel  聊天模型
     * @param sourceText 待切分的源文本
     * @return 切分后的文本列表（可能为空）
     */
    private List<String> llmSplit(ChatModel chatModel, String sourceText) {
        // 渲染提示词
        String prompt = promptTemplateService.render(
            PromptTemplateNames.DOCUMENT_LLM_SPLIT, Map.of(
                "sourceText", StrUtil.blankToDefault(sourceText, "")));

        try {
            // 调用模型
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .content();

            if (StrUtil.isBlank(content)) {
                return List.of();
            }
            // 从回复中提取 JSON 数组
            String jsonArray = extractJsonArray(content);
            if (StrUtil.isBlank(jsonArray)) {
                return List.of();
            }

            // 解析 JSON 数组
            List<String> resultList = objectMapper.readValue(
                jsonArray, new TypeReference<List<String>>() {});
            return resultList.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .toList();
        } catch (Exception exception) {
            log.warn("大模型智能切块失败，回退到语义切块", exception);
            return List.of();
        }
    }

    /** 从模型回复文本中提取 JSON 数组部分（模型经常用额外文本包裹 JSON）。 */
    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    // ═══════════════════════════════════════════════════════════
    //  清理与克隆工具
    // ═══════════════════════════════════════════════════════════

    /**
     * 清理 ChunkCandidate 列表：过滤空白 + 去重。
     * <p>
     * 去重 key = canonicalPath + "||" + itemIndex + "||" + text。
     * 相同路径内容一样的块只保留第一个。
     */
    private List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> sourceList) {
        Map<String, ChunkCandidate> uniqueMap = new LinkedHashMap<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();
            // 生成去重 key
            String uniqueKey = StrUtil.blankToDefault(
                    candidate.getCanonicalPath(), candidate.getSectionPath())
                + "||" + candidate.getItemIndex()
                + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey,
                cloneChunkCandidate(candidate, normalizedText));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    /** 清理 ParentBlockCandidate 列表：过滤空白 + 去重。逻辑同 cleanupChunkList。 */
    private List<ParentBlockCandidate> cleanupParentBlockList(
            List<ParentBlockCandidate> sourceList) {
        Map<String, ParentBlockCandidate> uniqueMap = new LinkedHashMap<>();
        for (ParentBlockCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();
            String uniqueKey = StrUtil.blankToDefault(
                    candidate.getCanonicalPath(), candidate.getSectionPath())
                + "||" + candidate.getItemIndex()
                + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey, cloneParentBlockCandidate(
                candidate, normalizedText,
                candidate.getChildChunks() == null
                    ? List.of()
                    : new ArrayList<>(candidate.getChildChunks())));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    /**
     * Flush 当前累积的切块内容 — 将 StringBuilder 的内容转为 ChunkCandidate 并加入列表。
     * 清空 StringBuilder 供下一块使用。
     */
    private void flushChunk(
            List<ChunkCandidate> candidateList,
            String currentSectionPath,
            Integer sourceType,
            StringBuilder currentChunk) {
        String text = currentChunk.toString().trim();
        if (StrUtil.isNotBlank(text)) {
            candidateList.add(new ChunkCandidate(
                currentSectionPath,                                         // 章节路径
                null,                                                       // structureNodeId=null（非结构切块）
                null,                                                       // structureNodeType=null
                "",                                                         // canonicalPath=空
                null,                                                       // itemIndex=null
                text,                                                       // 正文内容
                sourceType == null
                    ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode()
                    : sourceType                                            // 来源类型
            ));
        }
        currentChunk.setLength(0);  // 清空
    }

    /**
     * 克隆 ChunkCandidate — 保留源块的元数据，替换文本内容。
     */
    private ChunkCandidate cloneChunkCandidate(ChunkCandidate source, String text) {
        if (source == null) {
            return new ChunkCandidate("", text,
                DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
        }
        return new ChunkCandidate(
            source.getSectionPath(),            // 章节路径（复用）
            source.getStructureNodeId(),        // 结构节点 ID（复用）
            source.getStructureNodeType(),      // 结构节点类型（复用）
            StrUtil.blankToDefault(source.getCanonicalPath(), ""),  // 规范路径（复用）
            source.getItemIndex(),              // 列表项序号（复用）
            text,                               // 新文本内容
            source.getSourceType()              // 来源类型（复用）
        );
    }

    /**
     * 克隆 ParentBlockCandidate — 保留源块的元数据，替换文本和子块列表。
     */
    private ParentBlockCandidate cloneParentBlockCandidate(
            ParentBlockCandidate source, String text, List<ChunkCandidate> childChunks) {
        if (source == null) {
            return new ParentBlockCandidate("", text,
                DocumentChunkSourceTypeEnum.ORIGINAL.getCode(), childChunks);
        }
        return new ParentBlockCandidate(
            source.getSectionPath(),
            source.getStructureNodeId(),
            source.getStructureNodeType(),
            StrUtil.blankToDefault(source.getCanonicalPath(), ""),
            source.getItemIndex(),
            text,
            source.getSourceType(),
            childChunks
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  通用工具方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 拼接章节路径 — 如 "第一章" + " > 第一节" → "第一章 > 第一节"。
     * 如果 baseSectionPath 为空，直接返回 currentSectionPath。
     */
    private String composeSectionPath(String baseSectionPath, String currentSectionPath) {
        String normalizedBase = StrUtil.blankToDefault(baseSectionPath, "").trim();
        String normalizedCurrent = StrUtil.blankToDefault(currentSectionPath, "").trim();
        if (StrUtil.isBlank(normalizedBase)) {
            return normalizedCurrent;
        }
        if (StrUtil.isBlank(normalizedCurrent)) {
            return normalizedBase;
        }
        return normalizedBase + " > " + normalizedCurrent;
    }

    /**
     * 根据步骤索引和策略类型解析角色。
     * <ul>
     *   <li>第一个步骤（index=0）→ PRIMARY（主策略）</li>
     *   <li>RECURSIVE → FALLBACK（兜底）</li>
     *   <li>SEMANTIC → OPTIMIZE（优化）</li>
     *   <li>LLM → ENHANCE（增强）</li>
     *   <li>其他 → OPTIMIZE（优化）</li>
     * </ul>
     */
    private Integer resolveRole(int index, Integer strategyType) {
        if (index == 0) {
            return DocumentStrategyRoleEnum.PRIMARY.getCode();      // 第一个步骤=主策略
        }
        if (DocumentStrategyTypeEnum.RECURSIVE.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.FALLBACK.getCode();     // 递归=兜底
        }
        if (DocumentStrategyTypeEnum.SEMANTIC.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.OPTIMIZE.getCode();     // 语义=优化
        }
        if (DocumentStrategyTypeEnum.LLM.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.ENHANCE.getCode();      // LLM=增强
        }
        return DocumentStrategyRoleEnum.OPTIMIZE.getCode();         // 默认=优化
    }
}
