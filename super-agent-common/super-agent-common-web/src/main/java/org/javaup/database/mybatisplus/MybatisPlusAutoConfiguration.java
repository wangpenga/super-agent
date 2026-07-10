package org.javaup.database.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 自动配置类
 * @author: wangpeng
 **/

public class MybatisPlusAutoConfiguration {

    @Bean
    public MetaObjectHandler metaObjectHandler(){
        return new MybatisPlusMetaObjectHandler();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
