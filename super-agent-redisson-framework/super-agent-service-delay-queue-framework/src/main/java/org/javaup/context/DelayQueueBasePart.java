package org.javaup.context;

import org.javaup.config.DelayQueueProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.redisson.api.RedissonClient;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 延迟队列配置信息
 * @author: wangpeng
 **/
@Data
@AllArgsConstructor
public class DelayQueueBasePart {

    private final RedissonClient redissonClient;

    private final DelayQueueProperties delayQueueProperties;
}
