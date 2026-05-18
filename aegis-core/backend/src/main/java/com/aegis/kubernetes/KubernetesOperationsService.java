package com.aegis.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class KubernetesOperationsService {

    private static final int DEFAULT_LOG_LINES = 160;
    private static final int MAX_LOG_LINES = 500;
    private static final int HIGH_RESTART_THRESHOLD = 3;

    private final KubernetesClientProvider clientProvider;
    private final ObjectMapper objectMapper;

    public Mono<List<KubernetesPodSummary>> pods(String namespace) {
        return blocking(() -> {
            JsonNode root = executeJson(coreApi().listNamespacedPod(namespace).buildCall(null));
            List<KubernetesPodSummary> pods = new ArrayList<>();
            for (JsonNode item : iterable(root.path("items"))) {
                pods.add(toPodSummary(item));
            }
            pods.sort(Comparator.comparing(KubernetesPodSummary::name));
            return pods;
        });
    }

    public Mono<EnvironmentHealthResponse> environmentHealth() {
        return blocking(() -> {
            CoreV1Api coreApi = coreApi();
            AppsV1Api appsApi = appsApi();
            JsonNode namespaces = executeJson(coreApi.listNamespace().buildCall(null));
            JsonNode pods = executeJson(coreApi.listPodForAllNamespaces().buildCall(null));
            JsonNode deployments = executeJson(appsApi.listDeploymentForAllNamespaces().buildCall(null));
            JsonNode events = executeJson(coreApi.listEventForAllNamespaces().buildCall(null));

            Map<String, EnvironmentAccumulator> environments = new LinkedHashMap<>();
            for (JsonNode namespace : iterable(namespaces.path("items"))) {
                String name = namespace.path("metadata").path("name").asText("");
                if (!name.isBlank()) {
                    environments.put(name, new EnvironmentAccumulator(name));
                }
            }

            for (JsonNode podNode : iterable(pods.path("items"))) {
                KubernetesPodSummary pod = toPodSummary(podNode);
                accumulator(environments, pod.namespace()).addPod(pod);
            }

            for (JsonNode deploymentNode : iterable(deployments.path("items"))) {
                KubernetesDeploymentSummary deployment = toDeploymentSummary(deploymentNode);
                accumulator(environments, deployment.namespace()).addDeployment(deployment);
            }

            for (JsonNode eventNode : iterable(events.path("items"))) {
                KubernetesEventSummary event = toEventSummary(eventNode);
                accumulator(environments, event.namespace()).addEvent(event);
            }

            List<EnvironmentHealth> health = environments.values().stream()
                    .map(EnvironmentAccumulator::toHealth)
                    .sorted(Comparator
                            .comparing(EnvironmentHealth::severity, Comparator.comparingInt(this::severityRank).reversed())
                            .thenComparing(EnvironmentHealth::failingPods, Comparator.reverseOrder())
                            .thenComparing(EnvironmentHealth::warningEvents, Comparator.reverseOrder())
                            .thenComparing(EnvironmentHealth::namespace))
                    .toList();
            return new EnvironmentHealthResponse(health, Instant.now());
        });
    }

    public Mono<List<KubernetesDeploymentSummary>> deployments(String namespace) {
        return blocking(() -> {
            JsonNode root = executeJson(appsApi().listNamespacedDeployment(namespace).buildCall(null));
            List<KubernetesDeploymentSummary> deployments = new ArrayList<>();
            for (JsonNode item : iterable(root.path("items"))) {
                deployments.add(toDeploymentSummary(item));
            }
            deployments.sort(Comparator.comparing(KubernetesDeploymentSummary::name));
            return deployments;
        });
    }

    public Mono<List<KubernetesEventSummary>> events(String namespace) {
        return blocking(() -> {
            JsonNode root = executeJson(coreApi().listNamespacedEvent(namespace).buildCall(null));
            List<KubernetesEventSummary> events = new ArrayList<>();
            for (JsonNode item : iterable(root.path("items"))) {
                events.add(toEventSummary(item));
            }
            events.sort(Comparator.comparing(KubernetesEventSummary::lastSeen, Comparator.nullsLast(Comparator.reverseOrder())));
            return events.stream().limit(80).toList();
        });
    }

    public Mono<KubernetesPodLogs> podLogs(String namespace, String podName, String container, Integer tailLines) {
        return blocking(() -> {
            int requestedLines = clampTailLines(tailLines);
            String logs = coreApi().readNamespacedPodLog(podName, namespace)
                    .container(blankToNull(container))
                    .tailLines(requestedLines)
                    .timestamps(true)
                    .execute();

            return new KubernetesPodLogs(
                    namespace,
                    podName,
                    blankToNull(container),
                    requestedLines,
                    logs == null || logs.isBlank() ? List.of() : logs.lines().toList(),
                    Instant.now()
            );
        });
    }

    public Mono<KubernetesDeploymentDetail> describeDeployment(String namespace, String deploymentName) {
        return blocking(() -> {
            JsonNode deployment = executeJson(appsApi().readNamespacedDeployment(deploymentName, namespace).buildCall(null));
            return new KubernetesDeploymentDetail(
                    toDeploymentSummary(deployment),
                    stringMap(deployment.path("metadata").path("labels")),
                    stringMap(deployment.path("metadata").path("annotations")),
                    deploymentConditions(deployment),
                    deploymentContainers(deployment),
                    toRolloutStatus(deployment)
            );
        });
    }

    public Mono<KubernetesRolloutStatus> rolloutStatus(String namespace, String deploymentName) {
        return blocking(() -> toRolloutStatus(
                executeJson(appsApi().readNamespacedDeployment(deploymentName, namespace).buildCall(null))
        ));
    }

    private CoreV1Api coreApi() throws Exception {
        ApiClient client = clientProvider.defaultClient();
        return new CoreV1Api(client);
    }

    private AppsV1Api appsApi() throws Exception {
        ApiClient client = clientProvider.defaultClient();
        return new AppsV1Api(client);
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

    private KubernetesPodSummary toPodSummary(JsonNode pod) {
        JsonNode metadata = pod.path("metadata");
        JsonNode status = pod.path("status");
        JsonNode spec = pod.path("spec");
        JsonNode containerStatuses = status.path("containerStatuses");

        int readyContainers = 0;
        int restarts = 0;
        List<String> containers = new ArrayList<>();
        List<String> statusReasons = new ArrayList<>();
        for (JsonNode container : iterable(containerStatuses)) {
            String containerName = container.path("name").asText("");
            if (container.path("ready").asBoolean(false)) {
                readyContainers++;
            }
            restarts += container.path("restartCount").asInt(0);
            containers.add(containerName);
            String waitingReason = container.path("state").path("waiting").path("reason").asText("");
            String waitingMessage = container.path("state").path("waiting").path("message").asText("");
            if (!waitingReason.isBlank()) {
                statusReasons.add(containerName + ": " + waitingReason + detailSuffix(waitingMessage));
            }
            String terminatedReason = container.path("state").path("terminated").path("reason").asText("");
            if (!terminatedReason.isBlank()) {
                statusReasons.add(containerName + ": terminated/" + terminatedReason);
            }
            String lastTerminatedReason = container.path("lastState").path("terminated").path("reason").asText("");
            if (!lastTerminatedReason.isBlank() && container.path("restartCount").asInt(0) > 0) {
                statusReasons.add(containerName + ": previous termination/" + lastTerminatedReason);
            }
        }

        for (JsonNode condition : iterable(status.path("conditions"))) {
            if ("False".equalsIgnoreCase(condition.path("status").asText(""))) {
                String type = condition.path("type").asText("");
                String reason = condition.path("reason").asText("");
                statusReasons.add("Pod condition " + type + "=False" + detailSuffix(reason));
            }
        }

        String phase = status.path("phase").asText("Unknown");
        int totalContainers = containerStatuses.isArray() ? containerStatuses.size() : 0;
        return new KubernetesPodSummary(
                metadata.path("namespace").asText(""),
                metadata.path("name").asText(""),
                phase,
                readyContainers,
                totalContainers,
                restarts,
                spec.path("nodeName").asText(""),
                containers,
                statusReasons.stream().distinct().toList(),
                isFailingPod(phase, readyContainers, totalContainers, restarts, statusReasons),
                instant(metadata.path("creationTimestamp").asText(null))
        );
    }

    private KubernetesDeploymentSummary toDeploymentSummary(JsonNode deployment) {
        JsonNode metadata = deployment.path("metadata");
        JsonNode spec = deployment.path("spec");
        JsonNode status = deployment.path("status");
        return new KubernetesDeploymentSummary(
                metadata.path("namespace").asText(""),
                metadata.path("name").asText(""),
                spec.path("replicas").asInt(0),
                status.path("readyReplicas").asInt(0),
                status.path("updatedReplicas").asInt(0),
                status.path("availableReplicas").asInt(0),
                spec.path("strategy").path("type").asText("RollingUpdate"),
                stringMap(spec.path("selector").path("matchLabels")),
                deploymentImages(deployment),
                instant(metadata.path("creationTimestamp").asText(null))
        );
    }

    private KubernetesEventSummary toEventSummary(JsonNode event) {
        JsonNode metadata = event.path("metadata");
        JsonNode involvedObject = event.path("involvedObject");
        return new KubernetesEventSummary(
                metadata.path("namespace").asText(""),
                event.path("type").asText(""),
                event.path("reason").asText(""),
                event.path("message").asText(""),
                involvedObject.path("kind").asText("") + "/" + involvedObject.path("name").asText(""),
                event.path("count").asInt(0),
                instant(event.path("lastTimestamp").asText(event.path("eventTime").asText(null)))
        );
    }

    private KubernetesRolloutStatus toRolloutStatus(JsonNode deployment) {
        JsonNode metadata = deployment.path("metadata");
        JsonNode spec = deployment.path("spec");
        JsonNode status = deployment.path("status");
        int desired = spec.path("replicas").asInt(0);
        int ready = status.path("readyReplicas").asInt(0);
        int updated = status.path("updatedReplicas").asInt(0);
        int available = status.path("availableReplicas").asInt(0);
        long generation = metadata.path("generation").asLong(0);
        long observedGeneration = status.path("observedGeneration").asLong(0);
        boolean rolloutReady = desired == ready && desired == updated && desired == available && observedGeneration >= generation;

        return new KubernetesRolloutStatus(
                metadata.path("namespace").asText(""),
                metadata.path("name").asText(""),
                rolloutReady,
                rolloutReady
                        ? "Deployment is fully rolled out."
                        : "Deployment rollout is still converging.",
                desired,
                ready,
                updated,
                available,
                generation,
                observedGeneration,
                deploymentConditions(deployment)
        );
    }

    private List<String> deploymentImages(JsonNode deployment) {
        List<String> images = new ArrayList<>();
        for (JsonNode container : iterable(deployment.path("spec").path("template").path("spec").path("containers"))) {
            images.add(container.path("name").asText("") + "=" + container.path("image").asText(""));
        }
        return images;
    }

    private List<String> deploymentContainers(JsonNode deployment) {
        List<String> containers = new ArrayList<>();
        for (JsonNode container : iterable(deployment.path("spec").path("template").path("spec").path("containers"))) {
            containers.add(container.path("name").asText("") + " uses " + container.path("image").asText(""));
        }
        return containers;
    }

    private List<String> deploymentConditions(JsonNode deployment) {
        List<String> conditions = new ArrayList<>();
        for (JsonNode condition : iterable(deployment.path("status").path("conditions"))) {
            conditions.add(condition.path("type").asText("")
                    + "="
                    + condition.path("status").asText("")
                    + " / "
                    + condition.path("reason").asText("")
                    + " / "
                    + condition.path("message").asText(""));
        }
        return conditions;
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
        return values;
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

    private int clampTailLines(Integer tailLines) {
        if (tailLines == null) {
            return DEFAULT_LOG_LINES;
        }
        return Math.max(1, Math.min(MAX_LOG_LINES, tailLines));
    }

    private Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private EnvironmentAccumulator accumulator(Map<String, EnvironmentAccumulator> environments, String namespace) {
        return environments.computeIfAbsent(namespace == null || namespace.isBlank() ? "unknown" : namespace, EnvironmentAccumulator::new);
    }

    private boolean isFailingPod(
            String phase,
            int readyContainers,
            int totalContainers,
            int restarts,
            List<String> statusReasons
    ) {
        String normalizedPhase = phase == null ? "" : phase;
        if (!"Running".equalsIgnoreCase(normalizedPhase) && !"Succeeded".equalsIgnoreCase(normalizedPhase)) {
            return true;
        }
        if (totalContainers > 0 && readyContainers < totalContainers && !"Succeeded".equalsIgnoreCase(normalizedPhase)) {
            return true;
        }
        if (restarts >= HIGH_RESTART_THRESHOLD) {
            return true;
        }
        return statusReasons.stream().anyMatch(this::isCriticalPodReason);
    }

    private boolean isCriticalPodReason(String reason) {
        String text = reason.toLowerCase();
        return text.contains("crashloopbackoff")
                || text.contains("imagepullbackoff")
                || text.contains("errimagepull")
                || text.contains("createcontainerconfigerror")
                || text.contains("oomkilled");
    }

    private String detailSuffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " / " + detail;
    }

    private int severityRank(EnvironmentSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 3;
            case WARNING -> 2;
            case HEALTHY -> 1;
        };
    }

    private <T> Mono<T> blocking(CheckedSupplier<T> supplier) {
        return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private final class EnvironmentAccumulator {
        private final String namespace;
        private final List<FailingPodSignal> failingPodSignals = new ArrayList<>();
        private final List<String> signals = new ArrayList<>();
        private final Set<String> warningEventKeys = new HashSet<>();
        private int pods;
        private int runningPods;
        private int restarts;
        private int deployments;
        private int readyDeployments;

        private EnvironmentAccumulator(String namespace) {
            this.namespace = namespace;
        }

        private void addPod(KubernetesPodSummary pod) {
            pods++;
            if ("Running".equalsIgnoreCase(pod.phase())) {
                runningPods++;
            }
            restarts += pod.restarts();
            if (pod.failing()) {
                List<String> reasons = pod.statusReasons().isEmpty()
                        ? List.of(defaultPodReason(pod))
                        : pod.statusReasons();
                failingPodSignals.add(new FailingPodSignal(
                        pod.namespace(),
                        pod.name(),
                        pod.phase(),
                        pod.readyContainers(),
                        pod.totalContainers(),
                        pod.restarts(),
                        pod.nodeName(),
                        reasons
                ));
                signals.add("Pod " + pod.namespace() + "/" + pod.name() + ": " + String.join("; ", reasons));
            }
        }

        private void addDeployment(KubernetesDeploymentSummary deployment) {
            deployments++;
            if (deployment.replicas() == deployment.readyReplicas()
                    && deployment.replicas() == deployment.updatedReplicas()
                    && deployment.replicas() == deployment.availableReplicas()) {
                readyDeployments++;
            } else {
                signals.add("Deployment " + deployment.namespace() + "/" + deployment.name()
                        + " ready " + deployment.readyReplicas() + "/" + deployment.replicas()
                        + ", available " + deployment.availableReplicas() + "/" + deployment.replicas());
            }
        }

        private void addEvent(KubernetesEventSummary event) {
            if ("Warning".equalsIgnoreCase(event.type())) {
                warningEventKeys.add(event.involvedObject() + "/" + event.reason() + "/" + event.message());
                if (signals.size() < 12) {
                    signals.add("Event " + event.namespace() + " " + event.involvedObject()
                            + " " + event.reason() + ": " + event.message());
                }
            }
        }

        private EnvironmentHealth toHealth() {
            int warnings = warningEventKeys.size();
            boolean criticalPodReason = failingPodSignals.stream()
                    .flatMap(signal -> signal.reasons().stream())
                    .anyMatch(KubernetesOperationsService.this::isCriticalPodReason);
            EnvironmentSeverity severity;
            if (criticalPodReason || failingPodSignals.stream().anyMatch(signal -> !"Running".equalsIgnoreCase(signal.phase()))) {
                severity = EnvironmentSeverity.CRITICAL;
            } else if (!failingPodSignals.isEmpty() || warnings > 0 || readyDeployments < deployments) {
                severity = EnvironmentSeverity.WARNING;
            } else {
                severity = EnvironmentSeverity.HEALTHY;
            }
            return new EnvironmentHealth(
                    namespace,
                    namespace,
                    severity,
                    pods,
                    runningPods,
                    failingPodSignals.size(),
                    restarts,
                    warnings,
                    deployments,
                    readyDeployments,
                    failingPodSignals.stream().limit(8).toList(),
                    signals.stream().distinct().limit(12).toList()
            );
        }

        private String defaultPodReason(KubernetesPodSummary pod) {
            if (!"Running".equalsIgnoreCase(pod.phase())) {
                return "phase=" + pod.phase();
            }
            if (pod.totalContainers() > 0 && pod.readyContainers() < pod.totalContainers()) {
                return "containers ready " + pod.readyContainers() + "/" + pod.totalContainers();
            }
            return pod.restarts() + " restarts";
        }
    }
}
