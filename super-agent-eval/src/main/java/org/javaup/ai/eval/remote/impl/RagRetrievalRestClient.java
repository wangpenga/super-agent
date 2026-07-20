package org.javaup.ai.eval.remote.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.config.EvalProperties;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主服务检索 API 的 RestTemplate 客户端
 * <p>
 * 通过 HTTP 调用主服务的 {@code /api/internal/rag/retrieve} 接口，
 * 将返回的 JSON 反序列化为 {@link RetrievalRpcResult}。
 * <p>
 * 设计上保持无状态，方便并发调用。
 *
 * @author wangpeng
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetrievalRestClient {

    private static final Pattern DOCUMENT_NAME_PATTERN = Pattern.compile("[\u300a]([^\u300b]+)[\u300b]");

    private final RestTemplate restTemplate;
    private final EvalProperties evalProperties;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 调用主服务的纯检索接口
     *
     * @param documentId 文档 ID
     * @param question   检索问题
     * @return 检索结果（含 rerank 分数），失败时返回空结果
     */
    public RetrievalRpcResult retrieve(Long documentId, String question) {
        String url = evalProperties.getChatService().getUrl()
            + evalProperties.getChatService().getRetrieveApi();

        // 构造请求体 —— 仅携带评估所需的最少字段
        var request = new RetrieveRequest(documentId, question);

        try {
            log.debug("调用主服务检索 API: documentId={}, question='{}'", documentId, truncate(question, 50));
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                RetrievalRpcResult result = JSONUtil.toBean(response.getBody(), RetrievalRpcResult.class);
                log.debug("检索成功: question='{}', subQuestions={}, totalDocs={}",
                    truncate(question, 50),
                    result.getSubQuestions() != null ? result.getSubQuestions().size() : 0,
                    result.flattenDocuments().size());
                return result;
            } else {
                log.warn("主服务检索 API 返回非成功状态: code={}, body={}", response.getStatusCode(), response.getBody());
                return emptyResult(question);
            }
        } catch (Exception e) {
            log.error("调用主服务检索 API 失败: documentId={}, question='{}'", documentId, truncate(question, 50), e);
            return emptyResult(question);
        }
    }

    /**
     * 调用主服务的真实聊天接口（SSE），获取完整回答
     * <p>
     * 这是评估生成答案的正确方式——走和用户聊天一模一样的完整链路：
     * 问题改写 → 文档路由 → 多通道检索 → RRF融合 → Rerank → Prompt组装 → LLM生成
     * <p>
     * 缺点是要等 LLM 生成完才能拿到完整回答，比纯检索慢，但结果真实可靠。
     *
     * @param documentId 文档 ID
     * @param question   用户问题
     * @return AI 生成的完整回答文本，失败时返回空字符串
     */
    public String chatAnswer(Long documentId, String question) {
        String baseUrl = evalProperties.getChatService().getUrl();
        String url = baseUrl + "/api/chat/stream";

        // 构造请求体（和前端调用完全一致）
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("conversationId", UUID.randomUUID().toString().replace("-", ""));
        if (documentId != null) {
            request.put("chatMode", "DOCUMENT");
            request.put("selectedDocumentId", String.valueOf(documentId));
        } else {
            request.put("chatMode", "AUTO_DOCUMENT");
        }

        try {
            log.info("调用主服务聊天接口: documentId={}, question='{}'", documentId, truncate(question, 50));
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("聊天接口返回非成功状态: code={}", response.getStatusCode());
                return "";
            }

            // 解析 SSE 流：每行 data: {...}，累加 type=text 事件的内容
            String body = response.getBody();
            return parseSseResponse(body);
        } catch (Exception e) {
            log.error("调用主服务聊天接口失败: documentId={}, question='{}'", documentId, truncate(question, 50), e);
            return "";
        }
    }

    /**
     * 带有检索证据的聊天回答
     * <p>
     * 自动处理澄清流程：
     * 1. 先以 AUTO_DOCUMENT 模式调用
     * 2. 如果返回澄清（低置信度 + 候选文档列表），自动解析文档名并查询数据库
     * 3. 以 DOCUMENT 模式 + 解析到的文档 ID 重试
     * 整个流程对用户透明，无需提供 documentId
     */
    public ChatAnswerResult chatAnswerWithEvidence(Long documentId, String question) {
        String baseUrl = evalProperties.getChatService().getUrl();
        String url = baseUrl + "/api/chat/stream";
        String conversationId = UUID.randomUUID().toString().replace("-", "");

        // 有 documentId 直接用 DOCUMENT 模式
        if (documentId != null) {
            return callChatApi(url, conversationId, "DOCUMENT", question, documentId);
        }

        // 无 documentId：先走 AUTO_DOCUMENT
        log.info("AUTO_DOCUMENT 评估: question='{}'", truncate(question, 50));
        ChatAnswerResult result = callChatApi(url, conversationId, "AUTO_DOCUMENT", question, null);

        // 如果返回了证据（有 reference 事件），说明路由成功，直接返回
        if (result.hasEvidence()) {
            log.info("AUTO_DOCUMENT 路由成功: question='{}', evidence={}", truncate(question, 50), result.getEvidenceChunks().size());
            return result;
        }

        // 检测是否为澄清响应
        String answer = result.getAnswer();
        boolean isClarification = answer != null && (answer.contains("文档范围歧义")
            || answer.contains("候选文档")
            || answer.contains("避免误选"));

        if (!isClarification) {
            // 不是澄清但无证据（如兜底回复），直接返回
            return result;
        }

        // 自动处理澄清：解析文档名 → 查数据库 → 用 DOCUMENT 模式重试
        log.info("AUTO_DOCUMENT 返回澄清，自动解析文档名并重试: question='{}'", truncate(question, 50));
        List<Long> resolvedDocIds = resolveDocumentIds(answer);
        if (resolvedDocIds.isEmpty()) {
            log.warn("无法从澄清文本中解析出文档 ID", truncate(question, 50));
            return result;
        }

        // 用第一个匹配的文档 ID 以 DOCUMENT 模式重试
        Long autoDocId = resolvedDocIds.get(0);
        log.info("自动解析到文档 ID={}，以 DOCUMENT 模式重试", autoDocId);
        String retryUrl = evalProperties.getChatService().getUrl() + "/api/chat/stream";
        ChatAnswerResult retryResult = callChatApi(retryUrl,
            UUID.randomUUID().toString().replace("-", ""),
            "DOCUMENT", question, autoDocId);
        if (retryResult.hasEvidence() || !retryResult.getAnswer().isBlank()) {
            return retryResult;
        }

        return result;
    }

    /**
     * 调用聊天 API 并解析 SSE 响应
     */
    private ChatAnswerResult callChatApi(String url, String conversationId, String chatMode,
                                          String question, Long documentId) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("question", question);
            request.put("conversationId", conversationId);
            request.put("chatMode", chatMode);
            if (documentId != null) {
                request.put("selectedDocumentId", String.valueOf(documentId));
            }

            log.info("聊天接口调用: mode={}, documentId={}, question='{}'",
                chatMode, documentId, truncate(question, 50));
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("聊天接口返回非成功状态: code={}", response.getStatusCode());
                return new ChatAnswerResult("", List.of());
            }

            return parseSseResponseWithEvidence(response.getBody());
        } catch (Exception e) {
            log.error("聊天接口调用失败: mode={}, question='{}'", chatMode, truncate(question, 50), e);
            return new ChatAnswerResult("", List.of());
        }
    }

    /**
     * 从澄清文本中解析文档名称 → 查询数据库获取文档 ID
     */
    private List<Long> resolveDocumentIds(String clarificationText) {
        List<Long> docIds = new ArrayList<>();
        Matcher matcher = DOCUMENT_NAME_PATTERN.matcher(clarificationText);
        List<String> docNames = new ArrayList<>();
        while (matcher.find()) {
            docNames.add(matcher.group(1));
        }
        if (docNames.isEmpty()) {
            log.warn("澄清文本中未找到《文档名》模式");
            return docIds;
        }
        for (String name : docNames) {
            try {
                List<Long> found = jdbcTemplate.query(
                    "SELECT id FROM super_agent_document WHERE document_name LIKE ? AND status = 1 AND index_status = 3 AND last_index_task_id IS NOT NULL ORDER BY id ASC LIMIT 1",
                    (rs, rowNum) -> rs.getLong("id"),
                    name + "%"
                );
                if (!found.isEmpty() && !docIds.contains(found.get(0))) {
                    docIds.add(found.get(0));
                    log.info("解析到文档: name='{}', id={}", name, found.get(0));
                }
            } catch (Exception e) {
                log.warn("查询文档失败: name='{}'", name, e);
            }
        }
        return docIds;
    }

    /**
     * 解析 SSE 响应，只取 text 事件
     */
    private String parseSseResponse(String body) {
        StringBuilder answer = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;
            try {
                JSONObject json = JSONUtil.parseObj(trimmed.substring(5).trim());
                if ("text".equals(json.getStr("type"))) {
                    answer.append(json.getStr("content", ""));
                }
            } catch (Exception ignored) {}
        }
        return answer.toString().trim();
    }

    /**
     * 解析 SSE 响应，提取答案 + 检索引用
     */
    private ChatAnswerResult parseSseResponseWithEvidence(String body) {
        StringBuilder answer = new StringBuilder();
        List<String> evidenceChunks = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;
            try {
                JSONObject json = JSONUtil.parseObj(trimmed.substring(5).trim());
                String type = json.getStr("type");
                if ("text".equals(type)) {
                    answer.append(json.getStr("content", ""));
                } else if ("reference".equals(type)) {
                    Object content = json.get("content");
                    if (content instanceof cn.hutool.json.JSONArray arr) {
                        for (Object item : arr) {
                            if (item instanceof JSONObject ref) {
                                String snippet = ref.getStr("snippet");
                                if (snippet != null && !snippet.isBlank()) {
                                    evidenceChunks.add(snippet);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return new ChatAnswerResult(answer.toString().trim(), evidenceChunks);
    }

    /**
     * 请求体 DTO（内部检索 API 用）
     */
    private record RetrieveRequest(Long documentId, String question) {}

    /**
     * 构造空结果（调用失败时的兜底）
     */
    private RetrievalRpcResult emptyResult(String question) {
        RetrievalRpcResult result = new RetrievalRpcResult();
        result.setRetrievalQuestion(question);
        result.setSubQuestions(java.util.List.of());
        result.setUsedChannels(java.util.List.of());
        result.setRetrievalNotes(java.util.List.of("检索服务调用失败，已降级为空结果"));
        return result;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 聊天接口返回结果（答案 + 检索证据）
     */
    public static class ChatAnswerResult {
        private final String answer;
        private final List<String> evidenceChunks;

        public ChatAnswerResult(String answer, List<String> evidenceChunks) {
            this.answer = answer;
            this.evidenceChunks = evidenceChunks;
        }

        public String getAnswer() { return answer; }
        public List<String> getEvidenceChunks() { return evidenceChunks; }
        public boolean hasEvidence() { return evidenceChunks != null && !evidenceChunks.isEmpty(); }

        public String buildEvidenceText() {
            if (evidenceChunks == null || evidenceChunks.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < evidenceChunks.size(); i++) {
                sb.append("【引用").append(i + 1).append("】").append(evidenceChunks.get(i)).append("\n\n");
            }
            return sb.toString().trim();
        }
    }
}
