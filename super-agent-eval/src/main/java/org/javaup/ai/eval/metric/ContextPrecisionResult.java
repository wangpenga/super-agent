package org.javaup.ai.eval.metric;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Context Precision 计算结果
 * <p>
 * 衡量检索结果中排在前面的文档是否相关。
 * RAGAS 原始定义：
 * Context Precision@K = Σ(precision@k × rel(k)) / total_relevant_in_top_K
 * <p>
 * 其中 precision@k = relevant_in_top_k / k,
 * rel(k) 为 1 如果第 k 位文档相关，否则为 0。
 *
 * @author wangpeng
 */
@Data
@AllArgsConstructor
public class ContextPrecisionResult {

    /** 最终的 Context Precision 分数，范围 [0, 1] */
    private double score;

    /** 逐位的相关性判断明细 */
    private List<RelevanceJudgment> judgments;

    /** 如果全部不相关，返回零分数的结果 */
    public static final ContextPrecisionResult ZERO =
        new ContextPrecisionResult(0.0, List.of());

    /**
     * 单条相关性判断
     */
    @Data
    @AllArgsConstructor
    public static class RelevanceJudgment {
        /** chunk ID（可为 null，如来自外部数据源） */
        private Long chunkId;
        /** 排名（从 1 开始） */
        private int rank;
        /** 是否相关 */
        private boolean relevant;
        /** 判定依据：rerank / llm_judge */
        private String method;
        /** 原始 rerank 分数（如果有） */
        private Double rerankScore;
        /** 判断理由 */
        private String reason;
        /** 文档文本摘要（前 100 字） */
        private String textSnippet;
    }
}
