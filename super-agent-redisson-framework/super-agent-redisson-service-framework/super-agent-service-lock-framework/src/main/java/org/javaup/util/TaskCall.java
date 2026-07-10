package org.javaup.util;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 分布式锁 方法类型执行 有返回值的业务
 * @author: wangpeng
 **/
@FunctionalInterface
public interface TaskCall<V> {

    V call();
}
