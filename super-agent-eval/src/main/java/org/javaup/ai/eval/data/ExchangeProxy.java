package org.javaup.ai.eval.data;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 对话轮次归档代理（只读）
 * <p>
 * 映射主服务的 super_agent_chat_exchange 表，
 * 用于获取真实对话中大模型的回答，在人工抽检时展示。
 *
 * @author 阿星不是程序员
 */
@Data
@TableName("super_agent_chat_exchange")
public class ExchangeProxy {

    private Long id;

    /** 用户提问 */
    private String userPrompt;

    /** 助手回答内容 —— 这就是当时大模型给用户的真正答案 */
    private String replyContent;

    /** 引用来源快照（JSON） */
    private String sourceSnapshotList;

    /** 对话状态：1=进行中 2=已完成 3=失败 4=已停止 */
    private Integer exchangeState;

    /** 总耗时 */
    private Long totalLatencyMs;
}
