package com.example.testproject.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /// Pages allowed to call the API: the Flutter web client served locally
    /// or from another device on the LAN. Shared with [WebSocketConfig].
    public static final String[] ALLOWED_ORIGIN_PATTERNS = {"http://localhost:*", "http://192.168.*.*:*"};

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
