package org.javaup.ai.eval.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单问题评估结果 —— 一次评估运行中单个问题的全部指标
 * <p>
 * 包含检索指标（Context Precision / Recall）和生成指标（Faithfulness / Answer Relevancy）。
 * 通过 {relevanceJudgments} 字段可以回溯 LLM Judge 的逐条判断明细。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_eval_question_result")
@EqualsAndHashCode(callSuper = true)
public class EvalQuestionResult extends BaseEvalEntity {

    /** 主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联的评估运行 ID */
    private Long runId;

    /** 关联的测试集条目 ID */
    private Long datasetId;

    /** 文档 ID */
    private Long documentId;

    /** 问题文本 */
    private String question;

    /** Context Precision 分数（0~1） */
    private BigDecimal contextPrecision;

    /** Context Recall 分数（0~1） */
    private BigDecimal contextRecall;

    /** Faithfulness 分数（0~1） */
    private BigDecimal faithfulness;

    /** Answer Relevancy 分数（0~1） */
    private BigDecimal answerRelevancy;

    /** 检索阶段耗时（毫秒） */
    private Long retrievalLatencyMs;

    /**
     * 相关性判断明细，JSON 数组
     * [{"chunkId": 1, "relevant": true, "score": 0.85, "method": "rerank"}, ...]
     */
    private String relevanceJudgments;

    /** 生成的答案文本（来源对话日志时有值，用于 Faithfulness 评估） */
    private String answer;

    /** 最终选入 Prompt 的引用数 */
    private Integer finalTopK;

    /** 检索到的 chunk ID 列表（JSON 数组） */
    private String retrievedChunkIds;
}
