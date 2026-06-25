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

/**
 * 业务对话归档主表 (super_agent_chat_dialogue)
 * <p>
 * 记录每个对话会话的元信息，包括会话模式、锁定的文档范围等。
 * 一个会话 (dialogue) 包含多个轮次 (exchange)。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_dialogue")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatDialogue extends BaseTableData {

    /** 主键id */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 业务会话编号 (dialogue_code) */
    @TableField("dialogue_code")
    private String conversationId;

    /**
     * 会话阶段 (dialogue_stage)
     * 1:空闲 2:进行中
     */
    @TableField("dialogue_stage")
    private Integer sessionStatus;

    /**
     * 聊天模式 (chat_mode)
     * 1:当前文档问答 2:开放式提问
     */
    @TableField("chat_mode")
    private Integer chatMode;

    /** 当前会话显式锁定的提问文档id */
    @TableField("selected_document_id")
    private Long selectedDocumentId;

    /** 当前会话显式锁定的提问文档名称 */
    @TableField("selected_document_name")
    private String selectedDocumentName;
}
