package org.javaup.ai.chatagent.rag.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.chatagent.rag.service.RagRetrievalEngine;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评估服务内部检索 API
 * <p>
 * 为独立部署的 super-agent-eval 模块提供纯检索能力。
 * 不走完整的 SSE 对话管道，仅调用 RagRetrievalEngine.retrieve()，
 * 返回检索结果（含 rerank 分数）。
 * <p>
 * 接口路径：POST /api/internal/rag/retrieve
 *
 * @author wangpeng
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/rag")
@RequiredArgsConstructor
public class EvalApiController {

    private final RagRetrievalEngine ragRetrievalEngine;
    private final SuperAgentDocumentMapper documentMapper;

    /**
     * 纯检索接口（供 eval 服务调用）
     *
     * @param request { documentId: Long, question: String }
     * @return 检索结果，包含每个子问题的证据列表和每篇文档的 rerank 分数
     */
    @PostMapping("/retrieve")
    public Map<String, Object> retrieve(@RequestBody EvalRetrieveRequest request) {
        if (request.getDocumentId() == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return errorResponse("documentId 和 question 不能为空");
        }

        long startTime = System.currentTimeMillis();

        // 1. 查询文档的最新索引任务 ID（vector 和 keyword 检索都需要 taskId 过滤）
        Long taskId = null;
        SuperAgentDocument doc = documentMapper.selectById(request.getDocumentId());
        if (doc != null && doc.getLastIndexTaskId() != null) {
            taskId = doc.getLastIndexTaskId();
            log.debug("查询到文档 lastIndexTaskId: documentId={}, taskId={}", request.getDocumentId(), taskId);
        } else {
            log.warn("文档 {} 未找到或无 lastIndexTaskId，检索可能返回空", request.getDocumentId());
        }

        // 2. 构造最小化的 ConversationExecutionPlan
        //     关键：必须填充 selectedDocumentId + selectedTaskId，否则检索 SQL 的 task_id IN (...) 条件不匹配
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .originalQuestion(request.getQuestion())
            .retrievalQuestion(request.getQuestion())
            .selectedDocumentId(request.getDocumentId())
            .selectedTaskId(taskId)
            .retrievalDocumentIds(taskId != null ? List.of(request.getDocumentId()) : List.of())
            .retrievalTaskIds(taskId != null ? List.of(taskId) : List.of())
            .build();

        // 3. 调用检索引擎（不传 traceRecorder，避免产生数据库追踪记录）
        RagRetrievalContext context = ragRetrievalEngine.retrieve(plan, null);

        // 3. 转换为扁平化的 RPC 响应
        Map<String, Object> response = buildRpcResponse(context);

        long latency = System.currentTimeMillis() - startTime;
        log.debug("内部检索完成: documentId={}, question='{}', latency={}ms",
            request.getDocumentId(), truncate(request.getQuestion(), 50), latency);
        response.put("_latencyMs", latency);

        return response;
    }

    /**
     * 将 RagRetrievalContext 转换为 RPC 友好的 Map 结构
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRpcResponse(RagRetrievalContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retrievalQuestion", context.getRetrievalQuestion());
        result.put("usedChannels", context.getUsedChannels() != null
            ? new ArrayList<>(context.getUsedChannels()) : List.of());
        result.put("retrievalNotes", context.getRetrievalNotes() != null
            ? new ArrayList<>(context.getRetrievalNotes()) : List.of());

        // 转换子问题列表
        List<Map<String, Object>> subQuestions = new ArrayList<>();
        if (context.getSubQuestionEvidenceList() != null) {
            for (SubQuestionEvidence evidence : context.getSubQuestionEvidenceList()) {
                Map<String, Object> sq = new LinkedHashMap<>();
                sq.put("subQuestionIndex", evidence.getSubQuestionIndex());
                sq.put("subQuestion", evidence.getSubQuestion());
                sq.put("referenceCount", evidence.getReferences() != null ? evidence.getReferences().size() : 0);

                // 转换文档列表
                List<Map<String, Object>> docs = new ArrayList<>();
                if (evidence.getDocuments() != null) {
                    for (int i = 0; i < evidence.getDocuments().size(); i++) {
                        Document doc = evidence.getDocuments().get(i);
                        Map<String, Object> docMap = new LinkedHashMap<>();
                        docMap.put("id", doc.getId());
                        docMap.put("text", doc.getText());

                        // 提取 metadata 中的 chunkId、分数等信息
                        Map<String, Object> metadata = doc.getMetadata();
                        if (metadata != null) {
                            docMap.put("chunkId", metadata.get("chunkId"));
                            docMap.put("similarityScore", metadata.get("similarity"));
                            docMap.put("rrfScore", metadata.get("rrfScore"));
                            docMap.put("rerankScore", metadata.get("rerankScore"));
                            docMap.put("gatePassed", metadata.get("gatePassed"));
                            docMap.put("isSelected", metadata.get("isSelected"));
                            docMap.put("channel", metadata.get("channel"));
                            docMap.put("documentName", metadata.get("documentName"));
                            docMap.put("sectionPath", metadata.get("sectionPath"));
                        }

                        docs.add(docMap);
                    }
                }
                sq.put("documents", docs);
                subQuestions.add(sq);
            }
        }
        result.put("subQuestions", subQuestions);

        return result;
    }

    /**
     * 错误响应
     */
    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("retrievalQuestion", "");
        err.put("subQuestions", List.of());
        err.put("usedChannels", List.of());
        err.put("retrievalNotes", List.of("错误: " + message));
        return err;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 检索请求 DTO
     */
    @Data
    public static class EvalRetrieveRequest {
        private Long documentId;
        private String question;
    }
}
