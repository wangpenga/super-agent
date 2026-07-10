package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 检索结果视图 —— 写入 super_agent_chat_retrieval_result 表
 * <p>
 * 记录单个检索结果从召回到最终选入 Prompt 的全链路排名和分数变化。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResultView {

    private Long id;
    /** 所属 traceId */
    private String traceId;
    /** 子问题序号（从 1 开始） */
    private int subQuestionIndex;
    /** 子问题文本 */
    private String subQuestion;
    /** 检索通道：vector / keyword / hybrid */
    private String channelType;
    /** 通道内原始排名 */
    private Integer channelRank;
    /** RRF 融合后排名 */
    private Integer rrfRank;
    /** 最终排名（rerank 或裁剪后） */
    private Integer finalRank;
    /** 通道原始分数 */
    private BigDecimal originalScore;
    /** RRF 融合分数 */
    private BigDecimal rrfScore;
    /** Rerank 精排分数 */
    private BigDecimal rerankScore;
    /** 是否通过相关性闸门 */
    private boolean gatePassed;
    /** 是否提升到父块 */
    private boolean isElevated;
    /** 是否最终选入 Prompt */
    private boolean isSelected;
    /** 选入/排除原因 */
    private String selectionReason;
    /** 文档 ID */
    private Long documentId;
    /** 文档名称 */
    private String documentName;
    /** 切块 ID */
    private Long chunkId;
    /** 切块序号 */
    private Integer chunkNo;
    /** 父块 ID */
    private Long parentBlockId;
    /** 父块序号 */
    private Integer parentBlockNo;
    /** 章节路径 */
    private String sectionPath;
    /** 文档块内容预览（前 500 字符） */
    private String chunkTextPreview;
    /** 文档块字符数 */
    private Integer chunkCharCount;
    private Instant createTime;
}
