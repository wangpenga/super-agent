package org.javaup.ai.chatagent.model.debug;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.enums.ChatQueryMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话调试轨迹 —— 序列化后写入 exchange.debugTraceJson
 * <p>
 * 前端调试面板通过这个 JSON 展示一轮对话的完整执行过程：
 * 问题改写 → 路由决策 → 历史记忆 → 检索 → 工具调用 → LLM 生成。
 *
 * @author 阿星不是程序员
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDebugTrace {

    // ═══════════════════ 基础执行信息 ═══════════════════
    /** 执行模式：REACT_AGENT / RETRIEVAL / CLARIFICATION / GRAPH_ONLY / GRAPH_THEN_EVIDENCE */
    private String executionMode;
    /** 聊天模式：OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT */
    private ChatQueryMode chatMode;

    // ═══════════════════ 问题改写过程 ═══════════════════
    /** 用户原始提问 */
    private String originalQuestion;
    /** LLM 改写后的检索问题 */
    private String rewriteQuestion;
    /** 改写后的子问题列表 */
    @Builder.Default
    private List<String> rewriteSubQuestions = new ArrayList<>();
    /** 最终用于检索的问题（可能经过路由调整，JSON 别名 rewrittenQuestion 兼容旧版本） */
    @JsonAlias("rewrittenQuestion")
    private String retrievalQuestion;
    /** 最终交给 LLM Agent 的问题文本（由 Prompt 模板渲染生成） */
    private String agentQuestion;

    // ═══════════════════ 路由决策 ═══════════════════
    /** 文档路由决策：包含 executionMode、structureAnchor（章节定位）、retrievalPlan（检索计划） */
    private DocumentNavigationDecision navigationDecision;

    // ═══════════════════ 历史记忆上下文 ═══════════════════
    /** 压缩后的历史摘要（给 LLM 看的精炼版） */
    private String historySummary;
    /** 长期摘要文本（覆盖 N 轮之前的对话，由记忆压缩生成） */
    private String longTermSummary;
    /** 近期对话窗口原文（最新几轮的完整问答） */
    private String recentHistoryTranscript;
    /** 近期的助手回答原文 */
    private String answerRecentTranscript;
    /** 回答历史上下文的渲染文本（含上下文结构信息） */
    private String answerHistoryContext;
    /** 当前问题是否为追问（基于上下文判断） */
    private boolean answerHistoryFollowUpQuestion;

    // ═══════════════════ 历史压缩统计 ═══════════════════
    /** 本轮是否应用了历史压缩 */
    private boolean historyCompressionApplied;
    /** 压缩已覆盖到的最后一条 exchangeId */
    private Long historyCoveredExchangeId;
    /** 压缩已覆盖的轮次数 */
    private Integer historyCoveredExchangeCount;
    /** 累计压缩次数 */
    private Integer historyCompressionCount;

    // ═══════════════════ 时间感知 ═══════════════════
    /** 当前日期文本，如 "2026-06-25（星期四）" */
    private String currentDateText;
    /** 是否需要实时搜索（问题含"最新""现在"等词） */
    private boolean requiresFreshSearch;
    /** 是否需要日期锚定（问题含"今天""当前"等词） */
    private boolean requiresCurrentDateAnchoring;

    // ═══════════════════ 检索 ═══════════════════
    /** 检索用子问题列表（JSON 别名 subQuestions 兼容旧版本） */
    @JsonAlias("subQuestions")
    @Builder.Default
    private List<String> retrievalSubQuestions = new ArrayList<>();
    /** 当前锁定的文档 ID */
    private Long selectedDocumentId;
    /** 当前锁定的索引任务 ID */
    private Long selectedTaskId;

    // ═══════════════════ 运行时动态数据（执行过程中填充）═══════════════════
    /** 检索备注（如"走 ReactAgent 执行路径""无证据兜底"） */
    @Builder.Default
    private List<String> retrievalNotes = new ArrayList<>();
    /** 使用的检索通道名称（如 vector/keyword） */
    @Builder.Default
    private List<String> usedChannels = new ArrayList<>();
    /** 工具调用轨迹（每次工具调用的名称、参数、结果） */
    @Builder.Default
    private List<ChatToolTrace> toolTraces = new ArrayList<>();
    /** LLM 调用追踪（每次 LLM 调用的 Token 用量和耗时） */
    @Builder.Default
    private List<ChatModelUsageTrace> modelUsageTraces = new ArrayList<>();
    /** 调用限制统计（模型和工具的调用次数上限及实际用量） */
    private ChatLimitStats limitStats;

    // ═══════════════════ RAG 回答生成 ═══════════════════
    /** RAG 模式下的系统提示词（system prompt） */
    private String ragSystemPrompt;
    /** RAG 模式下的用户提示词（user prompt，含检索证据） */
    private String ragUserPrompt;

    // ═══════════════════ 兜底 ═══════════════════
    /** 检索结果为空时返回的兜底回复文本 */
    private String noEvidenceReply;
}
