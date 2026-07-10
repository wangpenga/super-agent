package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.enums.ChatTurnStatus;

import java.util.Date;
import java.util.List;

/**
 * 对话轮次视图 — exchange 表的业务层模型
 * <p>
 * 对应一次"用户提问 → AI 回答"的完整记录。
 * 从数据库 {@code super_agent_chat_exchange} 表的 JSON 字段反序列化而来，
 * 在内存中作为视图对象传递（比实体类更易读）。
 * <p>
 * 生命周期：
 * <pre>
 * startExchange → status=RUNNING, answer="" (占位)
 *        ↓
 *    流式生成中... (emitModelChunk 实时更新 answerBuffer, 本对象不变)
 *        ↓
 * completeExchange → status=COMPLETED/FAILED/STOPPED, answer 回填
 * </pre>
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExchangeView {

    /** 轮次 ID（雪花算法生成） */
    private long exchangeId;

    /** 用户提问原文 */
    private String question;

    /** 助手完整回答（流式生成完成后回填） */
    private String answer;

    /** 过程提示与思考片段列表（反序列化自 reasoning_note_list JSON） */
    private List<String> thinkingSteps;

    /** 检索引用来源列表（反序列化自 source_snapshot_list JSON，在 finishSuccessfully 中补发给客户端） */
    private List<SearchReference> references;

    /** 推荐追问列表（反序列化自 followup_suggestion_list JSON，在 finishSuccessfully 中补发给客户端） */
    private List<String> recommendations;

    /** 使用的工具名称列表（如 "vector_search", "keyword_search"） */
    private List<String> usedTools;

    /** 调试轨迹快照（反序列化自 debug_trace_json，包含改写/路由/检索/Token 用量等完整调试信息） */
    private ChatDebugTrace debugTrace;

    /**
     * 轮次状态
     * RUNNING: 进行中 / COMPLETED: 已完成 / FAILED: 失败 / STOPPED: 已停止
     */
    private ChatTurnStatus status;

    /** 失败或终止说明（COMPLETED 时为空） */
    private String errorMessage;

    /** 首包耗时（毫秒）— 从请求到 LLM 返回第一个 token 的时间 */
    private Long firstResponseTimeMs;

    /** 总耗时（毫秒）— 从请求到收尾完成的总时间 */
    private Long totalResponseTimeMs;

    /** 创建时间 — startExchange 的时间 */
    private Date createTime;

    /** 编辑时间 — completeExchange 的时间 */
    private Date editTime;
}
