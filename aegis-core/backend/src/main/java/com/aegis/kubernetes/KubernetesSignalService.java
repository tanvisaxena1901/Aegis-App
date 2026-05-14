package com.aegis.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1PodList;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class KubernetesSignalService {

    private final KubernetesClientProvider clientProvider;

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
        V1NamespaceList namespaces = api.listNamespace().execute();
        V1PodList pods = api.listPodForAllNamespaces().execute();

        List<String> warnings = api.listEventForAllNamespaces().execute().getItems().stream()
                .filter(event -> "Warning".equalsIgnoreCase(event.getType()))
                .map(this::eventSummary)
                .limit(20)
                .toList();

        return new ClusterSnapshot(
                client.getBasePath(),
                namespaces.getItems().size(),
                pods.getItems().size(),
                warnings,
                Instant.now()
        );
    }

    private String eventSummary(CoreV1Event event) {
        String namespace = event.getMetadata() == null ? "unknown" : event.getMetadata().getNamespace();
        return namespace + "/" + event.getReason() + ": " + event.getMessage();
    }
}
