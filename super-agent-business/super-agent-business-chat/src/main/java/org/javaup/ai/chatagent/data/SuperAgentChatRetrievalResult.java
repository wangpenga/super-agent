package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.math.BigDecimal;

/**
 * 检索结果快照表 (super_agent_chat_retrieval_result)
 * <p>
 * 记录每次 RAG 检索的详细结果，包括每个子问题在各个检索通道（向量/关键词/混合）
 * 中的排名、分数（原始分、RRF 融合分、Rerank 精排分）、是否通过相关性闸门、
 * 是否被提升到父块、是否最终被选入 Prompt 等全链路信息。
 * <p>
 * 用于检索效果的可观测性分析和调优。
 *
 * @author wangpeng
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_retrieval_result")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatRetrievalResult extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属业务会话编号 (dialogue_code) */
    @TableField("dialogue_code")
    private String conversationId;

    /** 所属轮次id */
    @TableField("exchange_id")
    private Long exchangeId;

    /** 本轮执行 trace id */
    @TableField("trace_id")
    private String traceId;

    /** 子问题下标（从1开始） */
    @TableField("sub_question_index")
    private Integer subQuestionIndex;

    /** 子问题文本 */
    @TableField("sub_question")
    private String subQuestion;

    /** 检索通道：vector / keyword / hybrid */
    @TableField("channel_type")
    private String channelType;

    /** 通道内原始排名 */
    @TableField("channel_rank")
    private Integer channelRank;

    /** RRF 融合后排名 */
    @TableField("rrf_rank")
    private Integer rrfRank;

    /** 最终排名（rerank 后或最终裁剪后） */
    @TableField("final_rank")
    private Integer finalRank;

    /** 通道原始分数 */
    @TableField("original_score")
    private BigDecimal originalScore;

    /** RRF 融合分数 */
    @TableField("rrf_score")
    private BigDecimal rrfScore;

    /** Rerank 精排分数 */
    @TableField("rerank_score")
    private BigDecimal rerankScore;

    /** 是否通过相关性闸门：1是 0否 */
    @TableField("gate_passed")
    private Integer gatePassed;

    /** 是否提升到父块：1是 0否 */
    @TableField("is_elevated")
    private Integer isElevated;

    /** 是否被选入最终 Prompt：1是 0否 */
    @TableField("is_selected")
    private Integer isSelected;

    /** 选入/排除原因 */
    @TableField("selection_reason")
    private String selectionReason;

    /** 文档id */
    @TableField("document_id")
    private Long documentId;

    /** 文档名称 */
    @TableField("document_name")
    private String documentName;

    /** 文档切块id */
    @TableField("chunk_id")
    private Long chunkId;

    /** 切块序号 */
    @TableField("chunk_no")
    private Integer chunkNo;

    /** 父块id */
    @TableField("parent_block_id")
    private Long parentBlockId;

    /** 父块序号 */
    @TableField("parent_block_no")
    private Integer parentBlockNo;

    /** 章节路径 */
    @TableField("section_path")
    private String sectionPath;

    /** 文档块内容预览（前500字符） */
    @TableField("chunk_text_preview")
    private String chunkTextPreview;

    /** 文档块字符数 */
    @TableField("chunk_char_count")
    private Integer chunkCharCount;
}
