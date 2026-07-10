package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 对话执行器注册表 - 调用链路第5层执行器查找
 * <p>
 * <b>职责：</b>维护 {@link ExecutionMode} → {@link ConversationExecutor} 的映射关系。
 * <p>
 * <b>初始化机制：</b>
 * 通过构造器注入 {@code List<ConversationExecutor>}，Spring 会自动收集所有
 * 实现了 ConversationExecutor 接口的 Bean（ReactAgentExecutor, RagChatExecutor,
 * ClarificationExecutor, GraphOnlyExecutor, GraphThenEvidenceExecutor），
 * 按每个执行器的 mode() 方法注册到 EnumMap 中。
 * <p>
 * <b>在调用链路中的位置：</b>
 * BusinessChatService.buildConversationExecution → executorRegistry.get(plan.getMode())
 * → 返回对应执行器 → executor.execute(taskInfo)
 * <p>
 * <b>查找失败处理：</b>如果 mode 对应的执行器不存在（如配置错误），抛出 IllegalStateException。
 *
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 对话执行器注册表 - 按 ExecutionMode 查找对应执行器
 * @author: wangpeng
 **/
@Component
public class ConversationExecutorRegistry {

    /**
     * ExecutionMode → 执行器实现的映射表
     * 使用 EnumMap 获得 O(1) 查找性能
     */
    private final Map<ExecutionMode, ConversationExecutor> executorMap = new EnumMap<>(ExecutionMode.class);

    /**
     * Spring 构造器注入：自动收集所有 ConversationExecutor 实现 Bean
     * <p>
     * 注入的 Bean 列表包括：
     * <ul>
     *   <li>ReactAgentExecutor (REACT_AGENT)</li>
     *   <li>RagChatExecutor (RETRIEVAL)</li>
     *   <li>ClarificationExecutor (CLARIFICATION)</li>
     *   <li>GraphOnlyExecutor (GRAPH_ONLY)</li>
     *   <li>GraphThenEvidenceExecutor (GRAPH_THEN_EVIDENCE)</li>
     * </ul>
     */
    public ConversationExecutorRegistry(List<ConversationExecutor> executors) {
        for (ConversationExecutor executor : executors) {
            executorMap.put(executor.mode(), executor);
        }
    }

    /**
     * 按执行模式查找对应的执行器
     *
     * @param mode 执行模式（来自 ConversationExecutionPlan.getMode()）
     * @return 对应的执行器实现
     * @throws IllegalStateException 如果未找到对应执行器
     */
    public ConversationExecutor get(ExecutionMode mode) {
        ConversationExecutor executor = executorMap.get(mode);
        if (executor == null) {
            throw new IllegalStateException("未找到执行模式对应的执行器: " + mode);
        }
        return executor;
    }
}
