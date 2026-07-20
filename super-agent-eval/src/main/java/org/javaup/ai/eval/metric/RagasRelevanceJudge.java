package org.javaup.ai.eval.metric;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * LLM-as-Judge 相关性判题器
 * <p>
 * 对检索到的每个文档判断是否与问题相关。
 * 采用分层策略减少 LLM 调用量：
 * <ul>
 *   <li>rerank 分 ≥ 0.5 → 直接判为相关（零 LLM）</li>
 *   <li>rerank 分 < 0.3 → 直接判为不相关（零 LLM）</li>
 *   <li>0.3 ~ 0.5 之间 → LLM Judge 二次确认</li>
 * </ul>
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagasRelevanceJudge {

    /** LLM 判题系统提示词：要求输出结构化 JSON */
    private static final String JUDGE_SYSTEM_PROMPT = """
        你是一个 RAG 相关性评估专家。判断以下文档片段是否包含回答用户问题的相关信息。
        注意：文档片段可能只是部分相关，只要包含了有助于回答问题的信息即判为相关。
        只输出 JSON，不要输出其他内容。
        """;

    /** LLM 判题用户提示词模板 */
    private static final String JUDGE_USER_PROMPT_TEMPLATE = """
        问题：{question}

        文档片段：{chunk_text}

        请判断：这个文档片段是否包含回答上述问题所需的相关信息？
        只输出以下 JSON 格式（不要输出其他内容）：
        {"relevant": true, "reason": "简要说明为什么相关"}
        或
        {"relevant": false, "reason": "简要说明为什么不相关"}
        """;

    private final ChatModel chatModel;

    // 判题并发线程池
    private final ExecutorService judgeExecutor = Executors.newFixedThreadPool(4);

    // ──────────────────────────────────────────────
    // 分层阈值，硬编码默认值，可通过 setter 覆盖
    // ──────────────────────────────────────────────
    private double rerankThresholdHigh = 0.5;
    private double rerankThresholdLow = 0.3;

    public void setThresholds(double high, double low) {
        this.rerankThresholdHigh = high;
        this.rerankThresholdLow = low;
    }

    /**
     * 批量判断相关性（带 rerank 分层 + 参考答案辅助）
     * <p>
     * 与旧版本相比，新增 referenceAnswer 参数。传给 LLM Judge 作为辅助上下文，
     * 让 Judge 知道"需要什么信息"，从而判断文档相关性时更精准。
     * <p>
     * 判断标准变为："这篇文档是否对综合推理出标准答案有帮助？"
     * 而不是旧的"这篇文档里是否直接包含了答案？"
     *
     * @param question        用户问题
     * @param referenceAnswer 参考答案（标准答案），辅助 LLM Judge 判断相关性
     * @param documents       检索结果文档列表（按排序传入）
     * @return 相关性判断列表，与输入文档列表一一对应
     */
    public List<ContextPrecisionResult.RelevanceJudgment> judgeBatch(
            String question,
            String referenceAnswer,
            List<RetrievalRpcResult.DocumentResult> documents) {

        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        String judgeContext;
        if (referenceAnswer != null && !referenceAnswer.isBlank()) {
            judgeContext = "问题：" + question + "\n参考答案（标准答案）：" + referenceAnswer
                + "\n\n判断标准：这篇文档是否包含对综合推理出参考答案有帮助的信息？"
                + "即使文档不包含完整答案，只要包含部分关键事实、数据或推理依据，就判为相关。";
        } else {
            judgeContext = "问题：" + question
                + "\n\n判断标准：这篇文档是否包含回答该问题所需的相关信息？";
        }

        List<ContextPrecisionResult.RelevanceJudgment> judgments = new ArrayList<>(documents.size());

        // 第一遍：用 rerank 分层快速判定
        for (int i = 0; i < documents.size(); i++) {
            RetrievalRpcResult.DocumentResult doc = documents.get(i);
            Double rerankScore = doc.getRerankScore();

            if (rerankScore != null && rerankScore >= rerankThresholdHigh) {
                judgments.add(new ContextPrecisionResult.RelevanceJudgment(
                    doc.getChunkId(), i + 1, true, "rerank", rerankScore,
                    "rerank_score=" + String.format("%.4f", rerankScore) + " ≥ " + rerankThresholdHigh,
                    truncate(doc.getText(), 100)));
            } else if (rerankScore != null && rerankScore < rerankThresholdLow) {
                judgments.add(new ContextPrecisionResult.RelevanceJudgment(
                    doc.getChunkId(), i + 1, false, "rerank", rerankScore,
                    "rerank_score=" + String.format("%.4f", rerankScore) + " < " + rerankThresholdLow,
                    truncate(doc.getText(), 100)));
            } else {
                // 争议区间 → 留到第二遍 LLM Judge
                judgments.add(null);
            }
        }

        // 第二遍：争议区间走 LLM Judge（并发）
        List<Integer> llmIndices = new ArrayList<>();
        for (int i = 0; i < judgments.size(); i++) {
            if (judgments.get(i) == null) {
                llmIndices.add(i);
            }
        }

        if (!llmIndices.isEmpty()) {
            log.info("LLM Judge 处理 {} 个争议文档 (rerank 分在 {}-{} 区间，referenceAnswer={})",
                llmIndices.size(), rerankThresholdLow, rerankThresholdHigh,
                referenceAnswer != null && !referenceAnswer.isBlank());

            List<CompletableFuture<Void>> futures = llmIndices.stream()
                .map(idx -> CompletableFuture.runAsync(() -> {
                    RetrievalRpcResult.DocumentResult doc = documents.get(idx);
                    ContextPrecisionResult.RelevanceJudgment judgeResult = llmJudge(question, judgeContext, doc, idx + 1);
                    judgments.set(idx, judgeResult);
                }, judgeExecutor))
                .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return judgments;
    }

    /**
     * 批量判断相关性（旧版本，无参考答案）
     * 内部委托给新版本，传 referenceAnswer=null
     */
    public List<ContextPrecisionResult.RelevanceJudgment> judgeBatch(
            String question,
            List<RetrievalRpcResult.DocumentResult> documents) {
        return judgeBatch(question, null, documents);
    }

    /**
     * 调用 LLM 判断单条文档相关性（带参考答案上下文）
     */
    private ContextPrecisionResult.RelevanceJudgment llmJudge(
            String question, String judgeContext,
            RetrievalRpcResult.DocumentResult doc, int rank) {

        try {
            String userPrompt = """
                判断以下文档片段是否包含回答该问题所需的相关信息。

                {judge_context}

                文档片段：{chunk_text}

                只输出以下 JSON：
                {"relevant": true, "reason": "说明为什么这篇文档对回答问题有帮助"}
                或
                {"relevant": false, "reason": "说明为什么不相关"}
                """
                .replace("{judge_context}", judgeContext)
                .replace("{chunk_text}", doc.getText() != null ? doc.getText() : "");

            Prompt prompt = new Prompt(List.of(
                new SystemMessage(JUDGE_SYSTEM_PROMPT),
                new UserMessage(userPrompt)
            ));

            String response = chatModel.call(prompt).getResult().getOutput().getText();
            JSONObject json = JSONUtil.parseObj(extractJsonObject(response));

            boolean relevant = json.getBool("relevant", false);
            String reason = json.getStr("reason", "");

            log.debug("LLM Judge: question='{}', chunkId={}, relevant={}, reason='{}'",
                truncate(question, 40), doc.getChunkId(), relevant, truncate(reason, 60));

            return new ContextPrecisionResult.RelevanceJudgment(
                doc.getChunkId(), rank, relevant, "llm_judge", doc.getRerankScore(),
                reason, truncate(doc.getText(), 100));

        } catch (Exception e) {
            log.warn("LLM Judge 失败: chunkId={}, 兜底判为不相关", doc.getChunkId(), e);
            return new ContextPrecisionResult.RelevanceJudgment(
                doc.getChunkId(), rank, false, "llm_judge_fallback", doc.getRerankScore(),
                "LLM Judge 调用失败: " + e.getMessage(), truncate(doc.getText(), 100));
        }
    }

    /**
     * Faithfulness 逐句判题：判断答案的每个主张是否可以从证据中推出
     */
    public FaithfulnessResult judgeFaithfulness(String answer, String evidenceText) {
        if (answer == null || answer.isBlank()) {
            return FaithfulnessResult.ZERO;
        }

        // 先让 LLM 从答案中提取独立主张
        List<String> claims = extractClaims(answer);
        if (claims.isEmpty()) {
            return FaithfulnessResult.ZERO;
        }

        // 逐条判断
        List<FaithfulnessResult.ClaimJudgment> claimJudgments = new ArrayList<>();
        int supported = 0;

        for (String claim : claims) {
            boolean faithful = judgeSingleClaim(claim, evidenceText);
            claimJudgments.add(new FaithfulnessResult.ClaimJudgment(claim, faithful,
                faithful ? "证据支持该主张" : "证据中未找到该主张的充分依据"));
            if (faithful) supported++;
        }

        double score = supported / (double) claims.size();
        return new FaithfulnessResult(score, claims.size(), supported, claimJudgments);
    }

    /**
     * 从答案中提取独立主张（调用 LLM）
     */
    private List<String> extractClaims(String answer) {
        String prompt = """
            将以下文本拆分为独立的、可验证的主张（claims）。
            每个主张应该是一个可以被事实验证的陈述句。
            以 JSON 数组格式输出：["主张1", "主张2", ...]

            文本：{text}
            """.replace("{text}", answer);

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个文本分析专家。只输出 JSON，不要其他内容。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            JSONArray arr = JSONUtil.parseArray(extractJsonArray(response));
            List<String> claims = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                String claim = arr.getStr(i);
                if (claim != null && !claim.isBlank()) {
                    claims.add(claim);
                }
            }
            return claims;
        } catch (Exception e) {
            log.warn("提取主张失败，降级为整句拆分", e);
            // 兜底：按句号拆分
            return Arrays.stream(answer.split("[。！？]"))
                .map(String::trim)
                .filter(s -> s.length() > 5)
                .collect(Collectors.toList());
        }
    }

    /**
     * 判断单条主张是否可以被证据支持
     */
    private boolean judgeSingleClaim(String claim, String evidenceText) {
        String prompt = """
            判断以下主张是否可以从提供的证据中推断出来。
            只要有部分证据支持即算"是"。

            主张：{claim}

            证据：{evidence}

            只输出 JSON：
            {"faithful": true, "reason": "简要理由"}
            或
            {"faithful": false, "reason": "简要理由"}
            """.replace("{claim}", claim).replace("{evidence}", evidenceText);

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个事实核查专家。只输出 JSON。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            JSONObject json = JSONUtil.parseObj(extractJsonObject(response));
            return json.getBool("faithful", false);
        } catch (Exception e) {
            log.warn("Faithfulness 判断失败，默认不忠实: claim='{}'", truncate(claim, 50), e);
            return false;
        }
    }

    /**
     * Answer Relevancy：反向生成问题，计算 embedding 相似度
     */
    public AnswerRelevancyResult judgeAnswerRelevancy(
            String question, String answer,
            java.util.function.Function<String, float[]> embeddingFunction) {

        if (answer == null || answer.isBlank()) {
            return AnswerRelevancyResult.ZERO;
        }

        // 1. LLM 反向生成 N 个可能问题
        List<String> generatedQuestions = generateReverseQuestions(answer);

        if (generatedQuestions.isEmpty()) {
            return AnswerRelevancyResult.ZERO;
        }

        // 2. 计算 embedding 相似度
        float[] questionEmbedding = embeddingFunction.apply(question);
        List<Double> similarities = new ArrayList<>();

        for (String genQ : generatedQuestions) {
            float[] genEmbedding = embeddingFunction.apply(genQ);
            double similarity = cosineSimilarity(questionEmbedding, genEmbedding);
            similarities.add(similarity);
        }

        // 3. 取平均值
        double avgSimilarity = similarities.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);

        return new AnswerRelevancyResult(avgSimilarity, generatedQuestions, question, similarities);
    }

    /**
     * LLM 反向生成问题
     */
    private List<String> generateReverseQuestions(String answer) {
        String prompt = """
            基于以下答案文本，反向生成 3 个可能的用户问题。
            问题应该简洁、自然，就像用户在问问题一样。
            以 JSON 数组格式输出：["问题1", "问题2", "问题3"]

            答案文本：{text}
            """.replace("{text}", answer);

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个问题生成专家。只输出 JSON。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            JSONArray arr = JSONUtil.parseArray(extractJsonArray(response));
            List<String> questions = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                questions.add(arr.getStr(i));
            }
            return questions;
        } catch (Exception e) {
            log.warn("反向生成问题失败", e);
            return List.of();
        }
    }

    /**
     * 计算 cosine 相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * 从 LLM 响应中提取 JSON 数组
     */
    private String extractJsonArray(String text) {
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
        if (start >= 0) {
            int end = text.lastIndexOf(']');
            if (end > start) {
                return text.substring(start, end + 1);
            }
        }
        return "[]";
    }

    /**
     * 从 LLM 响应中提取 JSON 对象
     */
    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        text = text.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start, end).trim();
            }
        }
        int start = text.indexOf('{');
        if (start >= 0) {
            int end = text.lastIndexOf('}');
            if (end > start) {
                return text.substring(start, end + 1);
            }
        }
        return "{}";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
