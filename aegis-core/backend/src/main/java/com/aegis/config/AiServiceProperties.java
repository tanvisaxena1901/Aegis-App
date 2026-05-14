package com.aegis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aegis.ai-service")
public record AiServiceProperties(String baseUrl) {
}
