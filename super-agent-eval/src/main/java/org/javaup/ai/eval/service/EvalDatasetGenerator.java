package org.javaup.ai.eval.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalDataset;
import org.javaup.ai.eval.metric.RagasRelevanceJudge;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.javaup.ai.eval.remote.impl.RagRetrievalRestClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评估测试集自动生成器
 * <p>
 * 三种数据来源：
 * <ol>
 *   <li><b>真实对话日志</b>（最高优先级）— 从主服务的 super_agent_chat_exchange 表提取</li>
 *   <li><b>文档画像问题</b> — 从 super_agent_document_profile.example_questions 提取</li>
 *   <li><b>LLM 补充生成</b> — 对问题不够的文档，基于文档摘要用 LLM 生成</li>
 * </ol>
 * <p>
 * 流程：获取问题 → 调用主服务检索 → 用 Rerank 分层 + LLM 标注 ground truth chunks。
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetGenerator {

    private final RagRetrievalRestClient retrievalClient;
    private final ChatModel chatModel;
    private final EvalJudgeService evalJudgeService;

    /**
     * 为指定文档自动生成测试集条目
     *
     * @param documentId    文档 ID
     * @param questions     候选问题列表
     * @param maxQuestions  最多生成多少条
     * @return 生成的测试集条目列表（尚未持久化）
     */
    public List<EvalDataset> generateForDocument(Long documentId, List<String> questions, int maxQuestions) {
        if (questions == null || questions.isEmpty()) {
            log.info("文档 {} 没有问题候选，跳过", documentId);
            return List.of();
        }

        // 限制问题数量
        List<String> selectedQuestions = questions.stream()
            .filter(q -> q != null && !q.isBlank())
            .limit(maxQuestions)
            .toList();

        log.info("开始为文档 {} 标注 ground truth，共 {} 个问题", documentId, selectedQuestions.size());

        List<EvalDataset> results = new ArrayList<>();

        for (String question : selectedQuestions) {
            try {
                EvalDataset dataset = annotateGroundTruth(documentId, question, "profile");
                if (dataset != null) {
                    results.add(dataset);
                }
            } catch (Exception e) {
                log.warn("文档 {} 问题标注失败: question='{}'", documentId, truncate(question, 50), e);
            }
        }

        log.info("文档 {} 标注完成，生成 {} / {} 条有效测试数据", documentId, results.size(), selectedQuestions.size());
        return results;
    }

    /**
     * 对单个问题进行 ground truth 标注
     */
    private EvalDataset annotateGroundTruth(Long documentId, String question, String source) {
        // 1. 调用主服务检索 API，获取候选 chunks
        RetrievalRpcResult result = retrievalClient.retrieve(documentId, question);
        List<RetrievalRpcResult.DocumentResult> allDocs = result.flattenDocuments();

        if (allDocs.isEmpty()) {
            log.debug("问题检索结果为空，跳过: question='{}'", truncate(question, 50));
            return null;
        }

        // 2. 用 rerank 分层 + LLM 判断哪些 chunk 与问题相关
        List<Long> relevantChunkIds = new ArrayList<>();

        for (RetrievalRpcResult.DocumentResult doc : allDocs) {
            EvalJudgeService.JudgeResult judgeResult = evalJudgeService.judge(
                question, doc.getText(), doc.getRerankScore());

            if (judgeResult.relevant() && doc.getChunkId() != null) {
                relevantChunkIds.add(doc.getChunkId());
            }
        }

        if (relevantChunkIds.isEmpty()) {
            log.debug("未找到相关 chunk，跳过: question='{}'", truncate(question, 50));
            return null;
        }

        // 3. 构造测试集条目
        EvalDataset dataset = new EvalDataset();
        dataset.setDocumentId(documentId);
        dataset.setQuestion(question);
        dataset.setSource(source);
        dataset.setGroundTruthChunkIds(JSONUtil.toJsonStr(relevantChunkIds));
        dataset.setDifficulty("medium");
        dataset.setIsActive(1);
        dataset.setStatus(1);

        log.info("标注完成: documentId={}, question='{}', relevantChunks={}",
            documentId, truncate(question, 50), relevantChunkIds);

        return dataset;
    }

    /**
     * 用 LLM 为文档生成补充问题
     *
     * @param documentSummary 文档摘要
     * @param coreTopics      核心主题（逗号分隔）
     * @param count           生成数量
     * @return 生成的问题列表
     */
    public List<String> generateQuestionsByLLM(String documentSummary, String coreTopics, int count) {
        String prompt = """
            你是一个文档专家。基于以下文档摘要和核心主题，生成 {count} 个用户可能会问的问题。
            问题应覆盖文档的核心内容，难度适中，语言自然口语化。
            以 JSON 数组格式输出：["问题1", "问题2", ...]

            文档摘要：{summary}

            核心主题：{topics}
            """
            .replace("{count}", String.valueOf(count))
            .replace("{summary}", documentSummary != null ? documentSummary : "")
            .replace("{topics}", coreTopics != null ? coreTopics : "");

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个问题生成专家。只输出 JSON。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            JSONArray arr = JSONUtil.parseArray(extractJson(response));
            List<String> questions = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                questions.add(arr.getStr(i));
            }
            return questions;
        } catch (Exception e) {
            log.warn("LLM 生成问题失败", e);
            return List.of();
        }
    }

    /**
     * 从 JSON 数组中提取字符串列表
     */
    public List<String> parseJsonArray(String jsonArrayStr) {
        if (jsonArrayStr == null || jsonArrayStr.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(jsonArrayStr);
            return arr.stream()
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析 JSON 数组失败: {}", jsonArrayStr, e);
            return List.of();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "[]";
        text = text.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start, end).trim();
            }
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "[]";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
