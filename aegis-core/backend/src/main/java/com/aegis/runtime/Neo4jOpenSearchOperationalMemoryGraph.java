package com.aegis.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@ConditionalOnProperty(name = "aegis.runtime.memory-graph", havingValue = "neo4j")
public class Neo4jOpenSearchOperationalMemoryGraph implements OperationalMemoryGraphPort {

    private static final Duration OPENSEARCH_TIMEOUT = Duration.ofSeconds(15);

    private static final List<String> SEARCH_FIELDS = List.of(
            "label^3",
            "type^2",
            "service^3",
            "namespace^2",
            "symptom^2",
            "text",
            "value",
            "workflowId",
            "incidentId",
            "clusterId"
    );

    private final Driver neo4jDriver;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String indexName;

    public Neo4jOpenSearchOperationalMemoryGraph(
            Driver neo4jDriver,
            WebClient.Builder webClientBuilder,
            MemoryGraphProperties properties,
            ObjectMapper objectMapper
    ) {
        this.neo4jDriver = neo4jDriver;
        this.objectMapper = objectMapper;
        MemoryGraphProperties.OpenSearchSettings openSearch = properties.opensearch();
        String baseUrl = blankOrDefault(openSearch == null ? null : openSearch.baseUrl(), "http://127.0.0.1:9200");
        this.indexName = blankOrDefault(openSearch == null ? null : openSearch.indexPrefix(), "aegis-runtime") + "-memory";
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public String backendLabel() {
        return "NEO4J_OPENSEARCH_READY";
    }

    @Override
    public void ingestIncident(RuntimeIncidentRequest request, String incidentId, String workflowId) {
        String clusterId = blankOrDefault(request.clusterId(), "dev-cluster");
        writeNode("service:" + request.service(), request.service(), "Service", Map.of(
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        writeNode("incident:" + incidentId, incidentId, "Incident", Map.of(
                "symptom", request.symptom(),
                "severity", blankOrDefault(request.severity(), "MEDIUM"),
                "workflowId", workflowId,
                "service", request.service(),
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        writeNode("deployment:" + request.namespace() + ":" + request.service(), request.service(), "Deployment", Map.of(
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        writeEdge("service:" + request.service(), "deployment:" + request.namespace() + ":" + request.service(), "DEPLOYED_TO", 0.8);
        writeEdge("deployment:" + request.namespace() + ":" + request.service(), "incident:" + incidentId, "TRIGGERED", 0.9);
        for (String signal : safeSignals(request.signals()).stream().limit(8).toList()) {
            String signalId = "signal:" + Math.abs(signal.hashCode());
            writeNode(signalId, shortLabel(signal), "Signal", Map.of(
                    "value", signal,
                    "service", request.service(),
                    "namespace", request.namespace(),
                    "clusterId", clusterId
            ));
            writeEdge(signalId, "incident:" + incidentId, "CORRELATED_WITH", 0.6);
            indexDocument(signalId, Map.of(
                    "id", signalId,
                    "label", shortLabel(signal),
                    "type", "Signal",
                    "text", signal,
                    "service", request.service(),
                    "namespace", request.namespace(),
                    "clusterId", clusterId,
                    "incidentId", incidentId,
                    "workflowId", workflowId,
                    "observedAtEpochMs", Instant.now().toEpochMilli()
            ));
        }
        Map<String, Object> incidentDocument = new HashMap<>();
        incidentDocument.put("id", incidentId);
        incidentDocument.put("label", incidentId);
        incidentDocument.put("type", "Incident");
        incidentDocument.put("text", request.symptom());
        incidentDocument.put("service", request.service());
        incidentDocument.put("namespace", request.namespace());
        incidentDocument.put("clusterId", clusterId);
        incidentDocument.put("workflowId", workflowId);
        incidentDocument.put("severity", blankOrDefault(request.severity(), "MEDIUM"));
        incidentDocument.put("symptom", request.symptom());
        incidentDocument.put("observedAtEpochMs", Instant.now().toEpochMilli());
        indexDocument("incident:" + incidentId, incidentDocument);
    }

    @Override
    public void ingestMemoryWrites(String workflowId, List<String> memoryWrites) {
        writeNode("workflow:" + workflowId, workflowId, "Workflow", Map.of("service", "runtime", "workflowId", workflowId));
        for (String memoryWrite : memoryWrites == null ? List.<String>of() : memoryWrites) {
            if (memoryWrite == null || memoryWrite.isBlank()) {
                continue;
            }
            String id = "memory:" + workflowId + ":" + Math.abs(memoryWrite.hashCode());
            writeNode(id, shortLabel(memoryWrite), "Memory", Map.of(
                    "workflowId", workflowId,
                    "value", memoryWrite
            ));
            writeEdge("workflow:" + workflowId, id, "CORRELATED_WITH", 0.5);
            indexDocument(id, Map.of(
                    "id", id,
                    "label", shortLabel(memoryWrite),
                    "type", "Memory",
                    "text", memoryWrite,
                    "workflowId", workflowId,
                    "observedAtEpochMs", Instant.now().toEpochMilli()
            ));
        }
    }

    @Override
    public void ensureWorkflowNode(String workflowId, String service) {
        writeNode("workflow:" + workflowId, workflowId, "Workflow", Map.of(
                "service", service,
                "workflowId", workflowId
        ));
    }

    @Override
    public List<String> retrieve(String service, String namespace, String symptom) {
        List<String> fromOpenSearch = searchOpenSearch(service, namespace, symptom);
        if (!fromOpenSearch.isEmpty()) {
            return fromOpenSearch;
        }
        return retrieveFromNeo4j(service, namespace, symptom);
    }

    @Override
    public OperationalMemoryGraph snapshot() {
        List<MemoryNode> nodes = readNodes();
        List<MemoryEdge> edges = readEdges();
        return new OperationalMemoryGraph(
                nodes,
                edges,
                causalPaths(nodes, edges),
                backendLabel(),
                Instant.now()
        );
    }

    private void writeNode(String id, String label, String type, Map<String, String> properties) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("label", label);
        params.put("type", type);
        params.put("propsJson", toJson(properties));
        params.put("observedAtEpochMs", Instant.now().toEpochMilli());
        runWrite("""
                MERGE (n:RuntimeMemory {id: $id})
                SET n.label = $label,
                    n.type = $type,
                    n.propsJson = $propsJson,
                    n.observedAtEpochMs = $observedAtEpochMs
                """, params);
    }

    private void writeEdge(String from, String to, String relationship, double weight) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", from);
        params.put("to", to);
        params.put("relationship", relationship);
        params.put("weight", weight);
        params.put("observedAtEpochMs", Instant.now().toEpochMilli());
        runWrite("""
                MATCH (a:RuntimeMemory {id: $from})
                MATCH (b:RuntimeMemory {id: $to})
                MERGE (a)-[r:RELATED_TO {relationship: $relationship}]->(b)
                SET r.weight = $weight,
                    r.observedAtEpochMs = $observedAtEpochMs
                """, params);
    }

    private void runWrite(String cypher, Map<String, Object> params) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        } catch (Exception ignored) {
            // Best effort: runtime remains readable even if Neo4j is temporarily unavailable.
        }
    }

    private List<MemoryNode> readNodes() {
        List<MemoryNode> nodes = new ArrayList<>();
        String cypher = """
                MATCH (n:RuntimeMemory)
                RETURN n.id AS id,
                       n.label AS label,
                       n.type AS type,
                       n.propsJson AS propsJson,
                       n.observedAtEpochMs AS observedAtEpochMs
                ORDER BY observedAtEpochMs DESC
                LIMIT 60
                """;
        try (Session session = neo4jDriver.session()) {
            List<Record> records = session.executeRead(tx -> tx.run(cypher).list());
            for (Record record : records) {
                nodes.add(toNode(record));
            }
        } catch (Exception ignored) {
        }
        return nodes;
    }

    private List<MemoryEdge> readEdges() {
        List<MemoryEdge> edges = new ArrayList<>();
        String cypher = """
                MATCH (a:RuntimeMemory)-[r:RELATED_TO]->(b:RuntimeMemory)
                RETURN a.id AS from,
                       b.id AS to,
                       r.relationship AS relationship,
                       r.weight AS weight
                LIMIT 80
                """;
        try (Session session = neo4jDriver.session()) {
            List<Record> records = session.executeRead(tx -> tx.run(cypher).list());
            for (Record record : records) {
                edges.add(new MemoryEdge(
                        safeString(record, "from"),
                        safeString(record, "to"),
                        safeString(record, "relationship"),
                        safeDouble(record, "weight")
                ));
            }
        } catch (Exception ignored) {
        }
        return edges;
    }

    private List<String> retrieveFromNeo4j(String service, String namespace, String symptom) {
        String normalizedService = service == null ? "" : service.toLowerCase();
        String normalizedNamespace = namespace == null ? "" : namespace.toLowerCase();
        String normalizedSymptom = symptom == null ? "" : symptom.toLowerCase();
        String cypher = """
                MATCH (n:RuntimeMemory)
                WHERE toLower(coalesce(n.label, '')) CONTAINS $service
                   OR toLower(coalesce(n.propsJson, '')) CONTAINS $service
                   OR toLower(coalesce(n.propsJson, '')) CONTAINS $namespace
                   OR toLower(coalesce(n.propsJson, '')) CONTAINS $symptom
                RETURN n.id AS id,
                       n.label AS label,
                       n.type AS type,
                       n.propsJson AS propsJson,
                       n.observedAtEpochMs AS observedAtEpochMs
                ORDER BY observedAtEpochMs DESC
                LIMIT 8
                """;
        List<String> results = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = Map.of(
                    "service", normalizedService,
                    "namespace", normalizedNamespace,
                    "symptom", normalizedSymptom
            );
            List<Record> records = session.executeRead(tx -> tx.run(cypher, params).list());
            for (Record record : records) {
                results.add(formatRecord(record));
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private List<String> searchOpenSearch(String service, String namespace, String symptom) {
        Map<String, Object> body = Map.of(
                "size", 8,
                "query", Map.of(
                        "multi_match", Map.of(
                                "query", String.join(" ", safeStrings(service, namespace, symptom)),
                                "fields", SEARCH_FIELDS
                        )
                ),
                "sort", List.of(Map.of("observedAtEpochMs", Map.of("order", "desc")))
        );
        try {
            String response = webClient.post()
                    .uri("/{index}/_search", indexName)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(OPENSEARCH_TIMEOUT);
            if (response == null || response.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response);
            JsonNode hits = root.path("hits").path("hits");
            List<String> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (!source.isMissingNode()) {
                    results.add(formatOpenSearchSource(source));
                }
            }
            return results;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void indexDocument(String id, Map<String, Object> document) {
        try {
            webClient.put()
                    .uri("/{index}/_doc/{id}", indexName, id)
                    .bodyValue(document)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(OPENSEARCH_TIMEOUT);
        } catch (Exception ignored) {
            // Search remains optional; Neo4j is the source of structure.
        }
    }

    private MemoryNode toNode(Record record) {
        String propsJson = safeString(record, "propsJson");
        Map<String, String> properties = parseProperties(propsJson);
        Instant observedAt = Instant.ofEpochMilli(Math.max(0L, safeLong(record, "observedAtEpochMs")));
        return new MemoryNode(
                safeString(record, "id"),
                safeString(record, "label"),
                safeString(record, "type"),
                properties,
                observedAt
        );
    }

    private String formatRecord(Record record) {
        return safeString(record, "type") + " " + safeString(record, "label") + " " + parseProperties(safeString(record, "propsJson"));
    }

    private String formatOpenSearchSource(JsonNode source) {
        String type = text(source, "type");
        String label = text(source, "label");
        String text = Optional.ofNullable(text(source, "text")).orElse("");
        String service = text(source, "service");
        String namespace = text(source, "namespace");
        String symptom = text(source, "symptom");
        String workflowId = text(source, "workflowId");
        return type + " " + label + " {service=" + service + ", namespace=" + namespace + ", symptom=" + symptom + ", workflowId=" + workflowId + ", text=" + text + "}";
    }

    private List<String> causalPaths(List<MemoryNode> nodeList, List<MemoryEdge> edgeList) {
        Map<String, MemoryNode> byId = new LinkedHashMap<>();
        nodeList.forEach(node -> byId.put(node.id(), node));
        return edgeList.stream()
                .filter(edge -> byId.containsKey(edge.from()) && byId.containsKey(edge.to()))
                .limit(12)
                .map(edge -> byId.get(edge.from()).label() + " -[" + edge.relationship() + "]-> " + byId.get(edge.to()).label())
                .toList();
    }

    private Map<String, String> parseProperties(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception ignored) {
            return Map.of("raw", json);
        }
    }

    private String toJson(Map<String, String> properties) {
        try {
            return objectMapper.writeValueAsString(properties == null ? Map.of() : properties);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String safeString(Record record, String key) {
        try {
            return record.get(key).isNull() ? "" : record.get(key).asString("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private long safeLong(Record record, String key) {
        try {
            return record.get(key).isNull() ? 0L : record.get(key).asLong(0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private double safeDouble(Record record, String key) {
        try {
            return record.get(key).isNull() ? 0D : record.get(key).asDouble(0D);
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private List<String> safeStrings(String... values) {
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned.isEmpty() ? List.of("aegis") : cleaned;
    }

    private String shortLabel(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }

    private List<String> safeSignals(List<String> signals) {
        return signals == null ? List.of() : signals.stream().filter(signal -> signal != null && !signal.isBlank()).toList();
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
