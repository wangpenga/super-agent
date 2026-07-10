package org.javaup.ai.eval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalQuestionResult;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.mapper.EvalQuestionResultMapper;
import org.javaup.ai.eval.mapper.EvalRunMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 薄弱环节分析器
 * <p>
 * 基于评估运行结果，按文档/按通道分析薄弱环节，
 * 为参数优化提供数据支撑。
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalWeaknessAnalyzer {

    private final EvalRunMapper evalRunMapper;
    private final EvalQuestionResultMapper questionResultMapper;

    /**
     * 分析指定运行中每个文档的指标
     *
     * @param runId 评估运行 ID
     * @return 文档维度的指标摘要
     */
    public List<DocumentSummary> analyzeByDocument(Long runId) {
        LambdaQueryWrapper<EvalQuestionResult> wrapper = new LambdaQueryWrapper<EvalQuestionResult>()
            .eq(EvalQuestionResult::getRunId, runId);
        List<EvalQuestionResult> results = questionResultMapper.selectList(wrapper);

        // 按文档分组
        Map<Long, List<EvalQuestionResult>> byDoc = results.stream()
            .filter(r -> r.getDocumentId() != null)
            .collect(Collectors.groupingBy(EvalQuestionResult::getDocumentId));

        List<DocumentSummary> summaries = new ArrayList<>();
        byDoc.forEach((docId, items) -> {
            DoubleSummaryStatistics precisionStats = items.stream()
                .filter(r -> r.getContextPrecision() != null)
                .collect(Collectors.summarizingDouble(r -> r.getContextPrecision().doubleValue()));

            DoubleSummaryStatistics recallStats = items.stream()
                .filter(r -> r.getContextRecall() != null)
                .collect(Collectors.summarizingDouble(r -> r.getContextRecall().doubleValue()));

            summaries.add(new DocumentSummary(
                docId, items.size(),
                BigDecimal.valueOf(precisionStats.getAverage()),
                BigDecimal.valueOf(recallStats.getAverage()),
                BigDecimal.valueOf(precisionStats.getMin()),
                BigDecimal.valueOf(recallStats.getMin())
            ));
        });

        // 按 Context Precision 升序排列（最差的排前面）
        summaries.sort(Comparator.comparing(DocumentSummary::avgPrecision));

        return summaries;
    }

    /**
     * 生成优化建议
     *
     * @param runId 评估运行 ID
     * @return 优化建议列表
     */
    public List<OptimizationAdvice> generateAdvice(Long runId) {
        EvalRun evalRun = evalRunMapper.selectById(runId);
        if (evalRun == null) return List.of();

        List<OptimizationAdvice> advice = new ArrayList<>();

        // 检查 Precision
        if (evalRun.getAvgContextPrecision() != null
            && evalRun.getAvgContextPrecision().compareTo(new BigDecimal("0.6")) < 0) {
            advice.add(new OptimizationAdvice(
                "Context Precision 偏低 (" + evalRun.getAvgContextPrecision() + ")",
                "尝试以下调整：\n"
                    + "1. 提高 minVectorSimilarity（0.45 → 0.50~0.55），过滤低分结果\n"
                    + "2. 确保 rerankEnabled=true，并适当提高 rerank.topN\n"
                    + "3. 适当降低 finalTopK（5 → 3），只保留最相关结果",
                "high"
            ));
        }

        // 检查 Recall
        if (evalRun.getAvgContextRecall() != null
            && evalRun.getAvgContextRecall().compareTo(new BigDecimal("0.5")) < 0) {
            advice.add(new OptimizationAdvice(
                "Context Recall 偏低 (" + evalRun.getAvgContextRecall() + ")",
                "尝试以下调整：\n"
                    + "1. 提高 vectorTopK（8 → 12~15），召回更多候选\n"
                    + "2. 提高 keywordTopK（8 → 12~15）\n"
                    + "3. 适当降低 minVectorSimilarity（0.45 → 0.35~0.40）\n"
                    + "4. 如果 query rewrite 未开启，考虑开启",
                "high"
            ));
        }

        // 检查 Faithfulness
        if (evalRun.getAvgFaithfulness() != null
            && evalRun.getAvgFaithfulness().compareTo(new BigDecimal("0.7")) < 0) {
            advice.add(new OptimizationAdvice(
                "Faithfulness 偏低 (" + evalRun.getAvgFaithfulness() + ")",
                "尝试以下调整：\n"
                    + "1. 检查 Prompt 中是否有『只基于给定证据回答』的明确指令\n"
                    + "2. 收窄证据预算范围，减少不相关信息干扰\n"
                    + "3. 适当减少 finalTopK，确保只传递高质量证据",
                "medium"
            ));
        }

        // 按文档分析最差情况
        List<DocumentSummary> docSummaries = analyzeByDocument(runId);
        if (!docSummaries.isEmpty()) {
            DocumentSummary worst = docSummaries.get(0);
            if (worst.avgPrecision().compareTo(new BigDecimal("0.4")) < 0) {
                advice.add(new OptimizationAdvice(
                    "文档 " + worst.documentId() + " Precision 最低 (" + worst.avgPrecision() + ")",
                    "检查该文档的分块质量，考虑调整 chunk 策略（如 STRUCTURE → SEMANTIC 或反之）",
                    "medium"
                ));
            }
        }

        return advice;
    }

    /**
     * 文档维度的指标摘要
     */
    public record DocumentSummary(
        Long documentId,
        int questionCount,
        BigDecimal avgPrecision,
        BigDecimal avgRecall,
        BigDecimal minPrecision,
        BigDecimal minRecall
    ) {}

    /**
     * 优化建议
     */
    public record OptimizationAdvice(
        String title,
        String suggestion,
        String priority  // high / medium / low
    ) {}
}
