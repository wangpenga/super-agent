package org.javaup.ai.eval.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.config.EvalProperties;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * 判题服务 —— rerank 分层判题 + LLM 兜底
 * <p>
 * 核心策略：利用项目中已有的 reranker (BAAI/bge-reranker-v2-m3) 分数，
 * 通过分层阈值减少 LLM Judge 调用量约 90%。
 * <p>
 *          rerank ≥ 0.5  ──→ relevant（零 LLM 调用）
 *   0.3 ≤ rerank < 0.5  ──→ LLM Judge 二次确认
 *          rerank < 0.3  ──→ irrelevant（零 LLM 调用）
 *
 * @author wangpeng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalJudgeService {

    private final ChatModel chatModel;
    private final EvalProperties evalProperties;

    /**
     * 判断单个文档块是否与问题相关
     *
     * @param question    用户问题
     * @param chunkText   文档块文本
     * @param rerankScore rerank 分数（可为 null）
     * @return 判断结果
     */
    public JudgeResult judge(String question, String chunkText, Double rerankScore) {
        EvalProperties.JudgeProperties cfg = evalProperties.getJudge();

        if (rerankScore != null) {
            if (rerankScore >= cfg.getRerankThresholdHigh()) {
                log.debug("Rerank 分层: relevant (score={} ≥ threshold={})",
                    String.format("%.4f", rerankScore), cfg.getRerankThresholdHigh());
                return JudgeResult.relevant("rerank_score=" + rerankScore, "rerank");
            }
            if (rerankScore < cfg.getRerankThresholdLow()) {
                log.debug("Rerank 分层: irrelevant (score={} < threshold={})",
                    String.format("%.4f", rerankScore), cfg.getRerankThresholdLow());
                return JudgeResult.irrelevant("rerank_score=" + rerankScore, "rerank");
            }
        }

        // 无 rerank 分或在争议区间 → LLM Judge
        return llmJudge(question, chunkText);
    }

    /**
     * LLM-as-Judge 二次确认
     */
    private JudgeResult llmJudge(String question, String chunkText) {
        String systemPrompt = "你是一个 RAG 相关性评估专家。判断以下文档片段是否包含回答用户问题的相关信息。只输出 JSON。";
        String userPrompt = """
            问题：{question}

            文档片段：{chunk_text}

            只输出以下 JSON：
            {"relevant": true, "reason": "简要说明为什么相关"}
            或
            {"relevant": false, "reason": "简要说明为什么不相关"}
            """
            .replace("{question}", question)
            .replace("{chunk_text}", chunkText != null ? chunkText : "");

        try {
            String response = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
            ))).getResult().getOutput().getText();

            JSONObject json = JSONUtil.parseObj(extractJson(response));
            boolean relevant = json.getBool("relevant", false);
            String reason = json.getStr("reason", "");

            log.debug("LLM Judge: relevant={}, reason='{}'", relevant, reason);
            return relevant
                ? JudgeResult.relevant(reason, "llm_judge")
                : JudgeResult.irrelevant(reason, "llm_judge");

        } catch (Exception e) {
            log.warn("LLM Judge 调用失败，兜底判为不相关: {}", e.getMessage());
            return JudgeResult.irrelevant("LLM Judge 异常: " + e.getMessage(), "llm_judge_fallback");
        }
    }

    /**
     * 从 LLM 响应中提取 JSON
     */
    private String extractJson(String text) {
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
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    /**
     * 判题结果
     */
    public record JudgeResult(boolean relevant, String reason, String method) {
        public static JudgeResult relevant(String reason, String method) {
            return new JudgeResult(true, reason, method);
        }
        public static JudgeResult irrelevant(String reason, String method) {
            return new JudgeResult(false, reason, method);
        }
    }
}
