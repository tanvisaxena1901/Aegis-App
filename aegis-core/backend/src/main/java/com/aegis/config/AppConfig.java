package com.aegis.config;

import com.aegis.runtime.PostgresRuntimeProperties;
import com.aegis.runtime.MemoryGraphProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AiServiceProperties.class, AegisPlatformProperties.class, PostgresRuntimeProperties.class, MemoryGraphProperties.class})
public class AppConfig {

    @Bean
    WebClient aiWebClient(AiServiceProperties properties, WebClient.Builder builder) {
        return builder.baseUrl(properties.baseUrl()).build();
    }
}
