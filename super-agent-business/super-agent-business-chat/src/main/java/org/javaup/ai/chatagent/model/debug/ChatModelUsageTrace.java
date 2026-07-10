package org.javaup.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次 LLM 调用的用量追踪
 * <p>
 * 记录本轮对话中每次调用 LLM 的详细指标，最终汇总写入 exchange.debugTraceJson。
 *
 * @author wangpeng
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatModelUsageTrace {

    /** 调用发生时的阶段名称（如 "问题改写" / "回答生成"） */
    private String stageName;

    /** LLM 提供商（如 dashscope / openai） */
    private String provider;

    /** 模型名称（如 qwen-max / gpt-4） */
    private String model;

    /** Prompt 消耗的 Token 数 */
    private Integer promptTokens;

    /** 生成内容消耗的 Token 数 */
    private Integer completionTokens;

    /** 总 Token 数 = promptTokens + completionTokens */
    private Integer totalTokens;

    /** 预估费用（美元） */
    private Double estimatedCost;

    /** 本次调用耗时（毫秒） */
    private Long durationMs;

    /** 调用状态（success / error） */
    private String status;
}
