package org.javaup.ai.eval.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.eval.data.EvalMetricDaily;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.mapper.EvalRunMapper;
import org.javaup.ai.eval.service.EvalMetricAggregationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估仪表盘接口
 * <p>
 * 提供趋势图、汇总面板等前端数据。
 *
 * @author 阿星不是程序员
 */
@RestController
@RequestMapping("/api/admin/eval/dashboard")
@RequiredArgsConstructor
public class EvalDashboardController {

    private final EvalRunMapper evalRunMapper;
    private final EvalMetricAggregationService aggregationService;

    /**
     * 总体指标概览
     * POST /api/admin/eval/dashboard/summary
     * <p>
     * 返回最新运行的指标值 + 与上一次运行的变化
     */
    @PostMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        // 最近两次完成的运行
        LambdaQueryWrapper<EvalRun> wrapper = new LambdaQueryWrapper<EvalRun>()
            .eq(EvalRun::getRunStatus, 3)
            .orderByDesc(EvalRun::getId)
            .last("LIMIT 2");
        List<EvalRun> runs = evalRunMapper.selectList(wrapper);

        Map<String, Object> summary = new LinkedHashMap<>();

        if (!runs.isEmpty()) {
            EvalRun latest = runs.get(0);
            summary.put("latestRun", toRunMap(latest));

            if (runs.size() > 1) {
                EvalRun previous = runs.get(1);
                summary.put("previousRun", toRunMap(previous));
                summary.put("precisionChange", calculateChange(
                    previous.getAvgContextPrecision(), latest.getAvgContextPrecision()));
                summary.put("recallChange", calculateChange(
                    previous.getAvgContextRecall(), latest.getAvgContextRecall()));
                summary.put("faithfulnessChange", calculateChange(
                    previous.getAvgFaithfulness(), latest.getAvgFaithfulness()));
                summary.put("relevancyChange", calculateChange(
                    previous.getAvgAnswerRelevancy(), latest.getAvgAnswerRelevancy()));
            }
        }

        return ResponseEntity.ok(summary);
    }

    /**
     * 指标趋势数据
     * POST /api/admin/eval/dashboard/trend
     */
    @PostMapping("/trend")
    public ResponseEntity<Map<String, Object>> trend(@RequestBody(required = false) Map<String, Object> params) {
        String metricName = params != null ? (String) params.get("metricName") : null;
        int days = params != null && params.get("days") != null
            ? Integer.parseInt(params.get("days").toString()) : 30;

        List<EvalMetricDaily> trend = aggregationService.getTrend(metricName, days);
        return ResponseEntity.ok(Map.of(
            "metricName", metricName != null ? metricName : "all",
            "days", days,
            "dataPoints", trend
        ));
    }

    /**
     * 按文档维度展开指标
     * POST /api/admin/eval/dashboard/per-document
     */
    @PostMapping("/per-document")
    public ResponseEntity<Map<String, Object>> perDocument(@RequestBody Map<String, Long> request) {
        Long runId = request.get("runId");
        if (runId == null) return ResponseEntity.badRequest().build();

        // 这里返回简化的聚合数据，完整的按文档分析走 /api/admin/eval/run/analyze
        return ResponseEntity.ok(Map.of("runId", runId, "message", "详细分析请调用 /api/admin/eval/run/analyze"));
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private Map<String, Object> toRunMap(EvalRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runName", run.getRunName());
        map.put("contextPrecision", run.getAvgContextPrecision());
        map.put("contextRecall", run.getAvgContextRecall());
        map.put("faithfulness", run.getAvgFaithfulness());
        map.put("answerRelevancy", run.getAvgAnswerRelevancy());
        map.put("datasetSize", run.getDatasetSize());
        map.put("avgLatencyMs", run.getAvgLatencyMs());
        map.put("startedAt", run.getStartedAt());
        map.put("completedAt", run.getCompletedAt());
        return map;
    }

    private String calculateChange(Number oldVal, Number newVal) {
        if (oldVal == null || newVal == null || oldVal.doubleValue() == 0) return "N/A";
        double change = (newVal.doubleValue() - oldVal.doubleValue()) / oldVal.doubleValue() * 100;
        return String.format("%+.2f%%", change);
    }
}
