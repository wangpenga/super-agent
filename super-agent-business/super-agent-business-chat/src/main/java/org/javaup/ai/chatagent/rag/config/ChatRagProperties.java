package org.javaup.ai.chatagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 聊天 RAG 编排配置属性
 * <p>
 * 配置前缀: {@code app.chat.rag}
 * <p>
 * 控制整个 RAG 管道的所有参数：从检索阶段的 topK/阈值/超时，
 * 到改写阶段的温度参数，到证据预算的字数限制，到补全提示词。
 *
 * @author 阿星不是程序员
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.chat.rag")
public class ChatRagProperties {

    // ═══════════════════════════════════════════════════════════════
    // 功能开关
    // ═══════════════════════════════════════════════════════════════

    /** 文档问答模式总开关（关闭后只能使用 OPEN_CHAT） */
    private boolean enabled = true;

    /** 问题改写功能开关（是否调用 LLM 改写问题） */
    private boolean rewriteEnabled = true;

    /** 关键词检索通道开关 */
    private boolean keywordChannelEnabled = true;

    /** Rerank 精排开关（对 RRF 融合结果进行二次排序） */
    private boolean rerankEnabled = true;

    // ═══════════════════════════════════════════════════════════════
    // 问题改写
    // ═══════════════════════════════════════════════════════════════

    /** 改写时携带的历史轮次数（给 LLM 看的上下文窗口） */
    private int rewriteHistoryTurns = 4;

    /** 改写 LLM 调用参数（temperature/topP/thinking） */
    private RewriteOptionsProperties rewriteOptions = new RewriteOptionsProperties();

    // ═══════════════════════════════════════════════════════════════
    // 检索参数
    // ═══════════════════════════════════════════════════════════════

    /** 改写时最多拆分的子问题数 */
    private int maxSubQuestions = 4;

    /** 向量语义检索 topK（召回数量） */
    private int vectorTopK = 8;

    /** 关键词检索 topK（召回数量） */
    private int keywordTopK = 8;

    /** RRF 融合后的候选数量 */
    private int candidateTopK = 10;

    /** 最终选入 Prompt 的引用数量 */
    private int finalTopK = 5;

    /** 向量相似度最低阈值（低于此值的召回结果丢弃） */
    private double minVectorSimilarity = 0.45D;

    /** 关键词相关性分数最低阈值（低于百分比的结果丢弃） */
    private double keywordRelativeScoreFloor = 0.35D;

    // ═══════════════════════════════════════════════════════════════
    // 证据预算控制（字符数限制）
    // ═══════════════════════════════════════════════════════════════

    /** 单个父块证据的最大字符数 */
    private int parentEvidenceMaxChars = 2200;

    /** 历史规划上下文的最大字符数（超出部分裁剪头部） */
    private int planningHistoryMaxChars = 1600;

    /** 回答历史上下文的最大字符数 */
    private int answerHistoryMaxChars = 1000;

    /** 所有子问题证据总字符数上限 */
    private int totalEvidenceMaxChars = 5200;

    /** 每个子问题证据的字符数上限 */
    private int perSubQuestionEvidenceMaxChars = 2200;

    // ═══════════════════════════════════════════════════════════════
    // 超时控制
    // ═══════════════════════════════════════════════════════════════

    /** 单个检索通道的超时时间（毫秒） */
    private long channelTimeoutMs = 5000L;

    /** 单个子问题检索的总超时时间（毫秒） */
    private long subQuestionTimeoutMs = 12000L;

    // ═══════════════════════════════════════════════════════════════
    // Prompt / 兜底
    // ═══════════════════════════════════════════════════════════════

    /** 检索结果为空时的兜底回复文本 */
    private String noEvidenceReply = "当前没有从已接入文档中检索到足够证据，暂时不能给出可靠结论。";

    /** RAG 回答的系统提示词（System Prompt，为空则使用默认） */
    private String answerSystemPrompt = "";

    // ═══════════════════════════════════════════════════════════════
    // 子配置
    // ═══════════════════════════════════════════════════════════════

    /** 历史摘要配置（控制对话记忆压缩行为） */
    private HistorySummaryProperties historySummary = new HistorySummaryProperties();

    /** Rerank 精排配置（API 地址、模型、超时等） */
    private RerankProperties rerank = new RerankProperties();

    // ═══════════════════════════════════════════════════════════════
    // 内部类
    // ═══════════════════════════════════════════════════════════════

    /**
     * 历史摘要配置
     * <p>
     * 控制对话记忆压缩的时机和范围：保留最近 N 轮原文，
     * 每 M 轮触发一次压缩，压缩后文本有字符数上限。
     */
    @Data
    public static class HistorySummaryProperties {

        /** 历史摘要功能开关 */
        private boolean enabled = true;

        /** 保留最近 N 轮对话原文（不被压缩，直接注入 Prompt） */
        private int keepRecentTurns = 4;

        /** 每累计 M 轮对话后触发一次压缩 */
        private int compressionBatchTurns = 6;

        /** 近期对话窗口的最大字符数 */
        private int recentTranscriptMaxChars = 2200;

        /** 压缩后长期摘要的最大字符数 */
        private int summaryMaxChars = 1400;
    }

    /**
     * 问题改写 LLM 参数
     * <p>
     * 控制改写 LLM 调用的温度和采样策略。
     * 改写任务需要高确定性（避免改写后偏离原意），
     * 所以默认温度很低（0.1）且关闭思考链。
     */
    @Data
    public static class RewriteOptionsProperties {

        /** 改写参数开关（开启时使用此处配置覆盖默认值） */
        private boolean enabled = true;

        /** 采样温度（越低越确定，默认 0.1 保证改写不走样） */
        private Double temperature = 0.1D;

        /** 核采样 topP（0.3 只保留概率最高的前 30% token） */
        private Double topP = 0.3D;

        /** 是否开启思考链（改写任务不需要深度推理，默认关闭） */
        private Boolean thinking = Boolean.FALSE;
    }

    /**
     * Rerank 精排配置
     * <p>
     * RRF 融合后的候选结果通过 Rerank API 进行二次语义排序，
     * 提高最终选入 Prompt 的引用质量。
     */
    @Data
    public static class RerankProperties {

        /** Rerank 功能开关 */
        private boolean enabled = false;

        /** Rerank API 地址（默认使用 SiliconFlow 的免费 API） */
        private String url = "https://api.siliconflow.cn/v1/rerank";

        /** Rerank API Key */
        private String apiKey;

        /** Rerank 模型名称 */
        private String model = "BAAI/bge-reranker-v2-m3";

        /** Rerank 后保留的 Top N 结果 */
        private int topN = 5;

        /** 连接超时（毫秒） */
        private int connectTimeoutMs = 3000;

        /** 读取超时（毫秒） */
        private int readTimeoutMs = 6000;
    }
}
