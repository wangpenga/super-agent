package org.javaup.core;

import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 延迟队列 延迟队列
 * @author: wangpeng
 **/
public class DelayProduceQueue extends DelayBaseQueue{

    private final RDelayedQueue<String> delayedQueue;
    public DelayProduceQueue(RedissonClient redissonClient, final String relTopic) {
        super(redissonClient, relTopic);
        this.delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
    }

    public void offer(String content, long delayTime, TimeUnit timeUnit) {
        delayedQueue.offer(content,delayTime,timeUnit);
    }
}
