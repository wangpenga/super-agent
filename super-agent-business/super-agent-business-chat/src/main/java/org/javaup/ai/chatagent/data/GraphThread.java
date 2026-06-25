package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Graph 线程表 (GRAPH_THREAD)
 * <p>
 * Spring AI Alibaba Graph 框架的线程存储表。每个线程对应一个业务会话（threadName = conversationId），
 * 用于管理 Agent 图执行的生命周期。与 GRAPH_CHECKPOINT 表配合使用，实现对话状态的持久化。
 * <p>
 * is_released 标记用于指示该线程是否已被释放（会话结束或重置时设置）。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("GRAPH_THREAD")
public class GraphThread {

    /** Graph 内部线程主键 */
    @TableId(value = "thread_id", type = IdType.INPUT)
    private String threadId;

    /** 业务线程名，通常就是 conversationId */
    private String threadName;

    /** 是否已经被释放 */
    @TableField("is_released")
    private Boolean released;
}
