package com.aegis.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KubernetesWatcherService {

    private static final List<String> WATCHED_RESOURCES = List.of("pods", "deployments", "events", "replicasets", "nodes");

    private final KubernetesClientProvider clientProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, WatcherResourceStatus> statuses = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${aegis.platform.watcher-refresh-ms:30000}", initialDelayString = "2000")
    public void refresh() {
        try {
            ApiClient client = clientProvider.defaultClient();
            CoreV1Api coreApi = new CoreV1Api(client);
            AppsV1Api appsApi = new AppsV1Api(client);
            update("pods", "all namespaces", coreApi.listPodForAllNamespaces().buildCall(null));
            update("deployments", "all namespaces", appsApi.listDeploymentForAllNamespaces().buildCall(null));
            update("events", "all namespaces", coreApi.listEventForAllNamespaces().buildCall(null));
            update("replicasets", "all namespaces", appsApi.listReplicaSetForAllNamespaces().buildCall(null));
            update("nodes", "cluster", coreApi.listNode().buildCall(null));
        } catch (Exception exception) {
            Instant now = Instant.now();
            WATCHED_RESOURCES.forEach(resource -> statuses.put(resource, new WatcherResourceStatus(
                    resource,
                    resource.equals("nodes") ? "cluster" : "all namespaces",
                    0,
                    "",
                    "ERROR",
                    exception.getMessage(),
                    now
            )));
        }
    }

    public WatcherStatus status() {
        if (statuses.isEmpty()) {
            refresh();
        }
        List<WatcherResourceStatus> resources = new ArrayList<>(statuses.values());
        resources.sort(Comparator.comparing(WatcherResourceStatus::resource));
        return new WatcherStatus(
                true,
                WATCHED_RESOURCES,
                resources,
                Instant.now()
        );
    }

    private void update(String resource, String scope, Call call) throws Exception {
        JsonNode root = executeJson(call);
        statuses.put(resource, new WatcherResourceStatus(
                resource,
                scope,
                root.path("items").size(),
                root.path("metadata").path("resourceVersion").asText(""),
                "WATCHING",
                "",
                Instant.now()
        ));
    }

    private JsonNode executeJson(Call call) throws Exception {
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            String content = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Kubernetes API returned " + response.code() + ": " + content);
            }
            return objectMapper.readTree(content);
        }
    }
}
