package org.javaup.servicelock.info;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 分布式锁 策略
 * @author: wangpeng
 **/
public enum LockTimeOutStrategy implements LockTimeOutHandler{

    FAIL(){
        @Override
        public void handler(String lockName) {
            String msg = String.format("%s请求频繁",lockName);
            throw new RuntimeException(msg);
        }
    }
}
