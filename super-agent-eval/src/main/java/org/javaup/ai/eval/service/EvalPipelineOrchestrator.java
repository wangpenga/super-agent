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
import org.javaup.ai.eval.metric.AnswerAccuracyResult;
import org.javaup.ai.eval.metric.AnswerRelevancyResult;
import org.javaup.ai.eval.metric.ContextPrecisionResult;
import org.javaup.ai.eval.metric.ContextRecallResult;
import org.javaup.ai.eval.metric.FaithfulnessResult;
import org.javaup.ai.eval.metric.RagasMetricCalculator;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.javaup.ai.eval.remote.impl.RagRetrievalRestClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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
    private final ChatModel chatModel;

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
            log.info("评估运行完成: runName={}, avgPrecision={}, avgRecall={}, avgFaithfulness={}, avgRelevancy={}, avgAccuracy={}",
                runName,
                evalRun.getAvgContextPrecision(),
                evalRun.getAvgContextRecall(),
                evalRun.getAvgFaithfulness(),
                evalRun.getAvgAnswerRelevancy(),
                evalRun.getAvgAnswerAccuracy());
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
     * <p>
     * 用户只需提供 question + referenceAnswer，不需要知道文档ID。
     * 有 documentId → 算全部指标（Precision/Recall/Faithfulness）
     * 无 documentId → 只算 Accuracy 和 AnswerRelevancy
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
            Long docId = dataset.getDocumentId();
            String referenceAnswer = dataset.getReferenceAnswer();

            // ────────── Step 1: 调聊天接口获取生成答案 + 检索证据 ──────────
            // 从 SSE 的 text 事件拿答案，从 references 事件拿检索证据
            // 有 docId → DOCUMENT 模式，无 → AUTO_DOCUMENT 模式
            var chatResult = retrievalClient.chatAnswerWithEvidence(docId, dataset.getQuestion());
            String generatedAnswer = chatResult.getAnswer();
            String evidenceText = chatResult.buildEvidenceText();
            result.setAnswer(generatedAnswer != null ? generatedAnswer : "");

            long latency = System.currentTimeMillis() - startTime;
            result.setRetrievalLatencyMs(latency);

            if (result.getAnswer().isBlank()) {
                log.warn("datasetId={} 聊天接口未返回有效回答", dataset.getId());
            }

            // 检测是否为澄清响应（AUTO_DOCUMENT 置信度不足时返回）
            // 如果是澄清，跳过所有指标计算
            boolean isClarification = !chatResult.hasEvidence()
                && (generatedAnswer.contains("文档范围歧义")
                    || generatedAnswer.contains("候选文档")
                    || generatedAnswer.contains("避免误选"));

            if (isClarification) {
                log.info("datasetId={} AUTO_DOCUMENT 返回澄清而非答案，跳过指标计算", dataset.getId());
            }

            // ────────── Step 2: Precision/Recall/Faithfulness（从 SSE reference 取证）──────────
            // 从 chat SSE 的 reference 事件拿证据，有 docId 时一定会有
            if (!isClarification && chatResult.hasEvidence()) {
                ContextPrecisionResult precision = metricCalculator.computeContextPrecision(
                    dataset.getQuestion(), referenceAnswer, convertEvidenceChunks(chatResult.getEvidenceChunks()));
                result.setContextPrecision(toBigDecimal(precision.getScore()));
            }

            if (referenceAnswer != null && !referenceAnswer.isBlank()
                && evidenceText != null && !evidenceText.isBlank()) {
                ContextRecallResult recall = metricCalculator.computeContextRecall(
                    dataset.getQuestion(), referenceAnswer, evidenceText);
                result.setContextRecall(toBigDecimal(recall.getScore()));
            }

            if (generatedAnswer != null && !generatedAnswer.isBlank()
                && evidenceText != null && !evidenceText.isBlank()) {
                FaithfulnessResult faithfulness = metricCalculator.computeFaithfulness(
                    generatedAnswer, evidenceText);
                result.setFaithfulness(toBigDecimal(faithfulness.getScore()));
            }
            // ────────── Step 3: Answer Relevancy（非澄清时才有效）──────────
            AnswerRelevancyResult relevancy = AnswerRelevancyResult.ZERO;
            if (!isClarification && generatedAnswer != null && !generatedAnswer.isBlank()) {
                try {
                    relevancy = metricCalculator.computeAnswerRelevancy(
                        dataset.getQuestion(), generatedAnswer, embeddingModel::embed);
                } catch (Exception e) {
                    log.warn("AnswerRelevancy 计算失败: datasetId={}", dataset.getId(), e);
                }
            }
            result.setAnswerRelevancy(toBigDecimal(relevancy.getScore()));

            // ────────── Step 4: Answer Accuracy（非澄清时才有效）──────────
            AnswerAccuracyResult accuracy = AnswerAccuracyResult.ZERO;
            if (!isClarification && generatedAnswer != null && !generatedAnswer.isBlank()
                && referenceAnswer != null && !referenceAnswer.isBlank()) {
                try {
                    accuracy = metricCalculator.computeAnswerAccuracy(
                        dataset.getQuestion(), generatedAnswer, referenceAnswer);
                } catch (Exception e) {
                    log.warn("AnswerAccuracy 计算失败: datasetId={}", dataset.getId(), e);
                }
            }
            result.setAnswerAccuracy(toBigDecimal(accuracy.getScore()));

        } catch (Exception e) {
            log.error("单条评估失败: datasetId={}, question='{}'",
                dataset.getId(), truncate(dataset.getQuestion(), 50), e);
        }

        // 持久化
        result.setStatus(1);
        questionResultMapper.insert(result);
        return result;
    }

    private List<RetrievalRpcResult.DocumentResult> convertEvidenceChunks(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        List<RetrievalRpcResult.DocumentResult> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievalRpcResult.DocumentResult doc = new RetrievalRpcResult.DocumentResult();
            doc.setId(String.valueOf(i + 1));
            doc.setText(chunks.get(i));
            docs.add(doc);
        }
        return docs;
    }

    private BigDecimal toBigDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
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

        DoubleSummaryStatistics accuracyStats = results.stream()
            .filter(r -> r.getAnswerAccuracy() != null)
            .collect(Collectors.summarizingDouble(r -> r.getAnswerAccuracy().doubleValue()));

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
        evalRun.setAvgAnswerAccuracy(
            BigDecimal.valueOf(accuracyStats.getAverage()).setScale(6, RoundingMode.HALF_UP));
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
