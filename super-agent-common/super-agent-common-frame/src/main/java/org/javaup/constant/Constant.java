package org.javaup.constant;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 常量类
 * @author: wangpeng
 **/

public class Constant {

    public static final String PREFIX_DISTINCTION_NAME = "prefix.distinction.name";

    public static final String DEFAULT_PREFIX_DISTINCTION_NAME = "super-agent";

    public static final String SPRING_INJECT_PREFIX_DISTINCTION_NAME = "${"+PREFIX_DISTINCTION_NAME+":"+DEFAULT_PREFIX_DISTINCTION_NAME+"}";

}
