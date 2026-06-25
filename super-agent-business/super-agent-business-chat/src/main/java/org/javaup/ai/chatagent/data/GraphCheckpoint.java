package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Graph Checkpoint 表 (GRAPH_CHECKPOINT)
 * <p>
 * Spring AI Alibaba Graph 框架的检查点存储表。用于持久化 Agent 执行图的状态快照，
 * 支持对话中断后恢复。每个检查点记录当前节点 ID、下一个节点 ID 和序列化后的完整 Agent 状态。
 * <p>
 * 与 GRAPH_THREAD 表配合使用，实现对话状态的持久化和断点续传。
 *
 * @author 阿星不是程序员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("GRAPH_CHECKPOINT")
public class GraphCheckpoint {

    /** Checkpoint ID，检查点唯一标识 */
    @TableId(value = "checkpoint_id", type = IdType.INPUT)
    private String checkpointId;

    /** 关联线程 ID（对应 GraphThread.threadId） */
    private String threadId;

    /** 当前节点 ID */
    private String nodeId;

    /** 下一个节点 ID */
    private String nextNodeId;

    /** 序列化后的 Agent 状态，JSON 格式 */
    private String stateData;

    /** 保存时间 */
    private LocalDateTime savedAt;
}
