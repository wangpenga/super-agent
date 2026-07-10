package org.javaup.ai.eval.service;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.config.EvalProperties;
import org.javaup.ai.eval.data.EvalDataset;
import org.javaup.ai.eval.data.EvalQuestionResult;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.mapper.EvalQuestionResultMapper;
import org.javaup.ai.eval.mapper.EvalRunMapper;
import org.javaup.ai.eval.metric.ContextPrecisionResult;
import org.javaup.ai.eval.metric.ContextRecallResult;
import org.javaup.ai.eval.metric.RagasMetricCalculator;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.javaup.ai.eval.remote.impl.RagRetrievalRestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 评估管道编排器 —— 评估模块的核心中枢
 * <p>
 * 执行流程：
 * <pre>
 * run(runName)
 *   │
 *   ├─ 1. 创建 EvalRun（status=pending），记录配置快照
 *   ├─ 2. 加载活跃的测试集条目
 *   ├─ 3. 对每条测试数据（可配置并发）：
 *   │     a. 通过 Feign 调主服务检索 API → RetrievalRpcResult
 *   │     b. 记录 retrieval_latency_ms
 *   │     c. RagasMetricCalculator.computeContextPrecision()
 *   │     d. RagasMetricCalculator.computeContextRecall()
 *   │     e. 如有答案，评估 Faithfulness + Answer Relevancy
 *   │     f. 持久化 EvalQuestionResult
 *   ├─ 4. 聚合：avg_context_precision, avg_context_recall, ...
 *   ├─ 5. 更新 EvalRun（status=completed）
 * </pre>
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalPipelineOrchestrator {

    private final RagRetrievalRestClient retrievalClient;
    private final RagasMetricCalculator metricCalculator;
    private final EvalDatasetService datasetService;
    private final EvalRunMapper evalRunMapper;
    private final EvalQuestionResultMapper questionResultMapper;
    private final EvalProperties evalProperties;
    private final EmbeddingModel embeddingModel;

    // 并发评估线程池
    private final ExecutorService evalExecutor = Executors.newFixedThreadPool(8);

    // 正在运行的评估（用于停止）
    private volatile boolean running = false;

    /**
     * 启动一次完整的评估运行
     *
     * @param runName 运行名称，如 "2026-07-10-baseline"
     * @param runType 运行类型：manual / scheduled / ab_test
     * @return 生成的 EvalRun（含最终聚合指标）
     */
    public EvalRun run(String runName, String runType) {
        log.info("========================================");
        log.info("评估运行开始: runName={}, runType={}", runName, runType);
        log.info("========================================");

        // 1. 创建 EvalRun 记录
        EvalRun evalRun = new EvalRun();
        evalRun.setRunName(runName);
        evalRun.setRunType(runType);
        evalRun.setRunStatus(1); // pending
        evalRun.setStartedAt(new Date());
        evalRun.setConfigSnapshot(buildConfigSnapshot());
        evalRunMapper.insert(evalRun);

        final Long runId = evalRun.getId();
        running = true;

        try {
            // 2. 加载活跃测试集
            List<EvalDataset> dataset = datasetService.listActive();
            if (dataset.isEmpty()) {
                log.warn("测试集为空，请先生成数据集");
                evalRun.setRunStatus(4); // failed
                evalRun.setErrorMessage("测试集为空");
                evalRunMapper.updateById(evalRun);
                return evalRun;
            }

            // 更新状态为 running
            evalRun.setRunStatus(2);
            evalRun.setDatasetSize(dataset.size());
            evalRunMapper.updateById(evalRun);

            log.info("加载测试集: {} 条，并发数={}", dataset.size(), evalProperties.getPipeline().getConcurrency());

            // 3. 并发执行每条测试数据的评估
            AtomicInteger completed = new AtomicInteger(0);
            List<CompletableFuture<EvalQuestionResult>> futures = dataset.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> evaluateSingleItem(runId, item), evalExecutor))
                .toList();

            // 等待全部完成
            List<EvalQuestionResult> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        log.error("评估任务异常", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

            running = false;

            // 4. 聚合指标
            aggregateResults(evalRun, results);

            // 5. 更新完成状态
            evalRun.setRunStatus(3); // completed
            evalRun.setCompletedAt(new Date());
            evalRunMapper.updateById(evalRun);

            log.info("========================================");
            log.info("评估运行完成: runName={}, avgPrecision={}, avgRecall={}, avgFaithfulness={}, avgRelevancy={}",
                runName,
                evalRun.getAvgContextPrecision(),
                evalRun.getAvgContextRecall(),
                evalRun.getAvgFaithfulness(),
                evalRun.getAvgAnswerRelevancy());
            log.info("========================================");

        } catch (Exception e) {
            running = false;
            log.error("评估运行异常中断: runName={}", runName, e);
            evalRun.setRunStatus(4); // failed
            evalRun.setErrorMessage(e.getMessage());
            evalRunMapper.updateById(evalRun);
        }

        return evalRunMapper.selectById(runId);
    }

    /**
     * 停止正在运行的评估
     */
    public void stop() {
        running = false;
        log.info("评估运行已停止（下次任务检查时会跳过）");
    }

    /**
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    // ──────────────────────────────────────────────
    // 单条测试数据评估
    // ──────────────────────────────────────────────

    /**
     * 评估单条测试数据
     */
    private EvalQuestionResult evaluateSingleItem(Long runId, EvalDataset dataset) {
        if (!running) {
            log.debug("评估已被停止，跳过: datasetId={}", dataset.getId());
            return null;
        }

        long startTime = System.currentTimeMillis();
        EvalQuestionResult result = new EvalQuestionResult();
        result.setRunId(runId);
        result.setDatasetId(dataset.getId());
        result.setDocumentId(dataset.getDocumentId());
        result.setQuestion(dataset.getQuestion());

        try {
            // a. 调用主服务检索
            RetrievalRpcResult retrievalResult = retrievalClient.retrieve(
                dataset.getDocumentId(), dataset.getQuestion());

            long latency = System.currentTimeMillis() - startTime;
            result.setRetrievalLatencyMs(latency);

            List<RetrievalRpcResult.DocumentResult> allDocs = retrievalResult.flattenDocuments();

            // 记录检索到的 chunk IDs
            List<Long> retrievedChunkIds = allDocs.stream()
                .map(RetrievalRpcResult.DocumentResult::getChunkId)
                .filter(Objects::nonNull)
                .toList();
            result.setRetrievedChunkIds(JSONUtil.toJsonStr(retrievedChunkIds));
            result.setFinalTopK(allDocs.size());

            log.debug("评估中: datasetId={}, docs={}, latency={}ms",
                dataset.getId(), allDocs.size(), latency);

            // b. 计算 Context Precision
            ContextPrecisionResult precision = metricCalculator.computeContextPrecision(
                dataset.getQuestion(), allDocs);
            result.setContextPrecision(BigDecimal.valueOf(precision.getScore())
                .setScale(6, RoundingMode.HALF_UP));

            // 保存相关性判断明细
            List<Map<String, Object>> judgmentDetails = precision.getJudgments().stream()
                .map(j -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunkId", j.getChunkId());
                    m.put("rank", j.getRank());
                    m.put("relevant", j.isRelevant());
                    m.put("method", j.getMethod());
                    m.put("rerankScore", j.getRerankScore());
                    m.put("reason", j.getReason());
                    return m;
                })
                .toList();
            result.setRelevanceJudgments(JSONUtil.toJsonStr(judgmentDetails));

            // c. 计算 Context Recall
            ContextRecallResult recall = metricCalculator.computeContextRecall(
                dataset.getGroundTruthChunkIds(), allDocs);
            result.setContextRecall(BigDecimal.valueOf(recall.getScore())
                .setScale(6, RoundingMode.HALF_UP));

            // d. 如果有答案（来自对话日志的数据集），评估 Faithfulness + Answer Relevancy
            if (dataset.getSource() != null && dataset.getSource().contains("conversation")) {
                // 注意：答案文本需要在创建数据集时一并保存
                // 此处只预留扩展点，实际使用时需要从对话日志获取完整答案
                String evidenceText = metricCalculator.buildEvidenceText(allDocs);
                if (evidenceText != null && !evidenceText.isBlank()) {
                    // Faithfulness: 需要答案文本，从 dataset 的扩展字段或关联查询获取
                    // 先留空，后续可通过 exchangeId 关联查询
                    if (dataset.getExchangeId() != null) {
                        // TODO: 通过 exchangeId 查询对话日志获取完整答案
                        log.debug("datasetId={} 有 exchangeId={}，可补充 Faithfulness 评估",
                            dataset.getId(), dataset.getExchangeId());
                    }
                }
            }

        } catch (Exception e) {
            log.error("单条评估失败: datasetId={}, question='{}'",
                dataset.getId(), truncate(dataset.getQuestion(), 50), e);
            result.setContextPrecision(BigDecimal.ZERO);
            result.setContextRecall(BigDecimal.ZERO);
        }

        // 持久化
        result.setStatus(1);
        questionResultMapper.insert(result);
        return result;
    }

    // ──────────────────────────────────────────────
    // 聚合
    // ──────────────────────────────────────────────

    /**
     * 汇总所有单条结果到 EvalRun
     */
    private void aggregateResults(EvalRun evalRun, List<EvalQuestionResult> results) {
        if (results.isEmpty()) return;

        DoubleSummaryStatistics precisionStats = results.stream()
            .filter(r -> r.getContextPrecision() != null)
            .collect(Collectors.summarizingDouble(r -> r.getContextPrecision().doubleValue()));

        DoubleSummaryStatistics recallStats = results.stream()
            .filter(r -> r.getContextRecall() != null)
            .collect(Collectors.summarizingDouble(r -> r.getContextRecall().doubleValue()));

        DoubleSummaryStatistics faithfulnessStats = results.stream()
            .filter(r -> r.getFaithfulness() != null)
            .collect(Collectors.summarizingDouble(r -> r.getFaithfulness().doubleValue()));

        DoubleSummaryStatistics relevancyStats = results.stream()
            .filter(r -> r.getAnswerRelevancy() != null)
            .collect(Collectors.summarizingDouble(r -> r.getAnswerRelevancy().doubleValue()));

        DoubleSummaryStatistics latencyStats = results.stream()
            .filter(r -> r.getRetrievalLatencyMs() != null)
            .collect(Collectors.summarizingDouble(r -> r.getRetrievalLatencyMs()));

        evalRun.setAvgContextPrecision(
            BigDecimal.valueOf(precisionStats.getAverage()).setScale(6, RoundingMode.HALF_UP));
        evalRun.setAvgContextRecall(
            BigDecimal.valueOf(recallStats.getAverage()).setScale(6, RoundingMode.HALF_UP));
        evalRun.setAvgFaithfulness(
            BigDecimal.valueOf(faithfulnessStats.getAverage()).setScale(6, RoundingMode.HALF_UP));
        evalRun.setAvgAnswerRelevancy(
            BigDecimal.valueOf(relevancyStats.getAverage()).setScale(6, RoundingMode.HALF_UP));
        evalRun.setAvgLatencyMs((long) latencyStats.getAverage());
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    /**
     * 构建运行时配置快照（JSON）
     */
    private String buildConfigSnapshot() {
        // 将当前 evalProperties 序列化为 JSON 快照
        // （仅保留关键配置，避免序列化错误）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("judge.rerankThresholdHigh", evalProperties.getJudge().getRerankThresholdHigh());
        snapshot.put("judge.rerankThresholdLow", evalProperties.getJudge().getRerankThresholdLow());
        snapshot.put("judge.judgeModel", evalProperties.getJudge().getJudgeModel());
        snapshot.put("pipeline.concurrency", evalProperties.getPipeline().getConcurrency());
        snapshot.put("pipeline.timeoutPerQuestionMs", evalProperties.getPipeline().getTimeoutPerQuestionMs());
        return JSONUtil.toJsonPrettyStr(snapshot);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
