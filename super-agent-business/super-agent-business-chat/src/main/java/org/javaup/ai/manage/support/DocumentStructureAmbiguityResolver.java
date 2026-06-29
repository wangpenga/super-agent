package org.javaup.ai.manage.support;

// ────────────── 导入工具类 ──────────────
import cn.hutool.core.util.StrUtil;                          // Hutool 字符串工具
import com.fasterxml.jackson.core.type.TypeReference;         // Jackson 泛型类型引用
import com.fasterxml.jackson.databind.ObjectMapper;           // Jackson JSON 解析器
import lombok.extern.slf4j.Slf4j;                            // Lombok 日志注解
import org.javaup.ai.manage.config.DocumentManageProperties;  // 应用配置
import org.javaup.ai.prompt.PromptTemplateNames;              // 提示词模板名称常量
import org.javaup.ai.prompt.PromptTemplateService;            // 提示词模板渲染服务
import org.springframework.ai.chat.client.ChatClient;         // Spring AI 聊天客户端
import org.springframework.ai.chat.model.ChatModel;           // Spring AI 聊天模型接口
import org.springframework.beans.factory.ObjectProvider;      // Spring Bean 延迟获取
import org.springframework.stereotype.Component;              // Spring 组件注解

import java.util.ArrayList;          // 动态数组
import java.util.LinkedHashMap;      // 有序哈希表
import java.util.List;               // 列表接口
import java.util.Map;                // 映射接口

/**
 * 文档结构歧义消解器 — 管线第二站。
 * <p>
 * 职责：对 {@link DocumentStructureSignalExtractor} 产出的 HEADING_CANDIDATE 信号
 * （即"看起来可能是标题、但不能确定"的信号）做进一步消歧。
 * <p>
 * === 两种消歧策略 ===
 * <ol>
 *   <li><b>基于配置开关的 LLM 消歧</b>（默认开启）：
 *       将歧义信号连同其上下文窗口打包成提示词，调用大模型判断是否为标题。
 *       模型返回的 JSON 数组格式：[{"line_no": 42, "resolved_kind": "HEADING", "level_hint": 1}, ...]</li>
 *   <li><b>兜底回退</b>（LLM 不可用时）：
 *       直接返回原始信号列表，HEADING_CANDIDATE 会保留原样。
 *       后续层级构建阶段会将 HEADING_CANDIDATE 作为 BODY 处理。</li>
 * </ol>
 * <p>
 * === 设计要点 ===
 * <ul>
 *   <li>只在 {@code structureParsing.llm-disambiguation-enabled=true} 且 ChatModel 可用时才启用 LLM 消歧</li>
 *   <li>只处理置信度在 [ambiguityConfidenceFloor, ambiguityConfidenceCeil] 区间的信号
 *       （默认 [0.45, 0.80]），低于下限的判为正文，高于上限的判为确定标题</li>
 *   <li>每次最多处理 maxAmbiguousSignalsPerCall 个歧义信号（默认 8 个），
 *       避免单次提示词过长导致模型处理不稳定</li>
 *   <li>LLM 调用失败时（超时/解析异常/返回格式错误）静静回退，不影响主线流程</li>
 * </ul>
 *
 * @see DocumentStructureSignalExtractor  上游：信号提取器（产生 HEADING_CANDIDATE）
 * @see DocumentStructureHierarchyResolver 下游：层级构建器（消费消解后的信号）
 */
@Slf4j     // Lombok：生成 log 字段，用于 warn 日志
@Component // 声明为 Spring 组件
public class DocumentStructureAmbiguityResolver {

    /**
     * 应用配置 — 用于读取结构解析相关参数：
     * llmDisambiguationEnabled：是否启用 LLM 消歧
     * maxAmbiguousSignalsPerCall：单次最多处理的歧义信号数
     * contextWindowLines：上下文窗口行数（当前行前后各取 N 行）
     * ambiguityConfidenceFloor/Ceil：歧义信号置信度的上下界
     */
    private final DocumentManageProperties properties;

    /**
     * ChatModel 延迟获取器 — 用于调用 LLM 做消歧判断。
     * 使用 ObjectProvider 而非直接注入 ChatModel，是为了在 ChatModel 不可用时优雅降级。
     */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /**
     * Jackson ObjectMapper — 用于解析 LLM 返回的 JSON 数组字符串。
     */
    private final ObjectMapper objectMapper;

    /**
     * 提示词模板服务 — 用于渲染结构消歧的 LLM 提示词。
     * 模板名称定义在 {@link PromptTemplateNames#DOCUMENT_STRUCTURE_AMBIGUITY}。
     */
    private final PromptTemplateService promptTemplateService;

    /**
     * 构造函数 — Spring 依赖注入。
     *
     * @param properties           应用配置
     * @param chatModelProvider    ChatModel 延迟获取器（可能为 null 以支持降级）
     * @param objectMapper         JSON 解析器
     * @param promptTemplateService 提示词模板服务
     */
    public DocumentStructureAmbiguityResolver(DocumentManageProperties properties,
                                              ObjectProvider<ChatModel> chatModelProvider,
                                              ObjectMapper objectMapper,
                                              PromptTemplateService promptTemplateService) {
        // 保存配置引用（用于读取阈值参数）
        this.properties = properties;
        // 保存 ChatModel 提供器（用于延迟获取 Bean）
        this.chatModelProvider = chatModelProvider;
        // 保存 JSON 解析器（用于解析 LLM 返回）
        this.objectMapper = objectMapper;
        // 保存提示词模板服务（用于渲染提示词）
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 对信号列表中的歧义信号做消歧处理 — 整个管线的第二站。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>检查配置开关和 ChatModel 可用性，任一不满足则直接回退</li>
     *   <li>过滤出置信度在 [floor, ceil] 区间内的 HEADING_CANDIDATE 信号</li>
     *   <li>构造 LLM 提示词（每个歧义信号携带上下文窗口），调用模型</li>
     *   <li>解析模型返回的 JSON 数组，更新对应信号的 kind/levelHint</li>
     *   <li>处理过程中任何异常都做 catch 并回退到原始信号</li>
     * </ol>
     *
     * @param documentTitle 文档标题（用于提示词上下文）
     * @param allLines      全文行列表（元素 = 每行的 normalizedText），下标从 0 开始
     * @param sourceSignals 待处理的信号列表（含 HEADING_CANDIDATE 等）
     * @return 消歧后的信号列表
     *         - 如果配置关闭 / ChatModel 不可用 / 无非歧义信号 → 直接返回 sourceSignals
     *         - 如果 LLM 调用失败 → 回退到 sourceSignals（不阻塞流程）
     *         - 如果 LLM 成功 → 返回合并后的信号列表（HEADING_CANDIDATE 被更新为 HEADING/BODY）
     */
    public List<DocumentStructureSignal> resolve(String documentTitle,
                                                 List<String> allLines,
                                                 List<DocumentStructureSignal> sourceSignals) {

        // ── Step 1: 空保护 ──────────────────────────────────────────
        // 如果信号列表为空或 null，直接返回空列表
        if (sourceSignals == null || sourceSignals.isEmpty()) {
            return List.of();
        }

        // ── Step 2: 检查 LLM 消歧是否启用 ────────────────────────────
        // 从配置中读取 llm-disambiguation-enabled 开关（默认 true）
        if (!Boolean.TRUE.equals(properties.getStructureParsing().getLlmDisambiguationEnabled())) {
            // 配置关闭 → 直接回退，保留原始信号（含 HEADING_CANDIDATE）
            return sourceSignals;
        }

        // ── Step 3: 检查 ChatModel 是否可用 ──────────────────────────
        // 使用 ObjectProvider 延迟获取，避免启动时因没有 ChatModel Bean 而报错
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            // ChatModel 不可用（如没有配置 LLM 密钥）→ 回退
            return sourceSignals;
        }

        // ── Step 4: 过滤歧义信号 ─────────────────────────────────────
        // 筛选条件：
        //   1) 类型为 HEADING_CANDIDATE（isAmbiguous() = true）
        //   2) 置信度 ≥ ambiguityConfidenceFloor（默认 0.45）
        //   3) 置信度 ≤ ambiguityConfidenceCeil（默认 0.80）
        // 低于下限的 → 太不像标题，直接判为正文（在层级构建阶段降级）
        // 高于上限的 → 太像标题，直接判为确定标题（不需要消歧）
        List<DocumentStructureSignal> ambiguousSignals = sourceSignals.stream()
            .filter(signal -> signal != null                            // 非空
                && signal.isAmbiguous()                                 // 是 HEADING_CANDIDATE
                && signal.getConfidence() >= properties.getStructureParsing().getAmbiguityConfidenceFloor()
                && signal.getConfidence() <= properties.getStructureParsing().getAmbiguityConfidenceCeil())
            .limit(Math.max(1, properties.getStructureParsing().getMaxAmbiguousSignalsPerCall()))
            .toList();      // 限制数量，避免提示词过长

        // 如果没有歧义信号 → 直接返回原始信号
        if (ambiguousSignals.isEmpty()) {
            return sourceSignals;
        }

        // ── Step 5: 调用 LLM 做消歧 ──────────────────────────────────
        try {
            // 5a) 构建 LLM 提示词：
            //     每个歧义信号携带其上下文窗口（前后各 N 行），标注初始类型
            String prompt = buildPrompt(documentTitle, ambiguousSignals, allLines);

            // 5b) 调用大模型：
            //     使用 ChatClient 构建对话，传入提示词，获取回复
            String content = ChatClient.builder(chatModel)
                .build()                    // 构建 ChatClient 实例
                .prompt()                   // 开始构建提示词
                .user(prompt)               // 设置用户消息
                .call()                     // 调用模型（同步阻塞）
                .content();                 // 获取模型回复文本

            // 5c) 解析模型返回的 JSON：
            //     期望格式：[{"line_no": 42, "resolved_kind": "HEADING", "level_hint": 1}, ...]
            List<DisambiguationResult> results = parse(content);

            // 如果解析结果为空（模型返回格式不对或不一致）→ 回退
            if (results.isEmpty()) {
                return sourceSignals;
            }

            // 5d) 构建行号→结果的映射（方便后续 O(1) 查找）
            Map<Integer, DisambiguationResult> resultMap = new LinkedHashMap<>();
            for (DisambiguationResult result : results) {
                if (result.lineNo == null) {
                    continue;  // 跳过无行号的结果
                }
                resultMap.put(result.lineNo, result);
            }

            // 5e) 合并结果：
            //     遍历原始信号列表，如果该信号行号在 resultMap 中有对应结果，
            //     则用 LLM 的判断更新信号的 kind 和 levelHint
            List<DocumentStructureSignal> merged = new ArrayList<>(sourceSignals.size());
            for (DocumentStructureSignal signal : sourceSignals) {
                // 查找该信号对应的消歧结果（可能为 null）
                DisambiguationResult resolved = signal == null
                    ? null
                    : resultMap.get(signal.getLineNo());
                // 将消歧结果应用到信号
                merged.add(applyResult(signal, resolved));
            }
            return merged;
        }
        catch (Exception exception) {
            // ── Step 6: 异常兜底 ─────────────────────────────────────
            // 任何异常（网络超时、JSON 解析失败、空指针等）都只记录警告日志
            log.warn("结构歧义判定失败，回退到规则结果: {}", exception.getMessage());
            // 回退到原始信号列表，不中断主线流程
            return sourceSignals;
        }
    }

    /**
     * 构建 LLM 消歧提示词 — resolve 方法的第 5a 步。
     * <p>
     * 使用 PromptTemplateService 渲染两个模板：
     * <ol>
     *   <li>{@link PromptTemplateNames#DOCUMENT_STRUCTURE_AMBIGUITY} — 主提示词框架，
     *       包含文档标题和候选块列表的占位符</li>
     *   <li>{@link PromptTemplateNames#DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE} — 单条候选块模板，
     *       包含行号、上下文窗口、初始类型和标题</li>
     * </ol>
     *
     * @param documentTitle    文档标题
     * @param ambiguousSignals 歧义信号列表（已按置信度过滤）
     * @param allLines         全文行列表（用于提取上下文窗口）
     * @return 渲染后的 LLM 提示词字符串
     */
    private String buildPrompt(String documentTitle,
                               List<DocumentStructureSignal> ambiguousSignals,
                               List<String> allLines) {
        // 渲染主提示词模板，传入文档标题和候选块列表
        return promptTemplateService.render(PromptTemplateNames.DOCUMENT_STRUCTURE_AMBIGUITY, Map.of(
            "documentTitle", StrUtil.blankToDefault(documentTitle, "未命名文档"),
            "candidateBlocks", buildCandidateBlocks(ambiguousSignals, allLines)
        ));
    }

    /**
     * 构建每条歧义信号的上下文窗口块 — buildPrompt 的辅助方法。
     * <p>
     * 每个块包含：
     * <ul>
     *   <li>信号行本身（用 ">>" 标记）</li>
     *   <li>上下文窗口：信号行前后各 N 行（N = contextWindowLines，默认 2）</li>
     *   <li>初始分类信息（kind / title / code）</li>
     * </ul>
     * <p>
     * 例如（contextWindowLines=2）：
     * <pre>
     *   行 20: 项目背景
     * >>行 21: 1. 目标
     *   行 22: 本项目旨在...
     *   - line_no: 21, initial_kind: HEADING_CANDIDATE, initial_title: "目标"
     * </pre>
     *
     * @param ambiguousSignals 歧义信号列表
     * @param allLines         全文行列表
     * @return 所有候选块的渲染文本（块间用双换行分隔）
     */
    private String buildCandidateBlocks(List<DocumentStructureSignal> ambiguousSignals,
                                        List<String> allLines) {
        StringBuilder builder = new StringBuilder();  // 累积所有候选块
        // 安全获取行列表（null→空列表）
        List<String> safeLines = allLines == null ? List.of() : allLines;
        // 从配置读取上下文窗口大小（默认 2 行）
        int contextWindow = Math.max(1, properties.getStructureParsing().getContextWindowLines());

        // 遍历每个歧义信号
        for (DocumentStructureSignal signal : ambiguousSignals) {
            if (signal == null) {
                continue;
            }

            // 计算上下文窗口的行范围
            // allLines 下标从 0 开始，signal.lineNo 从 1 开始，所以需要 -1 转换
            int currentIndex = Math.max(0, signal.getLineNo() - 1);
            int start = Math.max(0, currentIndex - contextWindow);     // 起始行（不越界）
            int end = Math.min(safeLines.size() - 1, currentIndex + contextWindow);  // 结束行

            // 构建上下文文本：逐行拼接，信号行用 ">>" 前缀标记
            StringBuilder contextBuilder = new StringBuilder();
            for (int index = start; index <= end; index++) {
                // 信号行标记 ">>"，上下文行标记 "   "
                contextBuilder.append(index + 1 == signal.getLineNo() ? ">> " : "   ")
                    .append(index + 1)                                    // 行号（从 1 开始）
                    .append(": ")
                    .append(StrUtil.blankToDefault(safeLines.get(index), ""))  // 行内容
                    .append('\n');
            }

            // 渲染单条候选块模板，填充行号、上下文、初始类型等信息
            builder.append(promptTemplateService.render(
                PromptTemplateNames.DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE, Map.of(
                    "lineNo", signal.getLineNo(),
                    "contextLines", contextBuilder.toString().stripTrailing(),
                    "initialKind", signal.getKind() == null ? "" : signal.getKind().name(),
                    "initialTitle", StrUtil.blankToDefault(signal.getTitle(), ""),
                    "initialCode", StrUtil.blankToDefault(signal.getNodeCode(), "")
                )))
                .append("\n\n");  // 块间分隔
        }
        // 返回所有候选块文本（去除末尾空白）
        return builder.toString().trim();
    }

    /**
     * 解析 LLM 返回的 JSON 字符串为消歧结果列表 — resolve 的第 5c 步。
     * <p>
     * LLM 返回格式示例：
     * <pre>
     * [{"line_no": 21, "resolved_kind": "HEADING", "level_hint": 1},
     *  {"line_no": 35, "resolved_kind": "LIST_ITEM", "level_hint": null}]
     * </pre>
     * <p>
     * 解析步骤：
     * <ol>
     *   <li>从模型回复中提取 JSON 数组（查找第一个 [ 和最后一个 ]）</li>
     *   <li>使用 Jackson 将 JSON 数组反序列化为 List&lt;Map&gt;</li>
     *   <li>逐项提取 line_no、resolved_kind、level_hint 字段</li>
     * </ol>
     *
     * @param raw 模型返回的原始文本（可能包含 Markdown 代码块包裹）
     * @return 消歧结果列表
     * @throws Exception 当 JSON 无法解析或格式异常时抛出，由 resolve 方法的 catch 块处理
     */
    private List<DisambiguationResult> parse(String raw) throws Exception {
        // 空文本 → 空结果
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }

        // 提取 JSON 数组部分（模型经常用 ```json ``` 或 其他文本包裹 JSON）
        String normalized = raw.trim();
        int start = normalized.indexOf('[');   // 第一个 [ = JSON 数组开始
        int end = normalized.lastIndexOf(']'); // 最后一个 ] = JSON 数组结束
        if (start < 0 || end <= start) {
            // 找不到合法的 JSON 数组 → 返回空，触发回退
            return List.of();
        }

        // 提取 JSON 数组字符串
        String jsonArray = normalized.substring(start, end + 1);

        // 使用 Jackson 反序列化为 List<Map>
        List<Map<String, Object>> items = objectMapper.readValue(
            jsonArray,
            new TypeReference<List<Map<String, Object>>>() {}  // 泛型类型信息
        );

        // 逐项解析为 DisambiguationResult 记录
        List<DisambiguationResult> results = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            // 提取 line_no（可能是 Integer 或 Long，用 Number 统一处理）
            Integer lineNo = item.get("line_no") instanceof Number number ? number.intValue() : null;
            // 提取 resolved_kind（字符串，如 "HEADING" / "LIST_ITEM" / "BODY"）
            String resolvedKind = item.get("resolved_kind") == null
                ? ""
                : String.valueOf(item.get("resolved_kind")).trim();
            // 提取 level_hint（可能是 Integer 或 Long）
            Integer levelHint = item.get("level_hint") instanceof Number number ? number.intValue() : null;
            // 添加到结果列表
            results.add(new DisambiguationResult(lineNo, resolvedKind, levelHint));
        }
        return results;
    }

    /**
     * 将消歧结果应用到信号 — resolve 的第 5e 步。
     * <p>
     * 应用规则：
     * <ul>
     *   <li>如果消歧结果为 "HEADING" → 将信号 kind 设为 HEADING</li>
     *   <li>如果消歧结果为 "LIST_ITEM" → 将信号 kind 设为 LIST_ITEM</li>
     *   <li>其他（含 "BODY" 和无法识别的值）→ 将信号 kind 设为 BODY</li>
     *   <li>如果消歧结果还提供了 levelHint（> 0）→ 同时更新信号的 levelHint</li>
     *   <li>在所有被消歧的信号上添加 "llm-disambiguated" 标签，并将置信度提升到 ≥ 0.88</li>
     * </ul>
     *
     * @param source   原始信号
     * @param resolved 消歧结果（可能为 null）
     * @return 更新后的信号。如果 source 或 resolved 为 null，直接返回 source
     */
    private DocumentStructureSignal applyResult(DocumentStructureSignal source,
                                                DisambiguationResult resolved) {
        // 原始信号或消歧结果为 null → 不做修改
        if (source == null || resolved == null || StrUtil.isBlank(resolved.resolvedKind)) {
            return source;
        }

        // 根据 resolved_kind 字符串转为枚举值
        DocumentStructureSignalKind targetKind = switch (resolved.resolvedKind.trim().toUpperCase()) {
            case "HEADING" -> DocumentStructureSignalKind.HEADING;       // 是标题
            case "LIST_ITEM" -> DocumentStructureSignalKind.LIST_ITEM;   // 是列表项
            default -> DocumentStructureSignalKind.BODY;                 // 默认降级为正文
        };

        // 更新信号的类型
        source.setKind(targetKind);

        // 如果判定为标题且提供了合法的层级提示 → 更新 levelHint
        if (targetKind == DocumentStructureSignalKind.HEADING
            && resolved.levelHint != null && resolved.levelHint > 0) {
            source.setLevelHint(resolved.levelHint);
        }

        // 添加 "llm-disambiguated" 标签（标记该信号曾被 LLM 消歧）
        source.getReasons().add("llm-disambiguated");
        // 提升置信度到 ≥ 0.88，表示该分类已经过 LLM 验证，即使 LLM 输出有误也不低于规则置信度
        source.setConfidence(Math.max(source.getConfidence(), 0.88D));

        return source;
    }

    /**
     * LLM 消歧结果记录 — 内部数据结构，表示单条信号的消歧结论。
     *
     * @param lineNo       信号行号（对应 sourceSignals 中的 lineNo）
     * @param resolvedKind LLM 判断的类型（"HEADING" / "LIST_ITEM" / "BODY"）
     * @param levelHint    LLM 建议的层级（仅当 resolvedKind=HEADING 时有效，> 0）
     */
    private record DisambiguationResult(
        Integer lineNo,
        String resolvedKind,
        Integer levelHint
    ) {
    }
}
