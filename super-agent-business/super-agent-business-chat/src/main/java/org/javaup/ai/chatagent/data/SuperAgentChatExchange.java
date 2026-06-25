package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * 业务对话轮次归档表 (super_agent_chat_exchange)
 * <p>
 * 记录会话中每一轮问答的完整信息，包括用户提问、助手回答、思考过程、
 * 引用来源、推荐追问、工具调用轨迹、调试信息、执行状态和耗时指标。
 * 一轮对话 = 一次用户提问 + 一次 AI 回答。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_exchange")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatExchange extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属业务会话编号 (dialogue_code) */
    @TableField("dialogue_code")
    private String conversationId;

    /** 用户提问 (user_prompt) */
    @TableField("user_prompt")
    private String question;

    /** 助手回答内容 (reply_content) */
    @TableField("reply_content")
    private String answer;

    /** 过程提示与思考片段，JSON 格式 (reasoning_note_list) */
    @TableField("reasoning_note_list")
    private String thinkingSteps;

    /** 引用来源快照，JSON 格式 (source_snapshot_list) */
    @TableField("source_snapshot_list")
    private String referenceList;

    /** 推荐追问快照，JSON 格式 (followup_suggestion_list) */
    @TableField("followup_suggestion_list")
    private String recommendationList;

    /** 工具使用轨迹快照，JSON 格式 (tool_trace_list) */
    @TableField("tool_trace_list")
    private String usedToolList;

    /** 调试轨迹快照，JSON 格式，包含检索备注、使用通道、Token 用量等 (debug_trace_json) */
    @TableField("debug_trace_json")
    private String debugTraceJson;

    /**
     * 轮次状态 (exchange_state)
     * 1:进行中 2:已完成 3:失败 4:已停止
     */
    @TableField("exchange_state")
    private Integer turnStatus;

    /** 失败或终止说明 (finish_note) */
    @TableField("finish_note")
    private String errorMessage;

    /** 首包耗时，毫秒 (first_token_latency_ms) */
    @TableField("first_token_latency_ms")
    private Long firstResponseTimeMs;

    /** 总耗时，毫秒 (total_latency_ms) */
    @TableField("total_latency_ms")
    private Long totalResponseTimeMs;
}
