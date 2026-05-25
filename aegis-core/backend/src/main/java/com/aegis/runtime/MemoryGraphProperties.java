package com.aegis.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aegis.runtime")
public record MemoryGraphProperties(
        String memoryGraph,
        Neo4jSettings neo4j,
        OpenSearchSettings opensearch
) {

    public record Neo4jSettings(String uri, String username, String password) {
    }

    public record OpenSearchSettings(String baseUrl, String indexPrefix) {
    }
}
