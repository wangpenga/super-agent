package org.javaup.config;

import org.javaup.lease.RedisLeaseManager;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 自动配置类
 * @author: wangpeng
 **/

public class ServiceLeaseAutoConfiguration {

    @Bean
    public RedisLeaseManager redisLeaseManager(RedissonClient redissonClient) {
        return new RedisLeaseManager(redissonClient);
    }
}
