package org.javaup.ai.eval.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalQuestionResult;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.mapper.EvalQuestionResultMapper;
import org.javaup.ai.eval.mapper.EvalRunMapper;
import org.javaup.ai.eval.monitor.EvalMetricExporter;
import org.javaup.ai.eval.optimization.EvalABTestRunner;
import org.javaup.ai.eval.service.EvalMetricAggregationService;
import org.javaup.ai.eval.service.EvalPipelineOrchestrator;
import org.javaup.ai.eval.service.EvalWeaknessAnalyzer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评估管道控制接口
 * <p>
 * 提供评估运行的启动、停止、查询、对比等操作。
 * 每次运行会遍历测试集，调用主服务检索，计算指标，持久化结果。
 *
 * @author 阿星不是程序员
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/eval/run")
@RequiredArgsConstructor
public class EvalPipelineController {

    private final EvalPipelineOrchestrator pipelineOrchestrator;
    private final EvalRunMapper evalRunMapper;
    private final EvalQuestionResultMapper questionResultMapper;
    private final EvalMetricAggregationService metricAggregationService;
    private final EvalMetricExporter metricExporter;
    private final EvalABTestRunner abTestRunner;
    private final EvalWeaknessAnalyzer weaknessAnalyzer;

    /**
     * 启动一次评估运行
     * POST /api/admin/eval/run/start
     *
     * @param request { runName: "2026-07-10-baseline", runType: "manual" }
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, String> request) {
        if (pipelineOrchestrator.isRunning()) {
            return ResponseEntity.ok(Map.of(
                "error", "已有评估正在运行，请先停止",
                "running", true
            ));
        }

        String runName = request.getOrDefault("runName",
            "manual-" + System.currentTimeMillis());
        String runType = request.getOrDefault("runType", "manual");

        // 异步启动（避免 HTTP 超时）
        new Thread(() -> {
            EvalRun evalRun = pipelineOrchestrator.run(runName, runType);
            if (evalRun != null) {
                // 完成后自动汇总 + 导出指标
                metricAggregationService.aggregateDaily(evalRun);
                metricExporter.updateLatestRun(evalRun);
            }
        }).start();

        return ResponseEntity.ok(Map.of(
            "message", "评估已启动",
            "runName", runName,
            "runType", runType
        ));
    }

    /**
     * 停止正在运行的评估
     * POST /api/admin/eval/run/stop
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        pipelineOrchestrator.stop();
        return ResponseEntity.ok(Map.of("message", "停止信号已发送"));
    }

    /**
     * 历史运行列表
     * POST /api/admin/eval/run/list
     */
    @PostMapping("/list")
    public ResponseEntity<List<EvalRun>> list() {
        LambdaQueryWrapper<EvalRun> wrapper = new LambdaQueryWrapper<EvalRun>()
            .orderByDesc(EvalRun::getId);
        return ResponseEntity.ok(evalRunMapper.selectList(wrapper));
    }

    /**
     * 运行详情（含聚合指标）
     * POST /api/admin/eval/run/detail
     */
    @PostMapping("/detail")
    public ResponseEntity<EvalRun> detail(@RequestBody Map<String, Long> request) {
        Long runId = request.get("runId");
        if (runId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(evalRunMapper.selectById(runId));
    }

    /**
     * 单问题结果明细
     * POST /api/admin/eval/result/list
     */
    @PostMapping("/result/list")
    public ResponseEntity<List<EvalQuestionResult>> resultList(@RequestBody Map<String, Long> request) {
        Long runId = request.get("runId");
        if (runId == null) return ResponseEntity.badRequest().build();
        LambdaQueryWrapper<EvalQuestionResult> wrapper = new LambdaQueryWrapper<EvalQuestionResult>()
            .eq(EvalQuestionResult::getRunId, runId);
        return ResponseEntity.ok(questionResultMapper.selectList(wrapper));
    }

    /**
     * A/B 对比
     * POST /api/admin/eval/run/compare
     */
    @PostMapping("/compare")
    public ResponseEntity<EvalABTestRunner.ABTestResult> compare(@RequestBody Map<String, String> request) {
        if (pipelineOrchestrator.isRunning()) {
            return ResponseEntity.ok(null);
        }

        String baselineName = request.getOrDefault("baselineName", "baseline");
        String variantName = request.getOrDefault("variantName", "variant-a");

        // 异步启动
        new Thread(() -> {
            EvalABTestRunner.ABTestResult result = abTestRunner.runABTest(baselineName, variantName);
            log.info("A/B 测试完成:\n{}", result);
        }).start();

        return ResponseEntity.ok(null);
    }

    /**
     * 薄弱环节分析 + 优化建议
     * POST /api/admin/eval/run/analyze
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, Long> request) {
        Long runId = request.get("runId");
        if (runId == null) return ResponseEntity.badRequest().build();

        List<EvalWeaknessAnalyzer.DocumentSummary> docSummary = weaknessAnalyzer.analyzeByDocument(runId);
        List<EvalWeaknessAnalyzer.OptimizationAdvice> advice = weaknessAnalyzer.generateAdvice(runId);

        return ResponseEntity.ok(Map.of(
            "documentSummaries", docSummary,
            "advice", advice
        ));
    }

    /**
     * 运行状态查询
     * POST /api/admin/eval/run/status（前端统一用 POST）
     */
    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "running", pipelineOrchestrator.isRunning()
        ));
    }
}
