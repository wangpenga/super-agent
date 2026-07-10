package org.javaup.ai.eval.remote.impl;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.config.EvalProperties;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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

    private final RestTemplate restTemplate;
    private final EvalProperties evalProperties;

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
     * 请求体 DTO
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
}
