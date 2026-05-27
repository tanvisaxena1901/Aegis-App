package com.aegis.config;

import com.aegis.runtime.PostgresRuntimeProperties;
import com.aegis.runtime.MemoryGraphProperties;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AiServiceProperties.class, AegisPlatformProperties.class, PostgresRuntimeProperties.class, MemoryGraphProperties.class})
public class AppConfig {

    @Bean
    WebClient aiWebClient(AiServiceProperties properties, WebClient.Builder builder) {
        return builder.baseUrl(properties.baseUrl()).build();
    }

    @Bean
    CorsWebFilter corsWebFilter(
            @Value("${aegis.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,https://tanvisaxena1901.github.io}") String allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
