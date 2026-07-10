package com.baidu.fsg.uid.buffer;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 处理器
 * @author: wangpeng
 **/

@FunctionalInterface
public interface RejectedTakeBufferHandler {

    void rejectTakeBuffer(RingBuffer ringBuffer);
}
