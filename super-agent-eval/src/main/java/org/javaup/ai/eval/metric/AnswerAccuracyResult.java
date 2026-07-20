package org.javaup.ai.eval.metric;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Answer Accuracy（答案准确率）计算结果
 * <p>
 * 这是用户最直观关心的指标：生成的答案和你给的参考答案意思一致吗？
 * <p>
 * 用 LLM-as-Judge 判断语义一致性，而非关键词/字符串匹配。
 * 因为"房东是张三"和"本合同中的甲方即房屋所有权人张三"说的是同一件事，
 * 但字符串匹配会判为不一致。
 *
 * @author wangpeng
 */
@Data
@AllArgsConstructor
public class AnswerAccuracyResult {

    /** 答案准确率分数，范围 [0, 1] — 1 表示完全与参考答案一致 */
    private double score;

    /** 判断理由（LLM 给出的解释） */
    private String reason;

    /** 空的准确率结果（无参考答案时返回） */
    public static final AnswerAccuracyResult ZERO =
        new AnswerAccuracyResult(0.0, "未提供参考答案或生成答案");
}
