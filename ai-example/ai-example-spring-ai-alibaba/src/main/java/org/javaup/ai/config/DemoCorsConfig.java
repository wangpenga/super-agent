package org.javaup.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @program: 企业级别深度设计 AI Agent。添加 wangpeng 微信，添加时备注 super 来获取项目的完整资料
 * @description: 配置类
 * @author: wangpeng
 **/
@Configuration
public class DemoCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/agent/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
    }

}
