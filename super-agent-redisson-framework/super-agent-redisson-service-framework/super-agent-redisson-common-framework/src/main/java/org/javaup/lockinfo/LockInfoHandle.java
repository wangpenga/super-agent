package org.javaup.lockinfo;

import org.aspectj.lang.JoinPoint;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 锁信息抽象
 * @author: wangpeng
 **/
public interface LockInfoHandle {

    String getLockName(JoinPoint joinPoint, String name, String[] keys);

    String simpleGetLockName(String name,String[] keys);
}
