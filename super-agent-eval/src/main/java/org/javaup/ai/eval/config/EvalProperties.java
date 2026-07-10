package org.javaup.ai.eval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评估模块全部配置属性
 * <p>
 * 前缀: {@code eval}
 * 控制数据集生成、LLM Judge 分层判题、管道并发、A/B 测试等参数。
 *
 * @author wangpeng
 */
@Data
@Component
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    /** 主服务连接配置 */
    private ChatServiceProperties chatService = new ChatServiceProperties();

    /** LLM-as-Judge 判题配置 */
    private JudgeProperties judge = new JudgeProperties();

    /** 数据集生成配置 */
    private DatasetProperties dataset = new DatasetProperties();

    /** 评估管道配置 */
    private PipelineProperties pipeline = new PipelineProperties();

    /** A/B 测试配置 */
    private OptimizationProperties optimization = new OptimizationProperties();

    // ============================================================
    // 内部配置类
    // ============================================================

    /**
     * 主服务连接配置
     * eval 服务通过 RestTemplate 调用主服务的内部检索 API，
     * 不直接依赖主服务的数据库或 Bean。
     */
    @Data
    public static class ChatServiceProperties {
        /** 主服务 HTTP 地址 */
        private String url = "http://127.0.0.1:9082";
        /** 检索 API 路径 */
        private String retrieveApi = "/api/internal/rag/retrieve";
    }

    /**
     * LLM-as-Judge 分层判题配置
     * <p>
     * 利用 rerank 分数分层决策，减少 LLM 调用：
     * <ul>
     *   <li>rerank ≥ highThreshold → 直接相关（零 LLM）</li>
     *   <li>rerank ≤ lowThreshold → 直接不相关（零 LLM）</li>
     *   <li>中间区间 → LLM Judge 二次确认</li>
     * </ul>
     */
    @Data
    public static class JudgeProperties {
        /** 高阈值：rerank 分 >= 此值 → 判为相关 */
        private double rerankThresholdHigh = 0.5;
        /** 低阈值：rerank 分 < 此值 → 判为不相关 */
        private double rerankThresholdLow = 0.3;
        /** LLM Judge 并发数 */
        private int judgeConcurrency = 2;
        /** Judge 使用的模型名称 */
        private String judgeModel = "qwen-plus-latest";
    }

    /**
     * 数据集生成配置
     */
    @Data
    public static class DatasetProperties {
        /** 每个文档最少问题数 */
        private int minQuestionsPerDocument = 3;
        /** 每个文档最多问题数 */
        private int maxQuestionsPerDocument = 10;
        /** 标注时检索候选 chunks 数 */
        private int groundTruthExtractTopK = 20;
        /** 启动时是否自动生成数据集 */
        private boolean autoGenerateOnStartup = false;
    }

    /**
     * 评估管道配置
     */
    @Data
    public static class PipelineProperties {
        /** 并行评估线程数 */
        private int concurrency = 4;
        /** 单问题超时（毫秒） */
        private long timeoutPerQuestionMs = 30000;
        /** LLM Judge 并发数 */
        private int relevanceJudgeConcurrency = 2;
    }

    /**
     * A/B 测试配置
     */
    @Data
    public static class OptimizationProperties {
        /** A/B 测试开关 */
        private boolean abTestEnabled = true;
    }
}
