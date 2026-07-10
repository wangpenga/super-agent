package org.javaup.ai.eval.metric;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Answer Relevancy（答案相关性）计算结果
 * <p>
 * 衡量生成的答案是否与用户问题相关，不包含无关信息。
 * <p>
 * 实现方式：
 * <ol>
 *   <li>LLM 根据答案反向生成 N 个可能的问题</li>
 *   <li>计算原问题 embedding 与生成问题 embedding 的平均余弦相似度</li>
 *   <li>相似度越高 → 答案越针对问题，相关性越强</li>
 * </ol>
 *
 * @author wangpeng
 */
@Data
@AllArgsConstructor
public class AnswerRelevancyResult {

    /** 最终的 Answer Relevancy 分数，范围 [0, 1] */
    private double score;

    /** 反向生成的问题列表 */
    private List<String> generatedQuestions;

    /** 原始问题 */
    private String originalQuestion;

    /** Cosine 相似度明细 */
    private List<Double> cosineSimilarities;

    /** 空的 Relevancy 结果 */
    public static final AnswerRelevancyResult ZERO =
        new AnswerRelevancyResult(0.0, List.of(), "", List.of());
}
