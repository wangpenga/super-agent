package org.javaup.ai.eval.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.eval.data.EvalDataset;
import org.javaup.ai.eval.data.ExchangeProxy;
import org.javaup.ai.eval.mapper.EvalDatasetMapper;
import org.javaup.ai.eval.mapper.ExchangeProxyMapper;
import org.javaup.ai.eval.remote.RetrievalRpcResult;
import org.javaup.ai.eval.remote.impl.RagRetrievalRestClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 人工抽检服务
 * <p>
 * 核心功能：
 * <ol>
 *   <li>为测试集问题生成 LLM 答案（基于检索到的 chunks）</li>
 *   <li>保存参考答案（人工录入或 LLM 预生成）</li>
 *   <li>保存人工评分和评语</li>
 * </ol>
 * <p>
 * 抽检流程：
 * <pre>
 * 数据集 → 生成答案 → 人工查看答案+chunks+文档 → 评分
 *                           ↑
 *                   可对比参考答案
 * </pre>
 *
 * @author 阿星不是程序员
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalReviewService {

    private final ChatModel chatModel;
    private final RagRetrievalRestClient retrievalClient;
    private final EvalDatasetMapper datasetMapper;
    private final ExchangeProxyMapper exchangeMapper;

    /** LLM 生成答案的系统提示词 */
    private static final String ANSWER_SYSTEM_PROMPT = """
        你是一个专业的文档问答助手。基于以下提供的文档片段，回答用户的问题。
        要求：
        1. 如果文档片段足够回答问题，给出准确、完整的答案
        2. 如果文档片段不足以回答问题，明确说"根据提供的资料无法完全回答"
        3. 引用文档内容时注明来源（文档片段编号）
        4. 答案要简洁明了，不要编造事实
        """;

    /**
     * 为测试集条目生成 LLM 答案
     * <p>
     * 流程：调主服务检索 → 将 chunks 拼入 Prompt → 调 LLM 生成 → 保存
     *
     * @param datasetId 测试集条目 ID
     * @return 生成的答案文本
     */
    public String generateAnswer(Long datasetId) {
        EvalDataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || dataset.getDocumentId() == null) {
            throw new IllegalArgumentException("测试集条目不存在或缺少文档 ID");
        }

        // 1. 检索文档 chunks
        RetrievalRpcResult result = retrievalClient.retrieve(
            dataset.getDocumentId(), dataset.getQuestion());

        List<RetrievalRpcResult.DocumentResult> allDocs = result.flattenDocuments();

        if (allDocs.isEmpty()) {
            String fallback = "⚠️ 检索未返回相关文档内容，无法生成基于文档的答案。";
            dataset.setGeneratedAnswer(fallback);
            dataset.setReviewStatus(dataset.getReviewStatus() != null && dataset.getReviewStatus() >= 1
                ? dataset.getReviewStatus() : 1);
            datasetMapper.updateById(dataset);
            return fallback;
        }

        // 2. 构建证据文本（按排序拼接 chunks）
        String evidenceText = allDocs.stream()
            .map(doc -> {
                String section = doc.getSectionPath() != null ? doc.getSectionPath() : "";
                String docName = doc.getDocumentName() != null ? doc.getDocumentName() : "";
                return "【文档：" + docName + "  章节：" + section + "】\n"
                    + (doc.getText() != null ? doc.getText() : "");
            })
            .collect(Collectors.joining("\n\n---\n\n"));

        // 3. 调用 LLM 生成答案
        String userPrompt = """
            请基于以下文档内容回答问题。

            问题：{question}

            文档内容：
            {evidence}
            """
            .replace("{question}", dataset.getQuestion())
            .replace("{evidence}", evidenceText);

        try {
            String answer = chatModel.call(new Prompt(List.of(
                new SystemMessage(ANSWER_SYSTEM_PROMPT),
                new UserMessage(userPrompt)
            ))).getResult().getOutput().getText();

            log.info("答案生成成功: datasetId={}, question='{}', answerLen={}",
                datasetId, truncate(dataset.getQuestion(), 40), answer != null ? answer.length() : 0);

            // 保存
            dataset.setGeneratedAnswer(answer);
            dataset.setReviewStatus(dataset.getReviewStatus() != null && dataset.getReviewStatus() >= 1
                ? dataset.getReviewStatus() : 1);
            datasetMapper.updateById(dataset);

            return answer;

        } catch (Exception e) {
            log.error("答案生成失败: datasetId={}", datasetId, e);
            String errorMsg = "❌ LLM 调用失败: " + e.getMessage();
            dataset.setGeneratedAnswer(errorMsg);
            dataset.setReviewStatus(1);
            datasetMapper.updateById(dataset);
            return errorMsg;
        }
    }

    /**
     * 保存参考答案
     */
    public void saveReferenceAnswer(Long datasetId, String referenceAnswer) {
        EvalDataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) return;
        dataset.setReferenceAnswer(referenceAnswer);
        datasetMapper.updateById(dataset);
        log.info("参考答案已保存: datasetId={}", datasetId);
    }

    /**
     * 保存人工评分和评语
     */
    public void saveHumanRating(Long datasetId, Integer score, String comment) {
        if (score != null && (score < 1 || score > 5)) {
            throw new IllegalArgumentException("评分必须在 1~5 之间");
        }
        EvalDataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) return;
        dataset.setHumanScore(score);
        dataset.setHumanComment(comment);
        dataset.setReviewStatus(2); // 已评分
        datasetMapper.updateById(dataset);
        log.info("人工评分已保存: datasetId={}, score={}", datasetId, score);
    }

    /**
     * 获取待抽检列表（含真实对话答案）
     */
    public List<EvalReviewItem> listReviewItems(Long documentId, Integer reviewStatus) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvalDataset>()
            .eq(EvalDataset::getStatus, 1)
            .eq(documentId != null, EvalDataset::getDocumentId, documentId)
            .eq(reviewStatus != null, EvalDataset::getReviewStatus, reviewStatus)
            .orderByAsc(EvalDataset::getDocumentId);

        List<EvalDataset> datasets = datasetMapper.selectList(wrapper);
        return datasets.stream().map(d -> buildReviewItem(d)).toList();
    }

    /**
     * 从 test_dataset 构建 EvalReviewItem（含捞取实际答案）
     */
    private EvalReviewItem buildReviewItem(EvalDataset d) {
        EvalReviewItem item = new EvalReviewItem();
        item.setDatasetId(d.getId());
        item.setDocumentId(d.getDocumentId());
        item.setQuestion(d.getQuestion());
        item.setSource(d.getSource());
        item.setGroundTruthChunkIds(d.getGroundTruthChunkIds());
        item.setReferenceAnswer(d.getReferenceAnswer());
        item.setGeneratedAnswer(d.getGeneratedAnswer());
        item.setHumanScore(d.getHumanScore());
        item.setHumanComment(d.getHumanComment());
        item.setReviewStatus(d.getReviewStatus() != null ? d.getReviewStatus() : 0);

        // 如果关联了对话，捞当时的实际回答
        if (d.getExchangeId() != null) {
            ExchangeProxy exchange = exchangeMapper.selectById(d.getExchangeId());
            if (exchange != null) {
                item.setActualAnswer(exchange.getReplyContent());
                item.setActualLatencyMs(exchange.getTotalLatencyMs());
                item.setActualSources(exchange.getSourceSnapshotList());
            }
        }

        return item;
    }

    /**
     * 从真实对话中导入测试数据
     * <p>
     * 检索 super_agent_chat_exchange 表中指定文档的已完成对话，
     * 导入为测试集条目（source=conversation_log，带 exchangeId）。
     *
     * @param documentId 文档 ID
     * @param keyword    可选关键词筛选
     * @param limit      最多导入多少条
     * @return 导入的条目数
     */
    public int importFromConversations(Long documentId, String keyword, int limit) {
        // 列出所有已完成对话（按时间倒序）
        List<ExchangeProxy> exchanges = exchangeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExchangeProxy>()
                .eq(ExchangeProxy::getExchangeState, 2)
                .isNotNull(ExchangeProxy::getReplyContent)
                .orderByDesc(ExchangeProxy::getId)
                .last("LIMIT 200")
        );

        if (exchanges.isEmpty()) {
            log.info("没有已完成的大模型对话记录");
            return 0;
        }

        int imported = 0;
        for (ExchangeProxy ex : exchanges) {
            // 跳过问题和答案太短的
            if (ex.getUserPrompt() == null || ex.getUserPrompt().trim().length() < 3) continue;
            if (ex.getReplyContent() == null || ex.getReplyContent().trim().length() < 5) continue;

            // 检查是否已存在
            var dupCheck = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getExchangeId, ex.getId());
            if (datasetMapper.selectCount(dupCheck) > 0) continue;

            // 创建数据集条目
            EvalDataset dataset = new EvalDataset();
            dataset.setDocumentId(documentId);
            dataset.setQuestion(ex.getUserPrompt().trim());
            dataset.setSource("conversation_log");
            dataset.setReferenceAnswer(ex.getReplyContent().trim());
            dataset.setGroundTruthChunkIds("[]");
            dataset.setDifficulty("medium");
            dataset.setIsActive(1);
            dataset.setExchangeId(ex.getId());
            dataset.setReviewStatus(0);
            dataset.setStatus(1);
            datasetMapper.insert(dataset);
            imported++;
        }

        log.info("从对话导入测试数据完成：imported={}", imported);
        return imported;
    }

    /**
     * 获取单条详情（含真实对话答案）
     */
    public EvalReviewItem getReviewDetail(Long datasetId) {
        EvalDataset d = datasetMapper.selectById(datasetId);
        if (d == null) return null;
        return buildReviewItem(d);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ═══════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════

    public static class EvalReviewItem {
        private Long datasetId;
        private Long documentId;
        private String question;
        private String source;
        private String groundTruthChunkIds;
        private String referenceAnswer;
        private String generatedAnswer;
        private Integer humanScore;
        private String humanComment;
        private Integer reviewStatus;

        // ═══════════════ 真实对话答案（来源 conversation_log 时） ═══════════════
        /** 当时大模型给用户的真正回答 */
        private String actualAnswer;
        /** 当时对话的耗时 */
        private Long actualLatencyMs;
        /** 当时的引用来源快照（JSON） */
        private String actualSources;

        // Getters / Setters
        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long v) { this.datasetId = v; }
        public Long getDocumentId() { return documentId; }
        public void setDocumentId(Long v) { this.documentId = v; }
        public String getQuestion() { return question; }
        public void setQuestion(String v) { this.question = v; }
        public String getSource() { return source; }
        public void setSource(String v) { this.source = v; }
        public String getGroundTruthChunkIds() { return groundTruthChunkIds; }
        public void setGroundTruthChunkIds(String v) { this.groundTruthChunkIds = v; }
        public String getReferenceAnswer() { return referenceAnswer; }
        public void setReferenceAnswer(String v) { this.referenceAnswer = v; }
        public String getGeneratedAnswer() { return generatedAnswer; }
        public void setGeneratedAnswer(String v) { this.generatedAnswer = v; }
        public Integer getHumanScore() { return humanScore; }
        public void setHumanScore(Integer v) { this.humanScore = v; }
        public String getHumanComment() { return humanComment; }
        public void setHumanComment(String v) { this.humanComment = v; }
        public Integer getReviewStatus() { return reviewStatus; }
        public void setReviewStatus(Integer v) { this.reviewStatus = v; }
        public String getActualAnswer() { return actualAnswer; }
        public void setActualAnswer(String v) { this.actualAnswer = v; }
        public Long getActualLatencyMs() { return actualLatencyMs; }
        public void setActualLatencyMs(Long v) { this.actualLatencyMs = v; }
        public String getActualSources() { return actualSources; }
        public void setActualSources(String v) { this.actualSources = v; }
    }
}
