package org.javaup.ai.chatagent.rag.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.chatagent.rag.service.ChatQueryRewriteService;
import org.javaup.ai.chatagent.rag.service.DocumentQuestionRouter;
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
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final DocumentQuestionRouter documentQuestionRouter;

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
        String originalQuestion = request.getQuestion().trim();

        // 1. 查询文档的最新索引任务 ID
        Long taskId = null;
        SuperAgentDocument doc = documentMapper.selectById(request.getDocumentId());
        if (doc != null && doc.getLastIndexTaskId() != null) {
            taskId = doc.getLastIndexTaskId();
        } else {
            log.warn("文档 {} 未找到或无 lastIndexTaskId，检索可能返回空", request.getDocumentId());
        }

        // 2. ⭐ 问题改写：将口语化问题改写为检索友好的结构化查询（和聊天链路一致）
        log.info("内部检索改写: question='{}'", originalQuestion);
        RagRewriteResult rewriteResult = chatQueryRewriteService.rewrite(originalQuestion, "");
        String rewriteQuestion = rewriteResult == null ? originalQuestion : rewriteResult.getRewrittenQuestion();
        List<String> subQuestions = rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()
            ? List.of(rewriteQuestion)
            : rewriteResult.getSubQuestions();
        log.info("内部检索改写完成: original='{}', rewritten='{}', subQuestions={}",
            originalQuestion, rewriteQuestion, subQuestions);

        // 3. ⭐ 文档路由决策：判断走图查询还是检索，获取章节锚点和检索增强信息
        DocumentNavigationDecision navDecision = documentQuestionRouter.route(request.getDocumentId(), originalQuestion, rewriteResult);
        String retrievalQuestion = rewriteQuestion;
        List<String> retrievalSubQuestions = subQuestions;
        if (navDecision != null && navDecision.getRetrievalPlan() != null) {
            retrievalQuestion = navDecision.getRetrievalPlan().getRetrievalQuestion();
            retrievalSubQuestions = navDecision.getRetrievalPlan().getSubQuestions();
        }
        log.info("内部检索路由完成: mode={}, question='{}'",
            navDecision == null || navDecision.getExecutionMode() == null ? "NONE" : navDecision.getExecutionMode(),
            retrievalQuestion);

        // 4. 构造完整的 ConversationExecutionPlan（和聊天链路的 prepare 阶段产出一致）
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .originalQuestion(originalQuestion)
            .rewriteQuestion(rewriteQuestion)
            .rewriteSubQuestions(subQuestions)
            .retrievalQuestion(retrievalQuestion)
            .retrievalSubQuestions(retrievalSubQuestions)
            .selectedDocumentId(request.getDocumentId())
            .selectedTaskId(taskId)
            .retrievalDocumentIds(taskId != null ? List.of(request.getDocumentId()) : List.of())
            .retrievalTaskIds(taskId != null ? List.of(taskId) : List.of())
            .navigationDecision(navDecision)
            .build();

        // 5. 调用检索引擎（带完整的改写+路由信息）
        RagRetrievalContext context = ragRetrievalEngine.retrieve(plan, null);

        // 6. 转换为 RPC 响应
        Map<String, Object> response = buildRpcResponse(context);

        long latency = System.currentTimeMillis() - startTime;
        log.info("内部检索完成: documentId={}, question='{}', rewritten='{}', mode={}, notes={}, latency={}ms",
            request.getDocumentId(), originalQuestion, rewriteQuestion,
            navDecision == null || navDecision.getExecutionMode() == null ? "NONE" : navDecision.getExecutionMode(),
            context.getRetrievalNotes(), latency);
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
