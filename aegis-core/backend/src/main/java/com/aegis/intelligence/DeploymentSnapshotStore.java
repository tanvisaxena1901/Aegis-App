package com.aegis.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeploymentSnapshotStore {

    private final com.aegis.kubernetes.KubernetesClientProvider clientProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, DeploymentSpecSnapshot> previous = new ConcurrentHashMap<>();
    private final Map<String, DeploymentSpecSnapshot> current = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${aegis.platform.watcher-refresh-ms:30000}", initialDelayString = "5000")
    public void refresh() {
        try {
            ApiClient client = clientProvider.defaultClient();
            AppsV1Api appsApi = new AppsV1Api(client);
            try (Response response = appsApi.listDeploymentForAllNamespaces().buildCall(null).execute()) {
                ResponseBody body = response.body();
                String content = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Kubernetes API returned " + response.code() + ": " + content);
                }
                JsonNode root = objectMapper.readTree(content);
                for (JsonNode deployment : iterable(root.path("items"))) {
                    DeploymentSpecSnapshot next = snapshot(deployment);
                    String key = key(next.namespace(), next.name());
                    DeploymentSpecSnapshot existing = current.put(key, next);
                    if (existing != null && !sameSpec(existing, next)) {
                        previous.put(key, existing);
                    }
                }
            }
        } catch (Exception ignored) {
            // The API layer reports lack of baseline or live data; scheduled collection stays best-effort.
        }
    }

    public Optional<DeploymentSpecSnapshot> previous(String namespace, String deploymentName) {
        return Optional.ofNullable(previous.get(key(namespace, deploymentName)));
    }

    public Optional<DeploymentSpecSnapshot> current(String namespace, String deploymentName) {
        return Optional.ofNullable(current.get(key(namespace, deploymentName)));
    }

    public void capture(JsonNode deployment) {
        DeploymentSpecSnapshot next = snapshot(deployment);
        String key = key(next.namespace(), next.name());
        DeploymentSpecSnapshot existing = current.put(key, next);
        if (existing != null && !sameSpec(existing, next)) {
            previous.put(key, existing);
        }
    }

    private DeploymentSpecSnapshot snapshot(JsonNode deployment) {
        JsonNode metadata = deployment.path("metadata");
        JsonNode spec = deployment.path("spec");
        return new DeploymentSpecSnapshot(
                metadata.path("namespace").asText(""),
                metadata.path("name").asText(""),
                spec.path("replicas").asInt(0),
                containerField(deployment, "image"),
                env(deployment),
                resourceLimit(deployment, "memory"),
                resourceLimit(deployment, "cpu"),
                probeTimeouts(deployment),
                Instant.now()
        );
    }

    private boolean sameSpec(DeploymentSpecSnapshot left, DeploymentSpecSnapshot right) {
        return left.replicas() == right.replicas()
                && left.images().equals(right.images())
                && left.env().equals(right.env())
                && left.memoryLimits().equals(right.memoryLimits())
                && left.cpuLimits().equals(right.cpuLimits())
                && left.probeTimeouts().equals(right.probeTimeouts());
    }

    private Map<String, String> containerField(JsonNode deployment, String field) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode container : iterable(containers(deployment))) {
            values.put(container.path("name").asText(""), container.path(field).asText(""));
        }
        return values;
    }

    private Map<String, String> env(JsonNode deployment) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode container : iterable(containers(deployment))) {
            String containerName = container.path("name").asText("");
            for (JsonNode env : iterable(container.path("env"))) {
                values.put(containerName + "." + env.path("name").asText(""), env.path("value").asText("<from-secret-or-config>"));
            }
        }
        return values;
    }

    private Map<String, String> resourceLimit(JsonNode deployment, String resource) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode container : iterable(containers(deployment))) {
            values.put(container.path("name").asText(""), container.path("resources").path("limits").path(resource).asText(""));
        }
        return values;
    }

    private Map<String, String> probeTimeouts(JsonNode deployment) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode container : iterable(containers(deployment))) {
            String name = container.path("name").asText("");
            values.put(name + ".readiness", container.path("readinessProbe").path("timeoutSeconds").asText(""));
            values.put(name + ".liveness", container.path("livenessProbe").path("timeoutSeconds").asText(""));
        }
        return values;
    }

    private JsonNode containers(JsonNode deployment) {
        return deployment.path("spec").path("template").path("spec").path("containers");
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return () -> new Iterator<>() {
            private final Iterator<JsonNode> delegate = node.elements();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public JsonNode next() {
                return delegate.next();
            }
        };
    }

    private String key(String namespace, String deploymentName) {
        return namespace + "/" + deploymentName;
    }
}
