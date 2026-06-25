package org.javaup.ai.chatagent.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 运行时任务注册表 - 第二道并发防线
 * <p>
 * 基于内存 {@link ConcurrentHashMap} 管理正在运行的任务。
 * <p>
 * <b>为什么 Redis 租约之外还需要这个？</b>
 * Redis 租约（30s TTL）是第一道防线，但极端情况下同一请求可能在同一节点上
 * 被并发处理两次（如网络抖动导致重试）。这个注册表是第二道防线——
 * 用 JVM 内存的 putIfAbsent 原子操作确保同一 conversationId 在单节点内只注册一次。
 * <p>
 * <b>三道并发防线总览：</b>
 * <ol>
 *   <li>Redis 分布式租约 —— 跨节点防并发</li>
 *   <li>ChatRuntimeRegistry —— 单节点内存防并发（本类）</li>
 *   <li>TaskInfo.finalized CAS —— 保证收尾逻辑只执行一次</li>
 * </ol>
 *
 * @author 阿星不是程序员
 */
@Component
public class ChatRuntimeRegistry {

    /**
     * conversationId → TaskInfo 映射
     * ConcurrentHashMap 保证 putIfAbsent 的原子性
     */
    private final ConcurrentMap<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    /**
     * 注册任务（原子操作）
     *
     * @param taskInfo 要注册的任务
     * @return true=注册成功（原来没有），false=注册失败（conversationId 已存在）
     */
    public boolean register(TaskInfo taskInfo) {
        // putIfAbsent 返回 null 表示之前没有 → 注册成功
        // putIfAbsent 返回旧值表示已存在 → 注册失败
        return taskMap.putIfAbsent(taskInfo.conversationId(), taskInfo) == null;
    }

    /**
     * 按 conversationId 查找运行中的任务
     *
     * @return 可能为空 Optional
     */
    public Optional<TaskInfo> get(String conversationId) {
        return Optional.ofNullable(taskMap.get(conversationId));
    }

    /**
     * 按 conversationId 移除任务（不校验任务身份）
     */
    public void remove(String conversationId) {
        taskMap.remove(conversationId);
    }

    /**
     * 按 conversationId + 任务实例引用双重匹配移除
     * <p>
     * 只有当前注册的任务 == expectedTaskInfo 时才移除，
     * 防止误删已被新任务替换的旧引用。
     */
    public void remove(String conversationId, TaskInfo expectedTaskInfo) {
        if (conversationId == null || expectedTaskInfo == null) {
            return;
        }
        taskMap.remove(conversationId, expectedTaskInfo);
    }
}
