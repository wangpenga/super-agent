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
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagasMetricCalculator {

    private final RagasRelevanceJudge relevanceJudge;
    private final ChatModel chatModel;

    public ContextPrecisionResult computeContextPrecision(
            String question,
            String referenceAnswer,
            List<RetrievalRpcResult.DocumentResult> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("Context Precision 计算：检索结果为空，返回 0");
            return ContextPrecisionResult.ZERO;
        }
        List<ContextPrecisionResult.RelevanceJudgment> judgments =
            relevanceJudge.judgeBatch(question, referenceAnswer, documents);
        if (judgments.isEmpty()) return ContextPrecisionResult.ZERO;

        int totalRelevant = (int) judgments.stream().filter(ContextPrecisionResult.RelevanceJudgment::isRelevant).count();
        if (totalRelevant == 0) return new ContextPrecisionResult(0.0, judgments);

        double sumPrecisionAtK = 0.0;
        int relevantCount = 0;
        for (int k = 0; k < judgments.size(); k++) {
            if (judgments.get(k).isRelevant()) {
                relevantCount++;
                sumPrecisionAtK += (double) relevantCount / (k + 1);
            }
        }
        return new ContextPrecisionResult(sumPrecisionAtK / totalRelevant, judgments);
    }

    public ContextRecallResult computeContextRecall(
            String question,
            String referenceAnswer,
            String evidenceText) {
        if (referenceAnswer == null || referenceAnswer.isBlank()) return ContextRecallResult.ZERO;
        if (evidenceText == null || evidenceText.isBlank()) return ContextRecallResult.ZERO;

        List<Statement> statements = extractStatements(referenceAnswer);
        if (statements.isEmpty()) return ContextRecallResult.ZERO;

        int attributableCount = 0;
        List<ContextRecallResult.AttributionJudgment> judgments = new ArrayList<>();
        for (Statement stmt : statements) {
            boolean attributable = judgeAttribution(stmt.text, question, evidenceText);
            judgments.add(new ContextRecallResult.AttributionJudgment(stmt.text, attributable,
                attributable ? "证据支持该陈述" : "证据中未找到支持该陈述的信息"));
            if (attributable) attributableCount++;
        }
        return new ContextRecallResult((double) attributableCount / statements.size(), attributableCount, statements.size(), judgments);
    }

    public FaithfulnessResult computeFaithfulness(String answer, String evidenceText) {
        return relevanceJudge.judgeFaithfulness(answer, evidenceText);
    }

    public AnswerRelevancyResult computeAnswerRelevancy(
            String question, String answer,
            java.util.function.Function<String, float[]> embeddingFunction) {
        return relevanceJudge.judgeAnswerRelevancy(question, answer, embeddingFunction);
    }

    public AnswerAccuracyResult computeAnswerAccuracy(
            String question, String generatedAnswer, String referenceAnswer) {
        if (generatedAnswer == null || generatedAnswer.isBlank()) return AnswerAccuracyResult.ZERO;
        if (referenceAnswer == null || referenceAnswer.isBlank()) return AnswerAccuracyResult.ZERO;

        String prompt = "判断以下AI生成的答案是否与标准答案意思一致。\n\n"
            + "用户问题：" + question + "\n\n"
            + "AI生成的答案：" + generatedAnswer + "\n\n"
            + "标准答案：" + referenceAnswer + "\n\n"
            + "判断标准：\n"
            + "- 如果AI答案和标准答案说的是同一个意思，只是措辞不同 -> 一致\n"
            + "- 如果AI答案只包含了标准答案的部分信息 -> 部分一致（也算一致）\n"
            + "- 如果AI答案与标准答案矛盾或完全无关 -> 不一致\n\n"
            + "只输出JSON：\n"
            + "{\"accurate\": true, \"reason\": \"简要说明\"} 或 {\"accurate\": false, \"reason\": \"简要说明\"}";

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个答案评估专家。只输出JSON，不要其他内容。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            JSONObject json = JSONUtil.parseObj(extractJsonObject(response));
            return new AnswerAccuracyResult(json.getBool("accurate", false) ? 1.0 : 0.0, json.getStr("reason", ""));
        } catch (Exception e) {
            log.warn("Answer Accuracy 判断失败", e);
            return new AnswerAccuracyResult(0.0, "评估失败");
        }
    }

    public String buildEvidenceText(List<RetrievalRpcResult.DocumentResult> documents) {
        if (documents == null || documents.isEmpty()) return "";
        return documents.stream()
            .map(d -> "【文档" + d.getChunkId() + "】" + (d.getText() != null ? d.getText() : ""))
            .collect(Collectors.joining("\n\n"));
    }

    private List<Statement> extractStatements(String referenceAnswer) {
        String prompt = "将以下文本拆分为独立的、可被事实验证的陈述句。\n"
            + "要求：\n- 每个陈述句应该只包含一个可验证的事实\n"
            + "- 去掉疑问句、祈使句\n"
            + "- 以JSON数组格式输出，每个元素包含text字段\n\n"
            + "文本：" + referenceAnswer + "\n\n"
            + "输出格式：[{\"text\": \"陈述句1\"}, {\"text\": \"陈述句2\"}]";

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个文本分析专家。只输出JSON，不要其他内容。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            JSONArray arr = JSONUtil.parseArray(extractJsonArray(response));
            List<Statement> statements = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String text = obj.getStr("text");
                if (text != null && !text.isBlank()) statements.add(new Statement(text.trim()));
            }
            if (!statements.isEmpty()) return statements;
        } catch (Exception e) {
            log.warn("LLM 拆分陈述句失败，降级为句号拆分", e);
        }
        return Arrays.stream(referenceAnswer.split("[。！？;；]"))
            .map(String::trim).filter(s -> s.length() > 5).map(Statement::new).toList();
    }

    private boolean judgeAttribution(String statement, String question, String evidenceText) {
        String prompt = "判断以下陈述句的信息是否可以在检索到的证据中找到支持。\n\n"
            + "问题：" + question + "\n\n"
            + "陈述句：" + statement + "\n\n"
            + "证据：\n" + evidenceText + "\n\n"
            + "只输出JSON：{\"attributable\": true/false, \"reason\": \"简要理由\"}";

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是一个事实核查专家。只输出JSON。"),
                new UserMessage(prompt)
            ))).getResult().getOutput().getText();

            return JSONUtil.parseObj(extractJsonObject(response)).getBool("attributable", false);
        } catch (Exception e) {
            log.warn("归因判断失败", e);
            return false;
        }
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        text = text.trim();
        if (text.startsWith("```")) {
            int s = text.indexOf('\n');
            int e = text.lastIndexOf("```");
            if (s > 0 && e > s) text = text.substring(s, e).trim();
        }
        int start = text.indexOf('[');
        if (start >= 0) {
            int end = text.lastIndexOf(']');
            if (end > start) return text.substring(start, end + 1);
        }
        return "[]";
    }

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        text = text.trim();
        if (text.startsWith("```")) {
            int s = text.indexOf('\n');
            int e = text.lastIndexOf("```");
            if (s > 0 && e > s) text = text.substring(s, e).trim();
        }
        int start = text.indexOf('{');
        if (start >= 0) {
            int end = text.lastIndexOf('}');
            if (end > start) return text.substring(start, end + 1);
        }
        return "{}";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static class Statement {
        private final String text;
        Statement(String text) { this.text = text; }
    }
}
