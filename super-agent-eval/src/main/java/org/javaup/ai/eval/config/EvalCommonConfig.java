package org.javaup.ai.eval.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 评估服务通用 Bean 配置
 * <p>
 * 配置 RestTemplate（用于调用主服务检索 API）和全局 ObjectMapper。
 *
 * @author wangpeng
 */
@Configuration
public class EvalCommonConfig {

    /**
     * RestTemplate 用于调用主服务的内部检索 API。
     * 设置超时时间，避免评估线程被长时间阻塞。
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时 5 秒
        factory.setConnectTimeout(5000);
        // 读取超时 30 秒（检索管道可能较慢）
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    /**
     * 全局 ObjectMapper，统一日期格式和忽略未知字段
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
