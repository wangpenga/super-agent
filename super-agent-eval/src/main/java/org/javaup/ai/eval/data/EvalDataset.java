package org.javaup.ai.eval.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 评估测试集条目 —— 一个「问题 + 期望命中的 chunk 列表」对
 * <p>
 * 这是 Context Recall 计算的 ground truth 依据。
 * 数据来源优先级：真实对话日志 > 文档画像问题 > LLM 补充生成 > 手工标注。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_eval_dataset")
@EqualsAndHashCode(callSuper = true)
public class EvalDataset extends BaseEvalEntity {

    /** 主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文档 ID */
    private Long documentId;

    /** 测试问题 */
    private String question;

    /**
     * 问题来源
     * conversation_log — 从真实对话日志中提取
     * profile — 从文档画像 example_questions 提取
     * llm_generated — LLM 基于文档摘要补充生成
     * manual — 手工导入/标注
     */
    private String source;

    /** 相关 chunk ID 列表，JSON 数组格式 [1,2,3] */
    private String groundTruthChunkIds;

    /** 相关父块 ID 列表，JSON 数组格式（可选） */
    private String groundTruthParentBlockIds;

    /** 难度：easy / medium / hard */
    private String difficulty;

    /** 标签，逗号分隔 */
    private String tags;

    /** 是否激活：1=参与评估 0=跳过 */
    private Integer isActive;

    /** 来源对话的 exchange_id（source=conversation_log 时） */
    private Long exchangeId;
}
