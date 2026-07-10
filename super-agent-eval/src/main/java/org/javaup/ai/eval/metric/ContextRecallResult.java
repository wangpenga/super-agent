package org.javaup.ai.eval.metric;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Context Recall 计算结果
 * <p>
 * 衡量所有 ground truth 相关文档中，有多少被成功检索到。
 * Context Recall = retrieved_ground_truth_chunks / total_ground_truth_chunks
 * <p>
 * 此指标通过 ground truth chunk IDs 与检索结果 chunk IDs 的集合交集直接计算，
 * 不需要 LLM 参与，零成本。
 *
 * @author wangpeng
 */
@Data
@AllArgsConstructor
public class ContextRecallResult {

    /** 最终的 Context Recall 分数，范围 [0, 1] */
    private double score;

    /** 成功命中的 ground truth chunk 数量 */
    private long hitCount;

    /** 总的 ground truth chunk 数量 */
    private long totalCount;

    /** 命中的 chunk ID 列表 */
    private List<Long> hitChunkIds;

    /** 未命中的 ground truth chunk ID 列表 */
    private List<Long> missedChunkIds;

    /** 空的召回结果 */
    public static final ContextRecallResult ZERO =
        new ContextRecallResult(0.0, 0, 0, List.of(), List.of());

    /**
     * @return 是否所有 ground truth 文档都命中
     */
    public boolean isFullRecall() {
        return totalCount > 0 && hitCount == totalCount;
    }

    /**
     * @return 是否完全没有命中
     */
    public boolean isZeroRecall() {
        return hitCount == 0;
    }
}
