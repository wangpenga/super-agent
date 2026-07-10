package com.baidu.fsg.uid.worker;

import com.baidu.fsg.uid.utils.ValuedEnum;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 枚举定义
 * @author: wangpeng
 **/

public enum WorkerNodeType implements ValuedEnum<Integer> {

    CONTAINER(1), ACTUAL(2);

    private final Integer type;

    private WorkerNodeType(Integer type) {
        this.type = type;
    }

    @Override
    public Integer value() {
        return type;
    }

}
