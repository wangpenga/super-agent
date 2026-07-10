package org.javaup.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单轮对话的调用限制统计
 * <p>
 * 记录 LLM 调用次数和工具调用次数的上限及实际用量，
 * 用于防止单轮对话无限循环消耗资源。最终写入 exchange.debugTraceJson。
 *
 * @author wangpeng
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatLimitStats {

    /** 本轮已使用的 LLM 调用次数 */
    private Integer modelCallsUsed;

    /** 单次执行允许的最大 LLM 调用次数 */
    private Integer modelCallsRunLimit;

    /** 整个会话线程允许的最大 LLM 调用次数 */
    private Integer modelCallsThreadLimit;

    /** 本轮已使用的工具调用次数 */
    private Integer toolCallsUsed;

    /** 单次执行允许的最大工具调用次数 */
    private Integer toolCallsRunLimit;

    /** 整个会话线程允许的最大工具调用次数 */
    private Integer toolCallsThreadLimit;

    /** 是否触发了调用限制（true 表示被截断） */
    private boolean limitTriggered;

    /** 触发限制的原因描述 */
    private String limitReason;
}
