package com.baidu.fsg.uid.buffer;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: /
 * @author: wangpeng
 **/

@FunctionalInterface
public interface BufferedUidProvider {

    List<Long> provide(long momentInSecond);
}
