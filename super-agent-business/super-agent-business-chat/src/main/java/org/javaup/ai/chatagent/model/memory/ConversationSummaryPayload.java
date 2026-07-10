package org.javaup.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期会话摘要的结构化载体
 * <p>
 * 这是 memory_summary 表 summary_json 字段的 Java 模型。
 * LLM 在记忆压缩时生成：将 N 轮对话提炼为结构化的会话状态，
 * 供后续问答中的路由决策使用。
 *
 * @author wangpeng
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummaryPayload {

    /** 摘要全文（给 LLM 看的自然语言版本） */
    private String summary;

    /** 会话目标：用户想达成的最终目的（如"了解项目开发规范"） */
    private String conversationGoal;

    /** 已确认事实：对话中已经达成共识的信息列表 */
    @Builder.Default
    private List<String> stableFacts = new ArrayList<>();

    /** 用户偏好：从对话中推断出的用户倾向/习惯 */
    @Builder.Default
    private List<String> userPreferences = new ArrayList<>();

    /** 已解决要点：对话中已经回答清楚的问题 */
    @Builder.Default
    private List<String> resolvedPoints = new ArrayList<>();

    /** 待跟进问题：用户提过但尚未完全解答的疑问 */
    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();

    /** 检索提示：从对话中提取的关键词/概念，用于优化后续检索 */
    @Builder.Default
    private List<String> retrievalHints = new ArrayList<>();
}
