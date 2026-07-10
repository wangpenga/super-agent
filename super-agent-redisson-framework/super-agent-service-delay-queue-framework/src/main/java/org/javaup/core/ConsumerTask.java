package org.javaup.core;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 延迟队列 消费者接口
 * @author: wangpeng
 **/
public interface ConsumerTask {

    void execute(String content);

    String topic();
}
