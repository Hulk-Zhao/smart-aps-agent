package com.hengce.aps.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 求解进度 STOMP 配置（M2-SE-03）。
 *
 * <p>连接端点 {@code /ws/solve-tasks/{id}}（兼容无路径参数形态），订阅
 * {@code /topic/solve-tasks/{id}}；使用原生 WebSocket，不启用 SockJS。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class SolveProgressWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    /**
     * 注册 STOMP 端点：精确路径与带任务 ID 的路径均映射到同一处理器。
     *
     * @param registry 端点注册表
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/solve-tasks").setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws/solve-tasks/**").setAllowedOriginPatterns("*");
    }

    /**
     * 启用简单内存 Broker，仅广播 {@code /topic} 前缀。
     *
     * @param registry Broker 注册表
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
