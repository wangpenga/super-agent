package org.javaup.ai.eval.metric;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Faithfulness（忠实度）计算结果
 * <p>
 * 衡量 LLM 生成的答案是否忠实于检索到的证据内容，不捏造事实。
 * Faithfulness = 可被证据支持的句子数 / 总句子数
 * <p>
 * 实现方式：将答案拆分为独立主张（claims），逐条用 LLM 判断
 * 是否可以从检索证据中推断出来。
 *
 * @author wangpeng
 */
@Data
@AllArgsConstructor
public class FaithfulnessResult {

    /** 最终的 Faithfulness 分数，范围 [0, 1] */
    private double score;

    /** 总主张数 */
    private int totalClaims;

    /** 被证据支持的主张数 */
    private int supportedClaims;

    /** 逐条主张的判断明细 */
    private List<ClaimJudgment> claims;

    /** 空的忠实度结果 */
    public static final FaithfulnessResult ZERO =
        new FaithfulnessResult(0.0, 0, 0, List.of());

    /**
     * 单条主张的判断
     */
    @Data
    @AllArgsConstructor
    public static class ClaimJudgment {
        /** 主张文本 */
        private String claim;
        /** 是否忠实于证据 */
        private boolean faithful;
        /** 判断理由 */
        private String reason;
    }
}
