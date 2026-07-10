package org.javaup.ai.eval.metric;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAGAS 指标计算器 —— 核心计算逻辑
 * <p>
 * 计算四种 RAGAS 指标：
 * <ol>
 *   <li>Context Precision — 检索结果的排序质量</li>
 *   <li>Context Recall — 对 ground truth 的召回率</li>
 *   <li>Faithfulness — 生成答案对证据的忠实度</li>
 *   <li>Answer Relevancy — 答案与问题的相关性</li>
 * </ol>
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagasMetricCalculator {

    private final RagasRelevanceJudge relevanceJudge;

    /**
     * 计算 Context Precision
     * <p>
     * RAGAS 定义：
     * Context Precision@K = Σ(precision@k × rel(k)) / total_relevant_in_top_K
     * 其中 precision@k = relevant_in_top_k / k
     * <p>
     * 分子是每个相关文档所在位置的 precision 值的累加，
     * 分母是相关文档总数。这给排在前面的相关文档更高权重。
     *
     * @param question  用户问题
     * @param documents 检索结果列表（按排序传入）
     * @return ContextPrecisionResult
     */
    public ContextPrecisionResult computeContextPrecision(
            String question,
            List<RetrievalRpcResult.DocumentResult> documents) {

        if (documents == null || documents.isEmpty()) {
            log.warn("Context Precision 计算：检索结果为空，返回 0");
            return ContextPrecisionResult.ZERO;
        }

        // 1. 用 LLM-as-Judge（分层策略）判断每条文档的相关性
        List<ContextPrecisionResult.RelevanceJudgment> judgments =
            relevanceJudge.judgeBatch(question, documents);

        if (judgments.isEmpty()) {
            return ContextPrecisionResult.ZERO;
        }

        // 2. 计算 Context Precision@K
        int totalRelevant = (int) judgments.stream().filter(ContextPrecisionResult.RelevanceJudgment::isRelevant).count();

        if (totalRelevant == 0) {
            log.info("Context Precision = 0.0 （所有检索结果均不相关）");
            return new ContextPrecisionResult(0.0, judgments);
        }

        double sumPrecisionAtK = 0.0;
        int relevantCount = 0;

        for (int k = 0; k < judgments.size(); k++) {
            if (judgments.get(k).isRelevant()) {
                relevantCount++;
                // precision@k = relevant_in_top_k / k （k 从 1 开始计数）
                double precisionAtK = (double) relevantCount / (k + 1);
                sumPrecisionAtK += precisionAtK;
            }
        }

        double score = sumPrecisionAtK / totalRelevant;
        log.info("Context Precision = {} (relevant={}/{}, docs={})",
            String.format("%.4f", score), totalRelevant, judgments.size(), documents.size());

        return new ContextPrecisionResult(score, judgments);
    }

    /**
     * 计算 Context Recall
     * <p>
     * 直接用 ground truth chunk IDs 与检索结果 chunk IDs 取交集。
     * 不需 LLM，零成本。
     * <p>
     * Context Recall = |ground_truth ∩ retrieved| / |ground_truth|
     *
     * @param groundTruthChunkIds  ground truth chunk ID 列表（JSON 数组字符串）
     * @param retrievedDocuments   检索到的文档列表
     * @return ContextRecallResult
     */
    public ContextRecallResult computeContextRecall(
            String groundTruthChunkIds,
            List<RetrievalRpcResult.DocumentResult> retrievedDocuments) {

        // 解析 ground truth IDs
        Set<Long> groundTruthSet = parseChunkIds(groundTruthChunkIds);
        if (groundTruthSet.isEmpty()) {
            log.warn("Context Recall 计算：ground truth chunk IDs 为空");
            return ContextRecallResult.ZERO;
        }

        // 提取检索到的 chunk IDs
        Set<Long> retrievedSet = retrievedDocuments.stream()
            .map(RetrievalRpcResult.DocumentResult::getChunkId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        if (retrievedSet.isEmpty()) {
            log.warn("Context Recall = 0.0 （未检索到任何有 chunkId 的结果）");
            return new ContextRecallResult(0.0, 0, groundTruthSet.size(), List.of(), new ArrayList<>(groundTruthSet));
        }

        // 计算交集
        List<Long> hitChunkIds = groundTruthSet.stream()
            .filter(retrievedSet::contains)
            .sorted()
            .toList();

        List<Long> missedChunkIds = groundTruthSet.stream()
            .filter(id -> !retrievedSet.contains(id))
            .sorted()
            .toList();

        long hitCount = hitChunkIds.size();
        long totalCount = groundTruthSet.size();
        double score = (double) hitCount / totalCount;

        log.info("Context Recall = {} (hit={}/{}, total ground truth={})",
            String.format("%.4f", score), hitCount, totalCount, groundTruthSet.size());

        if (!missedChunkIds.isEmpty() && log.isDebugEnabled()) {
            log.debug("未命中的 ground truth chunk IDs: {}", missedChunkIds);
        }

        return new ContextRecallResult(score, hitCount, totalCount, hitChunkIds, missedChunkIds);
    }

    /**
     * 计算 Faithfulness
     * <p>
     * 委托给 RagasRelevanceJudge 执行 LLM 逐句判断。
     * 注意：此方法会消耗 LLM Token，建议只在有真实对话数据的条目上执行。
     *
     * @param answer       生成的答案
     * @param evidenceText 检索到的证据文本（拼接后）
     * @return FaithfulnessResult
     */
    public FaithfulnessResult computeFaithfulness(String answer, String evidenceText) {
        return relevanceJudge.judgeFaithfulness(answer, evidenceText);
    }

    /**
     * 计算 Answer Relevancy
     * <p>
     * 委托给 RagasRelevanceJudge，用 embedding 计算余弦相似度。
     *
     * @param question  用户问题
     * @param answer    生成的答案
     * @param embeddingFunction embedding 计算函数
     * @return AnswerRelevancyResult
     */
    public AnswerRelevancyResult computeAnswerRelevancy(
            String question,
            String answer,
            java.util.function.Function<String, float[]> embeddingFunction) {
        return relevanceJudge.judgeAnswerRelevancy(question, answer, embeddingFunction);
    }

    /**
     * 构建证据文本（将所有检索文档拼接）
     */
    public String buildEvidenceText(List<RetrievalRpcResult.DocumentResult> documents) {
        if (documents == null || documents.isEmpty()) return "";
        return documents.stream()
            .map(d -> "【文档" + d.getChunkId() + "】" + (d.getText() != null ? d.getText() : ""))
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * 解析 JSON 数组字符串为 chunk ID 集合
     */
    private Set<Long> parseChunkIds(String jsonArrayStr) {
        if (jsonArrayStr == null || jsonArrayStr.isBlank()) {
            return Collections.emptySet();
        }
        try {
            List<Long> ids = JSONUtil.toList(jsonArrayStr, Long.class);
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.warn("解析 ground truth chunk IDs 失败: {}", jsonArrayStr, e);
            return Collections.emptySet();
        }
    }
}
