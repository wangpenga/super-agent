package org.javaup.ai.eval.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.service.EvalPipelineOrchestrator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * A/B 测试运行器
 * <p>
 * 先后用 baseline 和 variant 配置各跑一次评估，输出对比结果。
 * 用于验证参数调整是否真的带来了指标提升。
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalABTestRunner {

    private final EvalPipelineOrchestrator pipelineOrchestrator;
    /** 配置 profile 映射：profile 名称 → 对应的配置快照描述 */
    private final Map<String, String> configProfiles = new LinkedHashMap<>();

    {
        configProfiles.put("baseline", "当前生产配置（基线）");
        configProfiles.put("variant-a", "调高 minVectorSimilarity=0.50, vectorTopK=12, keywordTopK=12");
        configProfiles.put("variant-b", "调高 vectorTopK=10, rerank.topN=8");
    }

    /**
     * 运行 A/B 对比测试
     *
     * @param baselineName baseline 配置名称（由调用方确保主服务已切换到该配置）
     * @param variantName  variant 配置名称
     * @return A/B 对比结果
     */
    public ABTestResult runABTest(String baselineName, String variantName) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        log.info("开始 A/B 测试：baseline={}, variant={}", baselineName, variantName);

        // 1. 运行 baseline
        log.info("第 1 阶段：运行 baseline [{}]", baselineName);
        EvalRun baselineRun = pipelineOrchestrator.run(
            dateStr + "-baseline-" + baselineName, "ab_test");

        // 2. 运行 variant
        log.info("第 2 阶段：运行 variant [{}]", variantName);
        EvalRun variantRun = pipelineOrchestrator.run(
            dateStr + "-variant-" + variantName, "ab_test");

        // 3. 对比
        return buildComparison(baselineRun, variantRun);
    }

    /**
     * 构建对比结果
     */
    private ABTestResult buildComparison(EvalRun baseline, EvalRun variant) {
        ABTestResult result = new ABTestResult();
        result.baselineRunName = baseline.getRunName();
        result.variantRunName = variant.getRunName();

        // 逐指标对比
        result.metricComparisons = new ArrayList<>();

        result.metricComparisons.add(compareMetric(
            "Context Precision", baseline.getAvgContextPrecision(), variant.getAvgContextPrecision()));
        result.metricComparisons.add(compareMetric(
            "Context Recall", baseline.getAvgContextRecall(), variant.getAvgContextRecall()));
        result.metricComparisons.add(compareMetric(
            "Faithfulness", baseline.getAvgFaithfulness(), variant.getAvgFaithfulness()));
        result.metricComparisons.add(compareMetric(
            "Answer Relevancy", baseline.getAvgAnswerRelevancy(), variant.getAvgAnswerRelevancy()));

        // 判断整体胜负
        long wins = result.metricComparisons.stream()
            .filter(c -> c.changeDirection != null && c.changeDirection.contains("↑"))
            .count();
        long losses = result.metricComparisons.stream()
            .filter(c -> c.changeDirection != null && c.changeDirection.contains("↓"))
            .count();

        if (wins > losses) {
            result.conclusion = "Variant [" + variant.getRunName() + "] 整体优于 Baseline";
        } else if (losses > wins) {
            result.conclusion = "Baseline [" + baseline.getRunName() + "] 仍然更优";
        } else {
            result.conclusion = "两者无明显差异，建议放大差异再试";
        }

        return result;
    }

    private MetricComparison compareMetric(
            String name, Number baselineVal, Number variantVal) {

        MetricComparison mc = new MetricComparison();
        mc.metricName = name;
        mc.baselineValue = baselineVal != null ? baselineVal.toString() : "N/A";
        mc.variantValue = variantVal != null ? variantVal.toString() : "N/A";

        if (baselineVal != null && variantVal != null) {
            double diff = variantVal.doubleValue() - baselineVal.doubleValue();
            mc.absoluteChange = String.format("%+.4f", diff);
            if (baselineVal.doubleValue() != 0) {
                mc.relativeChange = String.format("%+.1f%%",
                    diff / baselineVal.doubleValue() * 100);
            }
            mc.changeDirection = diff > 0 ? "↑ 提升" : (diff < 0 ? "↓ 下降" : "→ 持平");
        }

        return mc;
    }

    /**
     * A/B 对比结果
     */
    public static class ABTestResult {
        public String baselineRunName;
        public String variantRunName;
        public List<MetricComparison> metricComparisons;
        public String conclusion;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("========================================\n");
            sb.append("A/B 测试对比结果\n");
            sb.append("========================================\n");
            sb.append("Baseline: ").append(baselineRunName).append("\n");
            sb.append("Variant:  ").append(variantRunName).append("\n\n");
            sb.append(String.format("%-20s %-15s %-15s %-10s %-10s\n",
                "指标", "Baseline", "Variant", "绝对变化", "相对变化"));
            for (MetricComparison mc : metricComparisons) {
                sb.append(String.format("%-20s %-15s %-15s %-10s %-10s\n",
                    mc.metricName, mc.baselineValue, mc.variantValue,
                    mc.absoluteChange, mc.relativeChange));
            }
            sb.append("\n结论：").append(conclusion).append("\n");
            sb.append("========================================");
            return sb.toString();
        }
    }

    /**
     * 单指标对比
     */
    public static class MetricComparison {
        public String metricName;
        public String baselineValue;
        public String variantValue;
        public String absoluteChange;
        public String relativeChange;
        public String changeDirection;
    }
}
