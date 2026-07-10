package org.javaup.parser;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.NativeDetector;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 对DefaultParameterNameDiscoverer进行扩展，添加{@link LocalVariableTableParameterNameDiscoverer}
 * @author: wangpeng
 **/
public class ExtParameterNameDiscoverer extends DefaultParameterNameDiscoverer {

    public ExtParameterNameDiscoverer() {
        super();
        if (!NativeDetector.inNativeImage()) {
            addDiscoverer(new LocalVariableTableParameterNameDiscoverer());
        }
    }
}
