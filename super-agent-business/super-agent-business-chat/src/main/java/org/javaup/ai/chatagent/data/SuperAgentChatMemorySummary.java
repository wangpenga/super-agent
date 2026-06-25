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

import java.util.Date;

/**
 * 业务对话长期记忆摘要快照表 (super_agent_chat_memory_summary)
 * <p>
 * 存储会话的长期记忆摘要，包括已覆盖的轮次范围、压缩次数、摘要文本等。
 * 当对话轮次超过一定数量后，系统会对历史对话进行压缩，生成长摘要存入此表，
 * 以便在新一轮对话时快速装载历史上下文，而不需要每次都读取全部历史轮次。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_memory_summary")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatMemorySummary extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属业务会话编号 (dialogue_code) */
    @TableField("dialogue_code")
    private String conversationId;

    /** 长期摘要已覆盖到的最后一条 exchangeId */
    @TableField("covered_exchange_id")
    private Long coveredExchangeId;

    /** 长期摘要已覆盖的轮次数 */
    @TableField("covered_exchange_count")
    private Integer coveredExchangeCount;

    /** 累计压缩次数 */
    @TableField("compression_count")
    private Integer compressionCount;

    /** 摘要版本号 */
    @TableField("summary_version")
    private Integer summaryVersion;

    /** 编排阶段直接使用的长期摘要文本 */
    @TableField("summary_text")
    private String summaryText;

    /** 长期摘要结构化 JSON */
    @TableField("summary_json")
    private String summaryJson;

    /** 摘要覆盖源轮次的最后更新时间 */
    @TableField("last_source_edit_time")
    private Date lastSourceEditTime;
}
