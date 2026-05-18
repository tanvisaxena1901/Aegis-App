package com.aegis.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class KubernetesSignalService {

    private final KubernetesClientProvider clientProvider;
    private final ObjectMapper objectMapper;

    public Mono<ClusterSnapshot> snapshot() {
        return Mono.fromCallable(this::loadSnapshot)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(new ClusterSnapshot(
                        "unavailable",
                        0,
                        0,
                        List.of("Kubernetes config is not available. Start Kind or set KUBECONFIG."),
                        Instant.now()
                ));
    }

    private ClusterSnapshot loadSnapshot() throws Exception {
        ApiClient client = clientProvider.defaultClient();
        CoreV1Api api = new CoreV1Api(client);
        JsonNode namespaces = executeJson(api.listNamespace().buildCall(null));
        JsonNode pods = executeJson(api.listPodForAllNamespaces().buildCall(null));
        JsonNode events = executeJson(api.listEventForAllNamespaces().buildCall(null));

        List<String> warnings = new ArrayList<>();
        for (JsonNode event : events.path("items")) {
            if ("Warning".equalsIgnoreCase(event.path("type").asText())) {
                warnings.add(eventSummary(event));
            }
            if (warnings.size() >= 20) {
                break;
            }
        }

        return new ClusterSnapshot(
                client.getBasePath(),
                namespaces.path("items").size(),
                pods.path("items").size(),
                warnings,
                Instant.now()
        );
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

    private String eventSummary(JsonNode event) {
        String namespace = event.path("metadata").path("namespace").asText("unknown");
        return namespace + "/" + event.path("reason").asText("") + ": " + event.path("message").asText("");
    }
}
