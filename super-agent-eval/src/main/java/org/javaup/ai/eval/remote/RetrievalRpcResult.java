package org.javaup.ai.eval.remote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 主服务检索 API 返回结果的简化封装
 * <p>
 * 从主服务 {@code RagRetrievalContext} 提取关键字段，
 * 避免 eval 服务直接依赖主服务的内部模型。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRpcResult {

    /** 检索问题 */
    private String retrievalQuestion;

    /** 所有检索到的证据列表（每个子问题一个组） */
    private List<SubQuestionResult> subQuestions;

    /** 使用的检索通道 */
    private List<String> usedChannels;

    /** 检索备注（超时/降级等） */
    private List<String> retrievalNotes;

    /**
     * 单个子问题的检索结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubQuestionResult {
        /** 子问题序号（从1开始） */
        private int subQuestionIndex;
        /** 子问题文本 */
        private String subQuestion;
        /** 检索到的文档列表 */
        private List<DocumentResult> documents;
        /** 选入 Prompt 的引用数 */
        private int referenceCount;
    }

    /**
     * 检索到的单个文档/块
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentResult {
        /** 文档 ID */
        private String id;
        /** chunk ID */
        private Long chunkId;
        /** 文档文本内容 */
        private String text;
        /** embedding 相似度分数（0~1） */
        private Double similarityScore;
        /** RRF 融合分数 */
        private Double rrfScore;
        /** Rerank 精排分数（0~1） */
        private Double rerankScore;
        /** 是否通过证据门控 */
        private Boolean gatePassed;
        /** 最终是否被选中 */
        private Boolean isSelected;
        /** 来源通道：vector / keyword */
        private String channel;
        /** 来源文档名称 */
        private String documentName;
        /** 章节路径 */
        private String sectionPath;
    }

    /** 判断是否为空 */
    public boolean isEmpty() {
        return subQuestions == null || subQuestions.stream()
            .allMatch(sq -> sq.getDocuments() == null || sq.getDocuments().isEmpty());
    }

    /** 展开所有子问题的文档为平铺列表（保留排序） */
    public List<DocumentResult> flattenDocuments() {
        if (subQuestions == null) return List.of();
        return subQuestions.stream()
            .filter(sq -> sq.getDocuments() != null)
            .flatMap(sq -> sq.getDocuments().stream())
            .toList();
    }
}
