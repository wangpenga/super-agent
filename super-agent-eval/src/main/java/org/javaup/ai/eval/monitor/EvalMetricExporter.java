package org.javaup.ai.eval.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.mapper.EvalRunMapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Prometheus 指标导出器
 * <p>
 * 将最新一次评估运行的指标导出到 Prometheus，方便 Grafana 监控。
 * 指标前缀：rag_eval_
 *
 * @author 阿星不是程序员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalMetricExporter {

    private final MeterRegistry meterRegistry;
    private final EvalRunMapper evalRunMapper;

    /** 最新一次完成的运行 */
    private final AtomicReference<EvalRun> latestRun = new AtomicReference<>(null);

    @PostConstruct
    public void init() {
        // 启动时加载最近一次完成的运行
        LambdaQueryWrapper<EvalRun> wrapper = new LambdaQueryWrapper<EvalRun>()
            .eq(EvalRun::getRunStatus, 3) // completed
            .orderByDesc(EvalRun::getId)
            .last("LIMIT 1");
        EvalRun latest = evalRunMapper.selectOne(wrapper);
        if (latest != null) {
            latestRun.set(latest);
        }

        // 注册 Prometheus Gauge
        Gauge.builder("rag_eval_context_precision", this, m -> getLatestValue(m.latestRun.get(), "precision"))
            .tag("application", "super-agent-eval")
            .description("Latest Context Precision score")
            .register(meterRegistry);

        Gauge.builder("rag_eval_context_recall", this, m -> getLatestValue(m.latestRun.get(), "recall"))
            .tag("application", "super-agent-eval")
            .description("Latest Context Recall score")
            .register(meterRegistry);

        Gauge.builder("rag_eval_faithfulness", this, m -> getLatestValue(m.latestRun.get(), "faithfulness"))
            .tag("application", "super-agent-eval")
            .description("Latest Faithfulness score")
            .register(meterRegistry);

        Gauge.builder("rag_eval_answer_relevancy", this, m -> getLatestValue(m.latestRun.get(), "relevancy"))
            .tag("application", "super-agent-eval")
            .description("Latest Answer Relevancy score")
            .register(meterRegistry);

        Gauge.builder("rag_eval_sample_count", this, m -> {
            EvalRun r = m.latestRun.get();
            return r != null && r.getDatasetSize() != null ? r.getDatasetSize() : 0;
        }).tag("application", "super-agent-eval")
            .description("Number of samples in latest eval run")
            .register(meterRegistry);

        log.info("Prometheus 指标注册完成");
    }

    /**
     * 更新最新运行（在 EvalPipelineOrchestrator 完成时调用）
     */
    public void updateLatestRun(EvalRun evalRun) {
        if (evalRun != null && evalRun.getRunStatus() == 3) {
            latestRun.set(evalRun);
            log.info("Prometheus 指标已更新: runName={}", evalRun.getRunName());
        }
    }

    private double getLatestValue(EvalRun run, String metric) {
        if (run == null) return 0;
        return switch (metric) {
            case "precision" -> run.getAvgContextPrecision() != null ? run.getAvgContextPrecision().doubleValue() : 0;
            case "recall" -> run.getAvgContextRecall() != null ? run.getAvgContextRecall().doubleValue() : 0;
            case "faithfulness" -> run.getAvgFaithfulness() != null ? run.getAvgFaithfulness().doubleValue() : 0;
            case "relevancy" -> run.getAvgAnswerRelevancy() != null ? run.getAvgAnswerRelevancy().doubleValue() : 0;
            default -> 0;
        };
    }
}
