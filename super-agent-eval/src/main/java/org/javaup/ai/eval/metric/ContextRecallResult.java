package org.javaup.ai.eval.metric;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Context Recall（上下文召回率）计算结果
 * <p>
 * 衡量检索到的证据是否覆盖了回答该问题所需的全部信息。
 * 使用参考答案（ground truth answer）作为基准：
 * <ol>
 *   <li>将参考答案拆分为独立的可验证陈述句</li>
 *   <li>判断每个陈述句是否可以从检索到的证据中找到支持</li>
 *   <li>Context Recall = 可归因的陈述句数 / 总陈述句数</li>
 * </ol>
 * <p>
 * 不再使用 chunk ID 交集匹配。因为：
 * <ul>
 *   <li>chunk ID 是物理存储标识，不是语义指标</li>
 *   <li>同一信息可分布在多个 chunk 中，ID 匹配会漏掉</li>
 *   <li>需要跨 chunk 综合推理的问题，ID 匹配完全无效</li>
 * </ul>
 *
 * @author wangpeng
 */
@Data
@AllArgsConstructor
public class ContextRecallResult {

    /** 最终的 Context Recall 分数，范围 [0, 1] */
    private double score;

    /** 可归因的陈述句数量 */
    private long hitCount;

    /** 总的陈述句数量 */
    private long totalCount;

    /** 归因判断明细 */
    private List<AttributionJudgment> attributions;

    /** 可归因的陈述句列表 */
    private List<String> attributableStatements;

    /** 不可归因的陈述句列表 */
    private List<String> missedStatements;

    /**
     * 全量构造器（供新代码使用）
     */
    public ContextRecallResult(double score, long hitCount, long totalCount, List<AttributionJudgment> attributions) {
        this.score = score;
        this.hitCount = hitCount;
        this.totalCount = totalCount;
        this.attributions = attributions;
        this.attributableStatements = attributions == null ? List.of() :
            attributions.stream().filter(a -> a.isAttributable()).map(AttributionJudgment::getStatement).toList();
        this.missedStatements = attributions == null ? List.of() :
            attributions.stream().filter(a -> !a.isAttributable()).map(AttributionJudgment::getStatement).toList();
    }

    /** 空的召回结果 */
    public static final ContextRecallResult ZERO =
        new ContextRecallResult(0.0, 0, 0, List.of());

    /**
     * @return 是否所有陈述句都归因成功
     */
    public boolean isFullRecall() {
        return totalCount > 0 && hitCount == totalCount;
    }

    /**
     * @return 是否完全没有归因成功
     */
    public boolean isZeroRecall() {
        return hitCount == 0;
    }

    /**
     * 单条归因判断明细
     */
    @AllArgsConstructor
    @Data
    public static class AttributionJudgment {
        /** 陈述句原文 */
        private String statement;
        /** 是否可归因于证据 */
        private boolean attributable;
        /** 判断理由 */
        private String reason;
    }
}
