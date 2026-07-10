package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;

import java.util.List;

/**
 * 检索可观测存储接口 —— 操作两张表：
 * <ul>
 *   <li><b>super_agent_chat_retrieval_result</b>：检索结果快照</li>
 *   <li><b>super_agent_chat_channel_execution</b>：检索通道执行详情</li>
 * </ul>
 *
 * @author wangpeng
 */
public interface RetrievalObserveStore {

    /** 批量写入检索结果（一次 RAG 检索的所有召回结果） */
    void batchSaveResults(String conversationId, long exchangeId, List<RetrievalResultView> results);

    /** 批量写入通道执行详情（每个检索通道在每个子问题上的执行指标） */
    void batchSaveChannelExecutions(String conversationId, long exchangeId, List<ChannelExecutionView> executions);

    /** 查询某轮对话的所有检索结果 */
    List<RetrievalResultView> listResults(String conversationId, long exchangeId);

    /** 查询某轮对话的所有通道执行详情 */
    List<ChannelExecutionView> listChannelExecutions(String conversationId, long exchangeId);

    /** 删除某会话的所有检索数据（会话重置时调用） */
    void deleteByConversation(String conversationId);
}
