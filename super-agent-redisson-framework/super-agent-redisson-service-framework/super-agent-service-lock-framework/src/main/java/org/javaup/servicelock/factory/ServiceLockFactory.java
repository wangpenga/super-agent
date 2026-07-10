package org.javaup.servicelock.factory;

import org.javaup.core.ManageLocker;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.ServiceLocker;
import lombok.AllArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 分布式锁类型工厂
 * @author: wangpeng
 **/
@AllArgsConstructor
public class ServiceLockFactory {

    private final ManageLocker manageLocker;

    public ServiceLocker getLock(LockType lockType){
        ServiceLocker lock;
        switch (lockType) {
            case Fair:
                lock = manageLocker.getFairLocker();
                break;
            case Write:
                lock = manageLocker.getWriteLocker();
                break;
            case Read:
                lock = manageLocker.getReadLocker();
                break;
            default:
                lock = manageLocker.getReentrantLocker();
                break;
        }
        return lock;
    }
}
