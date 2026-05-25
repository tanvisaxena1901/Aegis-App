package com.aegis.runtime;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "aegis.runtime.memory-graph", havingValue = "neo4j")
public class Neo4jOpenSearchConfig {

    @Bean(destroyMethod = "close")
    Driver runtimeNeo4jDriver(MemoryGraphProperties properties) {
        MemoryGraphProperties.Neo4jSettings neo4j = properties.neo4j();
        String uri = blankOrDefault(neo4j == null ? null : neo4j.uri(), "bolt://127.0.0.1:7687");
        String username = blankOrDefault(neo4j == null ? null : neo4j.username(), "neo4j");
        String password = blankOrDefault(neo4j == null ? null : neo4j.password(), "neo4j");
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
