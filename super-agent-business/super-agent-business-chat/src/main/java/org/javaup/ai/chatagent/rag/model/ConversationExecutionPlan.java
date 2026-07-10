package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.enums.ChatQueryMode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话执行计划 —— ChatPreparationOrchestrator.prepare() 的产出，
 * 下游 Executor 根据它决定怎么执行。
 * <p>
 * 包含 7 组信息：
 * <ol>
 *   <li><b>执行决策</b>：走哪个执行器（mode）</li>
 *   <li><b>问题演化链</b>：原始问题 → 改写 → 检索问题 → Agent 问题</li>
 *   <li><b>历史记忆</b>：LLM 看到的历史上下文</li>
 *   <li><b>压缩统计</b>：历史记忆被压缩了多少</li>
 *   <li><b>时间感知</b>：当前日期 + 是否需要实时搜索</li>
 *   <li><b>文档/检索</b>：目标文档、检索范围</li>
 *   <li><b>澄清/兜底</b>：文档歧义时的澄清文本、检索为空时的兜底回复</li>
 * </ol>
 *
 * @author wangpeng
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExecutionPlan {

    // ═══════════════════ ① 执行决策 ═══════════════════

    /** 执行模式 → 决定用哪个 Executor：REACT_AGENT / RETRIEVAL / CLARIFICATION / GRAPH_ONLY / GRAPH_THEN_EVIDENCE */
    private ExecutionMode mode;

    /** 聊天模式 → 原始请求的 chatMode：OPEN_CHAT / DOCUMENT / AUTO_DOCUMENT */
    private ChatQueryMode chatMode;

    // ═══════════════════ ② 问题演化链 — 用户问题经过多步加工 ═══════════════════

    /** 用户原始提问（trim 后） */
    private String originalQuestion;

    /** 给 LLM Agent 的最终问题文本，由 Prompt 模板渲染生成（含日期锚定、历史上下文等） */
    private String agentQuestion;

    /** LLM 改写后的检索友好问题（口语 → 结构化查询） */
    private String rewriteQuestion;

    /** 改写后的子问题列表（复杂问题拆分为多个子问题分别检索） */
    @Builder.Default
    private List<String> rewriteSubQuestions = new ArrayList<>();

    /** 最终用于检索的问题（可能经过 documentQuestionRouter 的路由调整） */
    private String retrievalQuestion;

    /** 检索用子问题列表（经过路由调整后的子问题） */
    @Builder.Default
    private List<String> retrievalSubQuestions = new ArrayList<>();

    // ═══════════════════ ③ 历史记忆 — 给 LLM 看的上下文 ═══════════════════

    /** 历史摘要文本（精炼后的近期对话，用于注入 Prompt） */
    private String historySummary;

    /** 长期摘要文本（覆盖 N 轮之前的对话，记忆压缩生成） */
    private String longTermSummary;

    /** 历史规划上下文（结构化：会话目标、已确认事实、待跟进问题、检索提示） */
    @Builder.Default
    private HistoryPlanningContext historyPlanningContext = new HistoryPlanningContext();

    /** 近期对话窗口原文（最新几轮的完整问答记录） */
    private String recentHistoryTranscript;

    /** 近期的助手回答原文 */
    private String answerRecentTranscript;

    /** 回答历史上下文（含追问判断 + 渲染后的上下文文本） */
    private AnswerHistoryContext answerHistoryContext;

    // ═══════════════════ ④ 路由决策 ═══════════════════

    /** 文档路由决策：走结构图查询还是混合检索，含章节定位锚点、检索计划等 */
    private DocumentNavigationDecision navigationDecision;

    // ═══════════════════ ⑤ 历史压缩统计 ═══════════════════

    /** 本轮是否应用了历史压缩 */
    private boolean historyCompressionApplied;

    /** 压缩已覆盖到的最后一条 exchangeId（之后的轮次未被压缩） */
    private Long historyCoveredExchangeId;

    /** 压缩已覆盖的轮次数 */
    private Integer historyCoveredExchangeCount;

    /** 累计压缩次数（整个会话生命周期内） */
    private Integer historyCompressionCount;

    // ═══════════════════ ⑥ 时间感知 ═══════════════════

    /** 当前日期（Asia/Shanghai 时区） */
    private LocalDate currentDate;

    /** 当前日期文本，如 "2026-06-25（星期四）" */
    private String currentDateText;

    /** 是否需要实时搜索（问题含"最新""现在""实时"等词时置 true） */
    private boolean requiresFreshSearch;

    /** 是否需要日期锚定（问题含"今天""当前""最近"等词时置 true） */
    private boolean requiresCurrentDateAnchoring;

    // ═══════════════════ ⑦ 文档/检索范围 ═══════════════════

    /** 当前锁定的文档 ID（DOCUMENT 模式和 AUTO_DOCUMENT 确定文档后） */
    private Long selectedDocumentId;

    /** 当前锁定的文档名称 */
    private String selectedDocumentName;

    /** 当前锁定的索引任务 ID（用于确保检索的是最新索引版本） */
    private Long selectedTaskId;

    /** 候选检索文档 ID 列表（AUTO_DOCUMENT 模式可能涉及多个文档） */
    @Builder.Default
    private List<Long> retrievalDocumentIds = new ArrayList<>();

    /** 候选检索任务的索引任务 ID 列表 */
    @Builder.Default
    private List<Long> retrievalTaskIds = new ArrayList<>();

    // ═══════════════════ ⑧ 澄清 — AUTO_DOCUMENT 文档歧义时使用 ═══════════════════

    /** 澄清回复文本（如"这个问题涉及多份文档，你想问哪一份？1.《A》2.《B》"） */
    private String clarificationReply;

    /** 澄清选项列表（如 ["我想问《A》", "我想问《B》"]），在收尾时转为推荐追问 */
    @Builder.Default
    private List<String> clarificationOptions = new ArrayList<>();

    /** 澄清原因（如"置信度 0.42，候选文档 3 份，为避免误选返回澄清"） */
    private String clarificationReason;

    // ═══════════════════ ⑨ 兜底 ═══════════════════

    /** 检索结果为空时返回的兜底回复文本（根据问题类型动态生成） */
    private String noEvidenceReply;
}
