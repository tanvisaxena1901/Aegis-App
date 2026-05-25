package com.aegis.runtime;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

@Configuration
@ConditionalOnProperty(name = "aegis.runtime.state-store", havingValue = "postgres")
public class PostgresRuntimeConfig {

    @Bean
    ConnectionFactory runtimeConnectionFactory(PostgresRuntimeProperties properties) {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(HOST, blankOrDefault(properties.host(), "127.0.0.1"))
                .option(PORT, properties.port() == null ? 5432 : properties.port())
                .option(DATABASE, blankOrDefault(properties.database(), "aegis_runtime"))
                .option(USER, blankOrDefault(properties.username(), "aegis"))
                .option(PASSWORD, blankOrDefault(properties.password(), "aegis"))
                .build();
        return io.r2dbc.spi.ConnectionFactories.get(options);
    }

    @Bean
    DatabaseClient runtimeDatabaseClient(ConnectionFactory runtimeConnectionFactory) {
        return DatabaseClient.builder()
                .connectionFactory(runtimeConnectionFactory)
                .build();
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
