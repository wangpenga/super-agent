package org.javaup.ai.eval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 配置 —— 允许 Vue 前端跨域调用 eval 服务
 * <p>
 * eval 服务独立部署在 9090 端口，前端开发服务器在另一端口，
 * 需要显式允许跨域请求。
 *
 * @author 阿星不是程序员
 */
@Configuration
public class EvalCorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许前端开发服务器和生产环境的来源
        config.addAllowedOriginPattern("*");
        // 允许凭证（Cookie / Authorization 头）
        config.setAllowCredentials(true);
        // 允许所有标准方法
        config.addAllowedMethod("*");
        // 允许所有请求头
        config.addAllowedHeader("*");
        // 预检请求缓存 1 小时
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
