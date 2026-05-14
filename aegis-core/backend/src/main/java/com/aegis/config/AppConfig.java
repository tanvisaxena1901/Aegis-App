package com.aegis.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AppConfig {

    @Bean
    WebClient aiWebClient(AiServiceProperties properties, WebClient.Builder builder) {
        return builder.baseUrl(properties.baseUrl()).build();
    }
}
