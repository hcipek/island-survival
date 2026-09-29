package com.example.testproject.config;

import com.example.testproject.live.WorldSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorldSocketHandler worldSocketHandler;

    public WebSocketConfig(WorldSocketHandler worldSocketHandler) {
        this.worldSocketHandler = worldSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(worldSocketHandler, "/ws/worlds/*")
                .setAllowedOriginPatterns(WebConfig.ALLOWED_ORIGIN_PATTERNS);
    }
}
