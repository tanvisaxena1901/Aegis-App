package com.aegis.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@ConditionalOnProperty(name = "aegis.runtime.memory-graph", havingValue = "opensearch")
public class OpenSearchOperationalMemoryGraph implements OperationalMemoryGraphPort {

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

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String indexName;

    public OpenSearchOperationalMemoryGraph(
            WebClient.Builder webClientBuilder,
            MemoryGraphProperties properties,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        MemoryGraphProperties.OpenSearchSettings openSearch = properties.opensearch();
        String baseUrl = blankOrDefault(openSearch == null ? null : openSearch.baseUrl(), "http://127.0.0.1:9200");
        this.indexName = blankOrDefault(openSearch == null ? null : openSearch.indexPrefix(), "aegis-runtime") + "-memory";
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public String backendLabel() {
        return "OPENSEARCH_READY";
    }

    @Override
    public void ingestIncident(RuntimeIncidentRequest request, String incidentId, String workflowId) {
        String clusterId = blankOrDefault(request.clusterId(), "dev-cluster");
        String serviceId = "service:" + request.service();
        String deploymentId = "deployment:" + request.namespace() + ":" + request.service();
        String incidentNodeId = "incident:" + incidentId;

        indexNode(serviceId, request.service(), "Service", Map.of(
                "service", request.service(),
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        indexNode(deploymentId, request.service(), "Deployment", Map.of(
                "service", request.service(),
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        indexNode(incidentNodeId, incidentId, "Incident", Map.of(
                "incidentId", incidentId,
                "workflowId", workflowId,
                "service", request.service(),
                "namespace", request.namespace(),
                "clusterId", clusterId,
                "severity", blankOrDefault(request.severity(), "MEDIUM"),
                "symptom", request.symptom(),
                "text", request.symptom()
        ));
        indexEdge(serviceId, deploymentId, "DEPLOYED_TO", 0.8);
        indexEdge(deploymentId, incidentNodeId, "TRIGGERED", 0.9);

        for (String signal : safeSignals(request.signals()).stream().limit(8).toList()) {
            String signalId = "signal:" + Math.abs(signal.hashCode());
            indexNode(signalId, shortLabel(signal), "Signal", Map.of(
                    "value", signal,
                    "text", signal,
                    "service", request.service(),
                    "namespace", request.namespace(),
                    "clusterId", clusterId,
                    "incidentId", incidentId,
                    "workflowId", workflowId
            ));
            indexEdge(signalId, incidentNodeId, "CORRELATED_WITH", 0.6);
        }
    }

    @Override
    public void ingestMemoryWrites(String workflowId, List<String> memoryWrites) {
        indexNode("workflow:" + workflowId, workflowId, "Workflow", Map.of(
                "service", "runtime",
                "workflowId", workflowId
        ));
        for (String memoryWrite : memoryWrites == null ? List.<String>of() : memoryWrites) {
            if (memoryWrite == null || memoryWrite.isBlank()) {
                continue;
            }
            String id = "memory:" + workflowId + ":" + Math.abs(memoryWrite.hashCode());
            indexNode(id, shortLabel(memoryWrite), "Memory", Map.of(
                    "workflowId", workflowId,
                    "value", memoryWrite,
                    "text", memoryWrite
            ));
            indexEdge("workflow:" + workflowId, id, "CORRELATED_WITH", 0.5);
        }
    }

    @Override
    public void ensureWorkflowNode(String workflowId, String service) {
        indexNode("workflow:" + workflowId, workflowId, "Workflow", Map.of(
                "service", blankOrDefault(service, "runtime"),
                "workflowId", workflowId
        ));
    }

    @Override
    public List<String> retrieve(String service, String namespace, String symptom) {
        Map<String, Object> body = Map.of(
                "size", 8,
                "query", Map.of(
                        "bool", Map.of(
                                "filter", List.of(Map.of("match", Map.of("docKind", "node"))),
                                "must", List.of(Map.of(
                                        "multi_match", Map.of(
                                                "query", String.join(" ", safeStrings(service, namespace, symptom)),
                                                "fields", SEARCH_FIELDS
                                        )
                                ))
                        )
                ),
                "sort", List.of(Map.of("observedAtEpochMs", Map.of("order", "desc")))
        );
        return search(body).stream()
                .map(this::formatOpenSearchSource)
                .toList();
    }

    @Override
    public OperationalMemoryGraph snapshot() {
        List<JsonNode> nodeSources = search(Map.of(
                "size", 60,
                "query", Map.of("match", Map.of("docKind", "node")),
                "sort", List.of(Map.of("observedAtEpochMs", Map.of("order", "desc")))
        ));
        List<JsonNode> edgeSources = search(Map.of(
                "size", 80,
                "query", Map.of("match", Map.of("docKind", "edge")),
                "sort", List.of(Map.of("observedAtEpochMs", Map.of("order", "desc")))
        ));

        List<MemoryNode> nodes = nodeSources.stream().map(this::toNode).toList();
        List<MemoryEdge> edges = edgeSources.stream().map(this::toEdge).toList();
        return new OperationalMemoryGraph(
                nodes,
                edges,
                causalPaths(nodes, edges),
                backendLabel(),
                Instant.now()
        );
    }

    private void indexNode(String id, String label, String type, Map<String, String> properties) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("docKind", "node");
        document.put("id", id);
        document.put("label", label);
        document.put("type", type);
        document.put("observedAtEpochMs", Instant.now().toEpochMilli());
        document.putAll(properties == null ? Map.of() : properties);
        indexDocument(id, document);
    }

    private void indexEdge(String from, String to, String relationship, double weight) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("docKind", "edge");
        document.put("id", "edge:" + from + ":" + relationship + ":" + to);
        document.put("from", from);
        document.put("to", to);
        document.put("relationship", relationship);
        document.put("weight", weight);
        document.put("observedAtEpochMs", Instant.now().toEpochMilli());
        indexDocument(document.get("id").toString(), document);
    }

    private void indexDocument(String id, Map<String, Object> document) {
        try {
            webClient.put()
                    .uri("/{index}/_doc/{id}", indexName, id)
                    .bodyValue(document)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
        } catch (Exception ignored) {
            // OpenSearch is best-effort so runtime workflows can continue during search outages.
        }
    }

    private List<JsonNode> search(Map<String, Object> body) {
        try {
            String response = webClient.post()
                    .uri("/{index}/_search", indexName)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            if (response == null || response.isBlank()) {
                return List.of();
            }
            JsonNode hits = objectMapper.readTree(response).path("hits").path("hits");
            List<JsonNode> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (!source.isMissingNode()) {
                    results.add(source);
                }
            }
            return results;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private MemoryNode toNode(JsonNode source) {
        return new MemoryNode(
                text(source, "id"),
                text(source, "label"),
                text(source, "type"),
                nodeProperties(source),
                Instant.ofEpochMilli(Math.max(0L, source.path("observedAtEpochMs").asLong(0L)))
        );
    }

    private MemoryEdge toEdge(JsonNode source) {
        return new MemoryEdge(
                text(source, "from"),
                text(source, "to"),
                text(source, "relationship"),
                source.path("weight").asDouble(0D)
        );
    }

    private Map<String, String> nodeProperties(JsonNode source) {
        Map<String, String> properties = new LinkedHashMap<>();
        source.properties().forEach(entry -> {
            String key = entry.getKey();
            if (!List.of("docKind", "id", "label", "type", "observedAtEpochMs").contains(key)) {
                JsonNode value = entry.getValue();
                properties.put(key, value.isTextual() ? value.asText("") : value.toString());
            }
        });
        return properties;
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

    private List<String> safeStrings(String... values) {
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned.isEmpty() ? List.of("aegis") : cleaned;
    }

    private List<String> safeSignals(List<String> signals) {
        return signals == null ? List.of() : signals.stream().filter(signal -> signal != null && !signal.isBlank()).toList();
    }

    private String shortLabel(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
