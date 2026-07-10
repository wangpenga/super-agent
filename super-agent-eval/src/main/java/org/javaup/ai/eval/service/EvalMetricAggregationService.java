package org.javaup.ai.eval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalMetricDaily;
import org.javaup.ai.eval.data.EvalQuestionResult;
import org.javaup.ai.eval.data.EvalRun;
import org.javaup.ai.eval.mapper.EvalMetricDailyMapper;
import org.javaup.ai.eval.mapper.EvalQuestionResultMapper;
import org.javaup.ai.eval.mapper.EvalRunMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 指标汇总服务 —— 将评估运行数据聚合到日汇总表
 * <p>
 * 每次运行完成后调用 {@link #aggregateDaily(EvalRun)}，
 * 更新 {@code super_agent_eval_metric_daily} 表，供 Dashboard 趋势图使用。
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalMetricAggregationService {

    private final EvalRunMapper evalRunMapper;
    private final EvalQuestionResultMapper questionResultMapper;
    private final EvalMetricDailyMapper metricDailyMapper;

    /**
     * 将指定运行的结果聚合到日汇总
     */
    public void aggregateDaily(EvalRun evalRun) {
        if (evalRun == null || evalRun.getId() == null) return;

        // 获取该运行的所有问题结果
        LambdaQueryWrapper<EvalQuestionResult> wrapper = new LambdaQueryWrapper<EvalQuestionResult>()
            .eq(EvalQuestionResult::getRunId, evalRun.getId());
        List<EvalQuestionResult> results = questionResultMapper.selectList(wrapper);

        if (results.isEmpty()) return;

        Date metricDate = evalRun.getStartedAt() != null
            ? evalRun.getStartedAt()
            : new Date();

        // 计算各指标的分位数
        aggregateSingleMetric("context_precision", results.stream()
            .map(EvalQuestionResult::getContextPrecision)
            .filter(Objects::nonNull)
            .toList(), metricDate, results.size(), 1);

        aggregateSingleMetric("context_recall", results.stream()
            .map(EvalQuestionResult::getContextRecall)
            .filter(Objects::nonNull)
            .toList(), metricDate, results.size(), 1);

        aggregateSingleMetric("faithfulness", results.stream()
            .map(EvalQuestionResult::getFaithfulness)
            .filter(Objects::nonNull)
            .toList(), metricDate, results.size(), 1);

        aggregateSingleMetric("answer_relevancy", results.stream()
            .map(EvalQuestionResult::getAnswerRelevancy)
            .filter(Objects::nonNull)
            .toList(), metricDate, results.size(), 1);

        log.info("日汇总完成: date={}, runId={}, samples={}", metricDate, evalRun.getId(), results.size());
    }

    /**
     * 聚合单个指标
     */
    private void aggregateSingleMetric(String metricName, List<BigDecimal> values,
                                        Date date, int sampleCount, int runCount) {
        if (values.isEmpty()) return;

        List<BigDecimal> sorted = values.stream()
            .sorted()
            .toList();

        int n = sorted.size();
        BigDecimal avg = sorted.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
        BigDecimal min = sorted.get(0);
        BigDecimal max = sorted.get(n - 1);
        BigDecimal p50 = sorted.get(n / 2);
        BigDecimal p90 = sorted.get((int) (n * 0.9));

        // 检查当天是否已有记录
        LocalDate localDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        java.util.Date sqlDate = java.sql.Date.valueOf(localDate);

        LambdaQueryWrapper<EvalMetricDaily> queryWrapper = new LambdaQueryWrapper<EvalMetricDaily>()
            .eq(EvalMetricDaily::getMetricDate, sqlDate)
            .eq(EvalMetricDaily::getMetricName, metricName);

        EvalMetricDaily existing = metricDailyMapper.selectOne(queryWrapper);

        if (existing != null) {
            // 更新已有记录（加权平均）
            int totalSamples = existing.getSampleCount() + sampleCount;
            int totalRuns = existing.getRunCount() + runCount;

            BigDecimal weightedAvg = existing.getAvgValue()
                .multiply(BigDecimal.valueOf(existing.getSampleCount()))
                .add(avg.multiply(BigDecimal.valueOf(sampleCount)))
                .divide(BigDecimal.valueOf(totalSamples), 6, RoundingMode.HALF_UP);

            existing.setAvgValue(weightedAvg);
            existing.setMinValue(min.compareTo(existing.getMinValue()) < 0 ? min : existing.getMinValue());
            existing.setMaxValue(max.compareTo(existing.getMaxValue()) > 0 ? max : existing.getMaxValue());
            existing.setP50(p50);
            existing.setP90(p90);
            existing.setSampleCount(totalSamples);
            existing.setRunCount(totalRuns);
            metricDailyMapper.updateById(existing);
        } else {
            // 新建记录
            EvalMetricDaily daily = new EvalMetricDaily();
            daily.setMetricDate(sqlDate);
            daily.setMetricName(metricName);
            daily.setAvgValue(avg);
            daily.setP50(p50);
            daily.setP90(p90);
            daily.setMinValue(min);
            daily.setMaxValue(max);
            daily.setSampleCount(sampleCount);
            daily.setRunCount(runCount);
            daily.setStatus(1);
            metricDailyMapper.insert(daily);
        }
    }

    /**
     * 获取趋势数据
     */
    public List<EvalMetricDaily> getTrend(String metricName, int days) {
        LocalDate since = LocalDate.now().minusDays(days);
        LambdaQueryWrapper<EvalMetricDaily> wrapper = new LambdaQueryWrapper<EvalMetricDaily>()
            .eq(metricName != null, EvalMetricDaily::getMetricName, metricName)
            .ge(EvalMetricDaily::getMetricDate, java.sql.Date.valueOf(since))
            .eq(EvalMetricDaily::getStatus, 1)
            .orderByAsc(EvalMetricDaily::getMetricDate);
        return metricDailyMapper.selectList(wrapper);
    }
}
