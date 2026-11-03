package com.hengce.aps.web.config;

import com.hengce.aps.common.idempotency.IdempotencyStore;
import com.hengce.aps.web.idempotency.IdempotencyInterceptor;
import com.hengce.aps.web.idempotency.InMemoryIdempotencyStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册幂等拦截器。
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    /**
     * MVP 幂等键存储；接入 Redis 后替换本 Bean 即可。
     *
     * @return 幂等键存储
     */
    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    /**
     * 幂等拦截器 Bean。
     *
     * @param idempotencyStore 幂等键存储
     * @return 拦截器
     */
    @Bean
    public IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore idempotencyStore) {
        return new IdempotencyInterceptor(idempotencyStore);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor(idempotencyStore()))
                .addPathPatterns("/api/**");
    }
}
