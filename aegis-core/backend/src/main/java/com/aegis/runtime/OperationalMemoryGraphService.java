package com.aegis.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "aegis.runtime.memory-graph", havingValue = "memory", matchIfMissing = true)
@Primary
public class OperationalMemoryGraphService implements OperationalMemoryGraphPort {

    private final ConcurrentMap<String, MemoryNode> nodes = new ConcurrentHashMap<>();
    private final List<MemoryEdge> edges = new ArrayList<>();

    @Override
    public String backendLabel() {
        return "IN_MEMORY_GRAPH_NEO4J_OPENSEARCH_READY";
    }

    public synchronized void ingestIncident(RuntimeIncidentRequest request, String incidentId, String workflowId) {
        String clusterId = blankOrDefault(request.clusterId(), "dev-cluster");
        putNode("service:" + request.service(), request.service(), "Service", Map.of(
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        putNode("incident:" + incidentId, incidentId, "Incident", Map.of(
                "symptom", request.symptom(),
                "severity", blankOrDefault(request.severity(), "MEDIUM"),
                "workflowId", workflowId
        ));
        putNode("deployment:" + request.namespace() + ":" + request.service(), request.service(), "Deployment", Map.of(
                "namespace", request.namespace(),
                "clusterId", clusterId
        ));
        connect("service:" + request.service(), "deployment:" + request.namespace() + ":" + request.service(), "DEPLOYED_TO", 0.8);
        connect("deployment:" + request.namespace() + ":" + request.service(), "incident:" + incidentId, "TRIGGERED", 0.9);
        for (String signal : safeSignals(request.signals()).stream().limit(8).toList()) {
            String signalId = "signal:" + Math.abs(signal.hashCode());
            putNode(signalId, shortLabel(signal), "Signal", Map.of("value", signal));
            connect(signalId, "incident:" + incidentId, "CORRELATED_WITH", 0.6);
        }
    }

    public synchronized void ingestMemoryWrites(String workflowId, List<String> memoryWrites) {
        for (String memoryWrite : memoryWrites == null ? List.<String>of() : memoryWrites) {
            if (memoryWrite == null || memoryWrite.isBlank()) {
                continue;
            }
            String id = "memory:" + workflowId + ":" + Math.abs(memoryWrite.hashCode());
            putNode(id, shortLabel(memoryWrite), "Memory", Map.of("workflowId", workflowId, "value", memoryWrite));
            connect("workflow:" + workflowId, id, "CORRELATED_WITH", 0.5);
        }
    }

    public synchronized void ensureWorkflowNode(String workflowId, String service) {
        putNode("workflow:" + workflowId, workflowId, "Workflow", Map.of("service", service));
    }

    public List<String> retrieve(String service, String namespace, String symptom) {
        String normalized = (service + " " + namespace + " " + symptom).toLowerCase();
        return nodes.values().stream()
                .filter(node -> ("Incident".equals(node.type()) || "Signal".equals(node.type()) || "Memory".equals(node.type()))
                        && (node.label().toLowerCase().contains(service.toLowerCase())
                        || node.properties().values().stream().anyMatch(value -> normalized.contains(value.toLowerCase())
                        || value.toLowerCase().contains(service.toLowerCase()))))
                .sorted(Comparator.comparing(MemoryNode::observedAt).reversed())
                .limit(8)
                .map(node -> node.type() + " " + node.label() + " " + node.properties())
                .toList();
    }

    public synchronized OperationalMemoryGraph snapshot() {
        List<MemoryNode> nodeList = nodes.values().stream()
                .sorted(Comparator.comparing(MemoryNode::observedAt).reversed())
                .limit(60)
                .toList();
        List<MemoryEdge> edgeList = edges.stream()
                .limit(80)
                .toList();
        return new OperationalMemoryGraph(
                nodeList,
                edgeList,
                causalPaths(nodeList, edgeList),
                backendLabel(),
                Instant.now()
        );
    }

    private void putNode(String id, String label, String type, Map<String, String> properties) {
        nodes.put(id, new MemoryNode(id, label, type, properties == null ? Map.of() : Map.copyOf(properties), Instant.now()));
    }

    private void connect(String from, String to, String relationship, double weight) {
        boolean exists = edges.stream().anyMatch(edge -> edge.from().equals(from)
                && edge.to().equals(to)
                && edge.relationship().equals(relationship));
        if (!exists) {
            edges.add(new MemoryEdge(from, to, relationship, weight));
        }
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

    private List<String> safeSignals(List<String> signals) {
        return signals == null ? List.of() : signals.stream().filter(signal -> signal != null && !signal.isBlank()).toList();
    }

    private String shortLabel(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
