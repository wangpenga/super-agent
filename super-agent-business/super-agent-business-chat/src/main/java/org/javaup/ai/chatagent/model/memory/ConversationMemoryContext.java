package org.javaup.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话记忆上下文 —— 编排阶段真正使用的记忆数据
 * <p>
 * 由 {@code ConversationMemoryService.loadMemoryContext(conversationId)} 生成，
 * 包含两类数据：
 * <ol>
 *   <li><b>纯文本</b>：组装好的历史文本 + 长期摘要 + 近期窗口 — 直接注入 LLM Prompt</li>
 *   <li><b>结构化数据</b>：会话目标、已确认事实、待跟进问题、检索提示 — 用于路由决策</li>
 *   <li><b>压缩元数据</b>：记录历史记忆被压缩了多深</li>
 * </ol>
 * <p>
 * <b>数据来源：</b>
 * <ul>
 *   <li>长期摘要来自 {@code super_agent_chat_memory_summary} 表</li>
 *   <li>近期对话窗口来自 {@code super_agent_chat_exchange} 表（最近几轮问答）</li>
 * </ul>
 *
 * @author wangpeng
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemoryContext {

    // ═══════════════ 组装好的文本（直接注入 LLM Prompt）═══════════════

    /**
     * 拼接好的完整历史上下文文本
     * = 长期摘要 + 近期对话窗口的拼接结果
     * 该字段可能用不到，因为编排器会从下面两个字段独立取用
     */
    private String assembledHistory;

    /**
     * 长期摘要文本
     * 覆盖了 coveredExchangeId 之前的所有轮次，
     * 由记忆压缩（ConversationMemoryService）生成，写入 memory_summary 表
     */
    private String longTermSummary;

    /**
     * 近期对话窗口原文
     * 最近 N 轮（由 historyPreviewTurns 配置控制）的完整问答记录，
     * 直接从 exchange 表中读取，未经压缩
     */
    private String recentTranscript;

    /**
     * 近期助手回答原文
     * 与 recentTranscript 对应，但只包含助手回答部分，
     * 用于判断当前问题是否为追问（如"那第二个呢？"）
     */
    private String answerRecentTranscript;

    // ═══════════════ 结构化数据（用于路由决策）═══════════════

    /**
     * 长期摘要的结构化载体
     * 从 summary_json 字段反序列化，包含：
     * conversationGoal（会话目标）、stableFacts（已确认事实）、
     * pendingQuestions（待跟进问题）、retrievalHints（检索提示）
     */
    private ConversationSummaryPayload summaryPayload;

    // ═══════════════ 压缩元数据 ═══════════════

    /**
     * 长期摘要已覆盖到的最后一条 exchangeId
     * 这个 ID 之前的轮次 → 已压缩到 longTermSummary 中
     * 这个 ID 之后的轮次 → 还在 recentTranscript 中（未压缩）
     */
    private Long coveredExchangeId;

    /** 长期摘要已覆盖的轮次数 */
    private Integer coveredExchangeCount;

    /** 累计压缩次数（整个会话生命周期内） */
    private Integer compressionCount;

    /** 本轮是否应用了历史压缩（有长期摘要数据） */
    private boolean compressionApplied;
}
