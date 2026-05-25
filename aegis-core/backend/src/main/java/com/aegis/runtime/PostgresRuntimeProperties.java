package com.aegis.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aegis.runtime.postgres")
public record PostgresRuntimeProperties(
        String host,
        Integer port,
        String database,
        String username,
        String password
) {
}
