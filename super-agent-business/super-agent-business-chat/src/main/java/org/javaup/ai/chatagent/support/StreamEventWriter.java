package org.javaup.ai.chatagent.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.model.SearchReference;
import org.springframework.stereotype.Component;

/**
 * SSE 流式事件写入器 - 调用链路中的事件序列化层
 * <p>
 * <b>在整个调用链路中的角色：</b>
 * 将业务数据（文本块、思考状态、错误信息、引用、推荐）包装为 JSON 格式的 SSE 事件字符串。
 * <p>
 * <b>SSE 事件类型：</b>
 * <ul>
 *   <li><b>text</b>：LLM 流式输出的文本增量块（最频繁的事件类型）</li>
 *   <li><b>thinking</b>：系统思考状态（如 "正在分析问题上下文"）</li>
 *   <li><b>status</b>：系统状态通知（如 "⏹ 用户已停止生成"）</li>
 *   <li><b>error</b>：错误信息（如 "该会话当前正在执行中"）</li>
 *   <li><b>reference</b>：检索引用列表（在流式回答完成后补发）</li>
 *   <li><b>recommend</b>：推荐追问列表（在流式回答完成后补发）</li>
 * </ul>
 * <p>
 * <b>事件 JSON 结构：</b>
 * <pre>
 * {
 *   "type": "text" | "thinking" | "status" | "error" | "reference" | "recommend",
 *   "content": "事件内容（文本、对象或列表）",
 *   "timestamp": "2026-06-25T10:30:00.000Z",
 *   "conversationId": "abc123",   // 可选，来自 StreamEventMetadata
 *   "exchangeId": 1               // 可选，来自 StreamEventMetadata
 * }
 * </pre>
 * <p>
 * <b>数据流方向：</b>
 * Executor 产生数据 → StreamEventWriter 包装为 JSON → Sink.emitNext → Flux → SSE 连接 → 客户端
 *
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: SSE 事件写入器 - 将业务数据包装为 JSON SSE 事件并序列化
 * @author: wangpeng
 **/
@Component
public class StreamEventWriter {

    private final ObjectMapper objectMapper;

    public StreamEventWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写入 LLM 流式文本块事件（最频繁的 SSE 事件类型）
     * <p>
     * 在 emitModelChunk 中被调用，每条 LLM 输出的增量文本块都会生成一个 text 事件。
     * 客户端累积这些文本块来实时显示 AI 回答。
     */
    public String text(String content) {
        return text(content, null);
    }

    public String text(String content, StreamEventMetadata metadata) {
        return write(event("text", content, metadata));
    }

    /**
     * 写入思考状态事件
     * <p>
     * 在执行的关键阶段发送，告知用户系统当前正在做什么。
     * 例如："正在分析问题上下文"、"正在根据问题规划知识检索范围"。
     */
    public String thinking(String content) {
        return thinking(content, null);
    }

    public String thinking(String content, StreamEventMetadata metadata) {
        return write(event("thinking", content, metadata));
    }

    /**
     * 写入状态通知事件
     * <p>
     * 用于通知用户系统的状态变化，如 "⏹ 用户已停止生成"。
     */
    public String status(String content) {
        return status(content, null);
    }

    public String status(String content, StreamEventMetadata metadata) {
        return write(event("status", content, metadata));
    }

    /**
     * 写入错误事件
     * <p>
     * 在启动失败、执行异常、拒绝请求等场景下发送。
     * 客户端收到 error 事件后通常会显示错误提示并停止等待。
     */
    public String error(String content) {
        return error(content, null);
    }

    public String error(String content, StreamEventMetadata metadata) {
        return write(event("error", content, metadata));
    }

    /**
     * 写入检索引用列表事件
     * <p>
     * 在流式回答完成后（finishSuccessfully）补发，包含所有检索到的文档引用。
     * 客户端可用这些引用来展示"参考来源"板块。
     * payload 中额外包含 "count" 字段表示引用数量。
     */
    public String references(List<SearchReference> references) {
        return references(references, null);
    }

    public String references(List<SearchReference> references, StreamEventMetadata metadata) {
        Map<String, Object> payload = event("reference", references, metadata);
        payload.put("count", references != null ? references.size() : 0);
        return write(payload);
    }

    /**
     * 写入推荐追问列表事件
     * <p>
     * 在流式回答完成后补发，由 recommendationService 生成的推荐追问。
     * 客户端可用这些内容展示"猜你想问"板块。
     * payload 中额外包含 "count" 字段表示推荐数量。
     */
    public String recommendations(List<String> recommendations) {
        return recommendations(recommendations, null);
    }

    public String recommendations(List<String> recommendations, StreamEventMetadata metadata) {
        Map<String, Object> payload = event("recommend", recommendations, metadata);
        payload.put("count", recommendations != null ? recommendations.size() : 0);
        return write(payload);
    }

    /**
     * 构建 SSE 事件的基础 JSON 结构
     * <p>
     * 每个事件包含：
     * <ul>
     *   <li>type: 事件类型标识</li>
     *   <li>content: 事件内容</li>
     *   <li>timestamp: ISO 8601 时间戳</li>
     *   <li>conversationId / exchangeId: 会话/轮次标识（来自 StreamEventMetadata），用于客户端路由</li>
     * </ul>
     */
    private Map<String, Object> event(String type, Object content, StreamEventMetadata metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("content", content);
        payload.put("timestamp", Instant.now().toString());
        if (metadata != null) {
            // 注入会话和轮次元数据，客户端可据此进行事件路由
            if (metadata.conversationId() != null && !metadata.conversationId().isBlank()) {
                payload.put("conversationId", metadata.conversationId());
            }
            if (metadata.exchangeId() != null && metadata.exchangeId() > 0) {
                payload.put("exchangeId", metadata.exchangeId());
            }
        }
        return payload;
    }

    /**
     * 将事件 Map 序列化为 JSON 字符串
     * <p>
     * 使用 Jackson ObjectMapper 进行序列化，每个事件是一行完整的 JSON。
     * SSE 协议下，Spring WebFlux 会自动在前端拼接 "data:" 前缀和 "\n\n" 分隔符。
     */
    private String write(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("流式事件序列化失败", exception);
        }
    }
}
