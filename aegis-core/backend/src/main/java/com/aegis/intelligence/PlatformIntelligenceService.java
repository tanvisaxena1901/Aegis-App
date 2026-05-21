package com.aegis.intelligence;

import com.aegis.kubernetes.KubernetesClientProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.AuthorizationV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ResourceAttributes;
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReview;
import io.kubernetes.client.openapi.models.V1SelfSubjectAccessReviewSpec;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class PlatformIntelligenceService {

    private static final List<String> RUNBOOK_FILES = List.of(
            "runbooks/crashloopbackoff.yaml",
            "runbooks/imagepullbackoff.yaml",
            "runbooks/oomkilled.yaml",
            "runbooks/pending-pods.yaml",
            "runbooks/failed-rollout.yaml",
            "runbooks/node-pressure.yaml"
    );

    private final KubernetesClientProvider clientProvider;
    private final ObjectMapper objectMapper;
    private final NamespaceRiskClassifier namespaceRiskClassifier;
    private final DeploymentSnapshotStore deploymentSnapshotStore;

    public Mono<PlatformIntelligenceResponse> intelligence(String clusterId, String namespace) {
        return Mono.fromCallable(() -> intelligenceBlocking(cluster(clusterId), namespace))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DeploymentDiffResponse> deploymentDiff(String clusterId, String namespace, String deploymentName) {
        return Mono.fromCallable(() -> deploymentDiffBlocking(cluster(clusterId), namespace, deploymentName))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<RbacScanResponse> rbac(String clusterId, String namespace) {
        return Mono.fromCallable(() -> rbacBlocking(cluster(clusterId), namespace))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public List<ClusterContext> clusters(String clusterId) {
        String selected = cluster(clusterId);
        return List.of(
                new ClusterContext("dev-cluster", "Development", "dev", selected.equals("dev-cluster"), "configured"),
                new ClusterContext("staging-cluster", "Staging", "staging", selected.equals("staging-cluster"), "configured"),
                new ClusterContext("prod-cluster", "Production", "prod", selected.equals("prod-cluster"), "configured")
        );
    }

    public List<RunbookCodeDefinition> runbooksAsCode() {
        return RUNBOOK_FILES.stream().map(this::loadRunbook).flatMap(Optional::stream).toList();
    }

    public IncidentReplayResponse replaySample() {
        try {
            ClassPathResource resource = new ClassPathResource("incidents/crashloop-demo.json");
            JsonNode root = objectMapper.readTree(resource.getInputStream());
            List<IncidentTimelineItem> timeline = new ArrayList<>();
            for (JsonNode item : iterable(root.path("timeline"))) {
                timeline.add(new IncidentTimelineItem(
                        instant(item.path("timestamp").asText(null)),
                        item.path("stage").asText("Observed"),
                        item.path("reason").asText(""),
                        item.path("involvedObject").asText(""),
                        item.path("message").asText("")
                ));
            }
            List<PodRestartPattern> patterns = new ArrayList<>();
            for (JsonNode item : iterable(root.path("restartPatterns"))) {
                patterns.add(new PodRestartPattern(
                        root.path("namespace").asText("aegis"),
                        item.path("podName").asText(""),
                        item.path("pattern").asText("crash loop"),
                        RiskLevel.valueOf(item.path("severity").asText("HIGH")),
                        item.path("restarts").asInt(0),
                        item.path("evidence").isArray()
                                ? stream(item.path("evidence")).map(JsonNode::asText).toList()
                                : List.of()
                ));
            }
            List<DeduplicatedEvent> deduped = new ArrayList<>();
            for (JsonNode item : iterable(root.path("deduplicatedEvents"))) {
                deduped.add(new DeduplicatedEvent(
                        item.path("reason").asText(""),
                        item.path("type").asText("Warning"),
                        item.path("involvedObject").asText(""),
                        item.path("message").asText(""),
                        item.path("occurrences").asInt(1),
                        instant(item.path("lastSeen").asText(null))
                ));
            }
            return new IncidentReplayResponse(
                    root.path("replayId").asText("sample-crashloop"),
                    root.path("title").asText("CrashLoopBackOff replay"),
                    root.path("clusterId").asText("dev-cluster"),
                    root.path("namespace").asText("aegis"),
                    timeline,
                    patterns,
                    deduped,
                    stream(root.path("investigationSteps")).map(JsonNode::asText).toList(),
                    Instant.now()
            );
        } catch (Exception exception) {
            Instant now = Instant.now();
            return new IncidentReplayResponse(
                    "sample-crashloop",
                    "CrashLoopBackOff replay",
                    "dev-cluster",
                    "aegis",
                    sampleTimeline(now),
                    List.of(new PodRestartPattern("aegis", "payment-api-7d9", "crash loop", RiskLevel.HIGH, 6, List.of("CrashLoopBackOff", "BackOff x47"))),
                    List.of(new DeduplicatedEvent("BackOff", "Warning", "Pod/payment-api-7d9", "Back-off restarting failed container", 47, now)),
                    List.of("Load timeline", "Deduplicate events", "Classify restart pattern", "Evaluate CrashLoopBackOff runbook"),
                    now
            );
        }
    }

    private PlatformIntelligenceResponse intelligenceBlocking(String clusterId, String namespace) {
        NamespaceRiskProfile risk = namespaceRiskClassifier.classify(namespace);
        try {
            ApiClient client = clientProvider.defaultClient();
            CoreV1Api coreApi = new CoreV1Api(client);
            AppsV1Api appsApi = new AppsV1Api(client);
            JsonNode events = executeJson(coreApi.listNamespacedEvent(namespace).buildCall(null));
            JsonNode pods = executeJson(coreApi.listNamespacedPod(namespace).buildCall(null));
            JsonNode deployments = executeJson(appsApi.listNamespacedDeployment(namespace).buildCall(null));
            for (JsonNode deployment : iterable(deployments.path("items"))) {
                deploymentSnapshotStore.capture(deployment);
            }
            List<DriftSignal> drift = drift(deployments, namespace);
            return new PlatformIntelligenceResponse(
                    clusterId,
                    clusters(clusterId),
                    risk,
                    timeline(events),
                    deduplicatedEvents(events),
                    restartPatterns(pods, events, namespace),
                    goldenSignals(deployments, events, risk),
                    drift,
                    rbacBlocking(clusterId, namespace),
                    runbooksAsCode(),
                    "",
                    Instant.now()
            );
        } catch (Exception exception) {
            Instant now = Instant.now();
            return new PlatformIntelligenceResponse(
                    clusterId,
                    clusters(clusterId),
                    risk,
                    sampleTimeline(now),
                    List.of(new DeduplicatedEvent("ClusterUnavailable", "Warning", "Cluster/" + clusterId, exception.getMessage(), 1, now)),
                    List.of(),
                    sampleGoldenSignals(namespace, risk),
                    List.of(new DriftSignal("Cluster", namespace, clusterId, "api", "reachable", "unreachable", RiskLevel.MEDIUM)),
                    new RbacScanResponse(clusterId, List.of(), exception.getMessage(), now),
                    runbooksAsCode(),
                    exception.getMessage(),
                    now
            );
        }
    }

    private DeploymentDiffResponse deploymentDiffBlocking(String clusterId, String namespace, String deploymentName) throws Exception {
        Optional<DeploymentSpecSnapshot> previous = deploymentSnapshotStore.previous(namespace, deploymentName);
        Optional<DeploymentSpecSnapshot> current = deploymentSnapshotStore.current(namespace, deploymentName);
        if (current.isEmpty()) {
            ApiClient client = clientProvider.defaultClient();
            AppsV1Api appsApi = new AppsV1Api(client);
            JsonNode deployment = executeJson(appsApi.readNamespacedDeployment(deploymentName, namespace).buildCall(null));
            deploymentSnapshotStore.capture(deployment);
            current = deploymentSnapshotStore.current(namespace, deploymentName);
        }
        List<DeploymentSpecChange> changes = new ArrayList<>();
        if (previous.isPresent() && current.isPresent()) {
            compare(previous.get(), current.get(), changes);
        } else {
            changes.add(new DeploymentSpecChange("baseline", "not captured", "captured current spec", "Aegis will compare this deployment after the next spec change."));
        }
        return new DeploymentDiffResponse(clusterId, namespace, deploymentName, previous.isPresent(), changes, Instant.now());
    }

    private RbacScanResponse rbacBlocking(String clusterId, String namespace) {
        List<RbacPermission> permissions = new ArrayList<>();
        try {
            ApiClient client = clientProvider.defaultClient();
            AuthorizationV1Api authorizationApi = new AuthorizationV1Api(client);
            permissions.add(check(authorizationApi, namespace, "can list pods", "", "pods", "", "list"));
            permissions.add(check(authorizationApi, namespace, "can read logs", "", "pods", "log", "get"));
            permissions.add(check(authorizationApi, namespace, "can list events", "", "events", "", "list"));
            permissions.add(check(authorizationApi, namespace, "can read deployments", "apps", "deployments", "", "get"));
            permissions.add(check(authorizationApi, namespace, "can rollback deployments", "apps", "deployments", "", "patch"));
            permissions.add(check(authorizationApi, namespace, "can patch resources", "apps", "deployments", "", "patch"));
            permissions.add(check(authorizationApi, namespace, "can restart managed pods", "", "pods", "", "delete"));
            return new RbacScanResponse(clusterId, permissions, "", Instant.now());
        } catch (Exception exception) {
            return new RbacScanResponse(clusterId, permissions, exception.getMessage(), Instant.now());
        }
    }

    private RbacPermission check(
            AuthorizationV1Api api,
            String namespace,
            String capability,
            String group,
            String resource,
            String subresource,
            String verb
    ) throws Exception {
        V1ResourceAttributes attributes = new V1ResourceAttributes()
                .namespace(namespace)
                .group(group)
                .resource(resource)
                .verb(verb);
        if (!subresource.isBlank()) {
            attributes.subresource(subresource);
        }
        V1SelfSubjectAccessReview review = new V1SelfSubjectAccessReview()
                .spec(new V1SelfSubjectAccessReviewSpec().resourceAttributes(attributes));
        V1SelfSubjectAccessReview result = api.createSelfSubjectAccessReview(review).execute();
        boolean allowed = result.getStatus() != null && Boolean.TRUE.equals(result.getStatus().getAllowed());
        String reason = result.getStatus() == null || result.getStatus().getReason() == null
                ? ""
                : result.getStatus().getReason();
        return new RbacPermission(capability, verb, resource + (subresource.isBlank() ? "" : "/" + subresource), allowed, reason);
    }

    private List<IncidentTimelineItem> timeline(JsonNode events) {
        List<IncidentTimelineItem> items = new ArrayList<>();
        for (JsonNode event : iterable(events.path("items"))) {
            items.add(new IncidentTimelineItem(
                    eventTime(event),
                    timelineStage(event),
                    event.path("reason").asText(""),
                    involvedObject(event),
                    event.path("message").asText("")
            ));
        }
        items.sort(Comparator.comparing(IncidentTimelineItem::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        return items.stream().limit(60).toList();
    }

    private List<DeduplicatedEvent> deduplicatedEvents(JsonNode events) {
        Map<String, DeduplicatedEventAccumulator> grouped = new LinkedHashMap<>();
        for (JsonNode event : iterable(events.path("items"))) {
            String key = event.path("reason").asText("") + "|" + involvedObject(event) + "|" + event.path("message").asText("");
            grouped.computeIfAbsent(key, ignored -> new DeduplicatedEventAccumulator(
                    event.path("reason").asText(""),
                    event.path("type").asText(""),
                    involvedObject(event),
                    event.path("message").asText("")
            )).add(Math.max(1, event.path("count").asInt(1)), eventTime(event));
        }
        return grouped.values().stream()
                .map(DeduplicatedEventAccumulator::toEvent)
                .sorted(Comparator.comparing(DeduplicatedEvent::occurrences).reversed())
                .limit(30)
                .toList();
    }

    private List<PodRestartPattern> restartPatterns(JsonNode pods, JsonNode events, String namespace) {
        Map<String, List<String>> eventEvidence = new LinkedHashMap<>();
        for (JsonNode event : iterable(events.path("items"))) {
            eventEvidence.computeIfAbsent(involvedObject(event), ignored -> new ArrayList<>())
                    .add(event.path("reason").asText("") + ": " + event.path("message").asText(""));
        }
        List<PodRestartPattern> patterns = new ArrayList<>();
        for (JsonNode pod : iterable(pods.path("items"))) {
            String podName = pod.path("metadata").path("name").asText("");
            int restarts = 0;
            List<String> evidence = new ArrayList<>();
            for (JsonNode container : iterable(pod.path("status").path("containerStatuses"))) {
                restarts += container.path("restartCount").asInt(0);
                evidence.add(container.path("name").asText("") + " restarts=" + container.path("restartCount").asInt(0));
                addReason(evidence, container.path("state").path("waiting").path("reason").asText(""));
                addReason(evidence, container.path("lastState").path("terminated").path("reason").asText(""));
            }
            evidence.addAll(eventEvidence.getOrDefault("Pod/" + podName, List.of()).stream().limit(4).toList());
            classifyPattern(namespace, podName, restarts, evidence).ifPresent(patterns::add);
        }
        patterns.sort(Comparator.comparing(PodRestartPattern::severity).reversed().thenComparing(PodRestartPattern::restarts, Comparator.reverseOrder()));
        return patterns.stream().limit(20).toList();
    }

    private Optional<PodRestartPattern> classifyPattern(String namespace, String podName, int restarts, List<String> evidence) {
        String text = String.join(" ", evidence).toLowerCase(Locale.ROOT);
        if (text.contains("crashloopbackoff") || text.contains("back-off restarting")) {
            return Optional.of(new PodRestartPattern(namespace, podName, "crash loop", RiskLevel.HIGH, restarts, evidence));
        }
        if (text.contains("oomkilled")) {
            return Optional.of(new PodRestartPattern(namespace, podName, "OOMKilled", RiskLevel.HIGH, restarts, evidence));
        }
        if (text.contains("readiness") || text.contains("liveness") || text.contains("probe") || text.contains("unhealthy")) {
            return Optional.of(new PodRestartPattern(namespace, podName, "probe failure", RiskLevel.MEDIUM, restarts, evidence));
        }
        if (text.contains("imagepullbackoff") || text.contains("errimagepull")) {
            return Optional.of(new PodRestartPattern(namespace, podName, "image pull failure", RiskLevel.MEDIUM, restarts, evidence));
        }
        if (text.contains("evicted") || text.contains("memorypressure") || text.contains("diskpressure") || text.contains("nodepressure")) {
            return Optional.of(new PodRestartPattern(namespace, podName, "node pressure eviction", RiskLevel.HIGH, restarts, evidence));
        }
        if (restarts >= 3) {
            return Optional.of(new PodRestartPattern(namespace, podName, "restart spike", RiskLevel.MEDIUM, restarts, evidence));
        }
        return Optional.empty();
    }

    private List<GoldenSignal> goldenSignals(JsonNode deployments, JsonNode events, NamespaceRiskProfile risk) {
        Map<String, Integer> warningsByService = new LinkedHashMap<>();
        for (JsonNode event : iterable(events.path("items"))) {
            if ("Warning".equalsIgnoreCase(event.path("type").asText(""))) {
                String object = involvedObject(event).toLowerCase(Locale.ROOT);
                warningsByService.merge(object, 1, Integer::sum);
            }
        }
        List<GoldenSignal> signals = new ArrayList<>();
        for (JsonNode deployment : iterable(deployments.path("items"))) {
            JsonNode metadata = deployment.path("metadata");
            JsonNode spec = deployment.path("spec");
            JsonNode status = deployment.path("status");
            String service = metadata.path("labels").path("app").asText(metadata.path("name").asText(""));
            int desired = Math.max(1, spec.path("replicas").asInt(1));
            int ready = status.path("readyReplicas").asInt(0);
            int warnings = warningsByService.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(service.toLowerCase(Locale.ROOT)))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            double saturation = Math.max(0, Math.min(100, 100 - ((ready * 100.0) / desired) + warnings * 6.0));
            double errors = Math.min(100, warnings * 0.8 + (desired - ready) * 5.0);
            String slo = metadata.path("annotations").path("aegis.io/slo").asText(defaultSlo(service, risk));
            signals.add(new GoldenSignal(
                    service,
                    Math.round((95 + saturation * 2.4) * 10.0) / 10.0,
                    Math.round((desired * 120.0 + ready * 35.0) * 10.0) / 10.0,
                    Math.round(errors * 10.0) / 10.0,
                    Math.round(saturation * 10.0) / 10.0,
                    slo,
                    incidentPriority(service, risk, errors, saturation),
                    customerImpact(service, risk)
            ));
        }
        return signals.stream().limit(20).toList();
    }

    private List<DriftSignal> drift(JsonNode deployments, String namespace) {
        Map<String, Map<String, Object>> desired = desiredDeployments();
        List<DriftSignal> drift = new ArrayList<>();
        for (JsonNode deployment : iterable(deployments.path("items"))) {
            String name = deployment.path("metadata").path("name").asText("");
            Map<String, Object> expected = desired.get(name);
            if (expected == null) {
                continue;
            }
            compareDrift(drift, namespace, name, "replicas", stringValue(expected.get("replicas")), deployment.path("spec").path("replicas").asText(""));
            compareDrift(drift, namespace, name, "image", stringValue(expected.get("image")), firstImage(deployment));
            compareDrift(drift, namespace, name, "memory limit", stringValue(expected.get("memory")), firstLimit(deployment, "memory"));
        }
        return drift;
    }

    private Map<String, Map<String, Object>> desiredDeployments() {
        Map<String, Map<String, Object>> desired = new LinkedHashMap<>();
        for (Path root : candidateGitOpsRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            Yaml yaml = new Yaml();
            try (Stream<Path> files = Files.list(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".yml")).toList()) {
                    try (InputStream input = Files.newInputStream(file)) {
                        for (Object document : yaml.loadAll(input)) {
                            if (!(document instanceof Map<?, ?> map) || !"Deployment".equals(stringAt(map, "kind"))) {
                                continue;
                            }
                            String name = stringAt(nested(map, "metadata"), "name");
                            if (name.isBlank()) {
                                continue;
                            }
                            Map<?, ?> spec = nested(map, "spec");
                            Map<?, ?> firstContainer = firstContainer(spec);
                            Map<String, Object> expected = new LinkedHashMap<>();
                            expected.put("replicas", spec.get("replicas"));
                            expected.put("image", firstContainer.get("image"));
                            expected.put("memory", nested(nested(firstContainer, "resources"), "limits").get("memory"));
                            desired.put(name, expected);
                        }
                    } catch (Exception ignored) {
                        // Ignore individual desired-state files that are not deployment manifests.
                    }
                }
            } catch (Exception ignored) {
                // Missing local GitOps root is fine for a demo/local install.
            }
        }
        return desired;
    }

    private void compare(DeploymentSpecSnapshot previous, DeploymentSpecSnapshot current, List<DeploymentSpecChange> changes) {
        compare(changes, "replicas", String.valueOf(previous.replicas()), String.valueOf(current.replicas()), "Capacity changed.");
        compareMap(changes, "image", previous.images(), current.images(), "Image changed; verify release provenance.");
        compareMap(changes, "env", previous.env(), current.env(), "Environment changed; check config and secret references.");
        compareMap(changes, "memory limit", previous.memoryLimits(), current.memoryLimits(), "Memory limit changed; OOM or throttling risk may change.");
        compareMap(changes, "cpu limit", previous.cpuLimits(), current.cpuLimits(), "CPU limit changed; throttling risk may change.");
        compareMap(changes, "probe timeout", previous.probeTimeouts(), current.probeTimeouts(), "Probe behavior changed; readiness failures may be explained.");
        if (changes.isEmpty()) {
            changes.add(new DeploymentSpecChange("spec", "unchanged", "unchanged", "No image, env, limit, probe, or replica change detected."));
        }
    }

    private void compareMap(List<DeploymentSpecChange> changes, String field, Map<String, String> previous, Map<String, String> current, String impact) {
        List<String> keys = Stream.concat(previous.keySet().stream(), current.keySet().stream()).distinct().toList();
        for (String key : keys) {
            compare(changes, field + "." + key, previous.getOrDefault(key, ""), current.getOrDefault(key, ""), impact);
        }
    }

    private void compare(List<DeploymentSpecChange> changes, String field, String previous, String current, String impact) {
        if (!Objects.equals(previous, current)) {
            changes.add(new DeploymentSpecChange(field, blank(previous), blank(current), impact));
        }
    }

    private void compareDrift(List<DriftSignal> drift, String namespace, String name, String field, String expected, String actual) {
        if (!expected.isBlank() && !Objects.equals(expected, actual)) {
            drift.add(new DriftSignal("Deployment", namespace, name, field, expected, blank(actual), RiskLevel.MEDIUM));
        }
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

    private Optional<RunbookCodeDefinition> loadRunbook(String path) {
        try {
            Yaml yaml = new Yaml();
            Map<?, ?> root = yaml.load(new ClassPathResource(path).getInputStream());
            return Optional.of(new RunbookCodeDefinition(
                    stringAt(root, "incidentType"),
                    stringAt(root, "version"),
                    listAt(root, "steps"),
                    listAt(root, "aiAfter")
            ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private List<IncidentTimelineItem> sampleTimeline(Instant now) {
        return List.of(
                new IncidentTimelineItem(now.minusSeconds(300), "Deployment updated", "DeploymentUpdated", "Deployment/payment-api", "deployment updated"),
                new IncidentTimelineItem(now.minusSeconds(240), "ReplicaSet created", "SuccessfulCreate", "ReplicaSet/payment-api-7d9", "new ReplicaSet created"),
                new IncidentTimelineItem(now.minusSeconds(180), "Pod scheduled", "Scheduled", "Pod/payment-api-7d9", "pod scheduled"),
                new IncidentTimelineItem(now.minusSeconds(120), "Probe failed", "Unhealthy", "Pod/payment-api-7d9", "readiness probe failed"),
                new IncidentTimelineItem(now.minusSeconds(60), "Pod restarted", "Killing", "Pod/payment-api-7d9", "pod restarted"),
                new IncidentTimelineItem(now, "CrashLoopBackOff", "BackOff", "Pod/payment-api-7d9", "Back-off restarting failed container")
        );
    }

    private List<GoldenSignal> sampleGoldenSignals(String namespace, NamespaceRiskProfile risk) {
        return List.of(new GoldenSignal(
                namespace.contains("payment") ? "payment-api" : "aegis-backend",
                185.0,
                420.0,
                risk.riskLevel() == RiskLevel.HIGH ? 3.2 : 0.4,
                71.0,
                risk.riskLevel() == RiskLevel.HIGH ? "99.9%" : "99.0%",
                risk.riskLevel() == RiskLevel.HIGH ? "P1" : "P3",
                risk.riskLevel() == RiskLevel.HIGH ? "customer impact possible" : "internal impact"
        ));
    }

    private String timelineStage(JsonNode event) {
        String reason = event.path("reason").asText("");
        String message = event.path("message").asText("").toLowerCase(Locale.ROOT);
        String involvedObject = involvedObject(event);
        if (reason.equals("ScalingReplicaSet") || message.contains("scaled up")) {
            return "Deployment updated";
        }
        if (involvedObject.startsWith("ReplicaSet/") || reason.equals("SuccessfulCreate")) {
            return "ReplicaSet created";
        }
        if (reason.equals("Scheduled")) {
            return "Pod scheduled";
        }
        if (reason.equals("Unhealthy") || message.contains("probe")) {
            return "Readiness probe failed";
        }
        if (reason.equals("Killing") || message.contains("restarted")) {
            return "Pod restarted";
        }
        if (reason.equals("BackOff") || message.contains("crashloopbackoff")) {
            return "CrashLoopBackOff";
        }
        return reason.isBlank() ? "Observed" : reason;
    }

    private String involvedObject(JsonNode event) {
        JsonNode object = event.path("involvedObject");
        return object.path("kind").asText("") + "/" + object.path("name").asText("");
    }

    private Instant eventTime(JsonNode event) {
        return instant(event.path("lastTimestamp").asText(event.path("eventTime").asText(event.path("metadata").path("creationTimestamp").asText(null))));
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return Instant.parse(value);
    }

    private void addReason(List<String> evidence, String reason) {
        if (reason != null && !reason.isBlank()) {
            evidence.add(reason);
        }
    }

    private String defaultSlo(String service, NamespaceRiskProfile risk) {
        String normalized = service.toLowerCase(Locale.ROOT);
        if (normalized.contains("payment") || risk.riskLevel() == RiskLevel.HIGH) {
            return "99.9%";
        }
        return "99.0%";
    }

    private String incidentPriority(String service, NamespaceRiskProfile risk, double errors, double saturation) {
        if (risk.riskLevel() == RiskLevel.HIGH && (errors > 1.0 || saturation > 70.0)) {
            return "P1";
        }
        if (errors > 2.0 || saturation > 80.0) {
            return "P2";
        }
        return "P3";
    }

    private String customerImpact(String service, NamespaceRiskProfile risk) {
        String normalized = service.toLowerCase(Locale.ROOT);
        if (normalized.contains("payment") || risk.riskLevel() == RiskLevel.HIGH) {
            return "customer impact possible";
        }
        return "internal-dashboard/no customer impact";
    }

    private String firstImage(JsonNode deployment) {
        JsonNode containers = deployment.path("spec").path("template").path("spec").path("containers");
        return containers.isArray() && !containers.isEmpty() ? containers.get(0).path("image").asText("") : "";
    }

    private String firstLimit(JsonNode deployment, String resource) {
        JsonNode containers = deployment.path("spec").path("template").path("spec").path("containers");
        return containers.isArray() && !containers.isEmpty()
                ? containers.get(0).path("resources").path("limits").path(resource).asText("")
                : "";
    }

    private List<Path> candidateGitOpsRoots() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        String configured = System.getenv("AEGIS_GITOPS_MANIFEST_PATH");
        List<Path> roots = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            roots.add(Path.of(configured).normalize());
        }
        roots.add(cwd.resolve("../k8s").normalize());
        roots.add(cwd.resolve("aegis-core/k8s").normalize());
        return roots;
    }

    private Map<?, ?> nested(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map<?, ?> nested ? nested : Map.of();
    }

    private Map<?, ?> firstContainer(Map<?, ?> spec) {
        Object template = spec.get("template");
        Object containers = template instanceof Map<?, ?> templateMap
                ? nested(templateMap, "spec").get("containers")
                : null;
        if (containers instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> container) {
            return container;
        }
        return Map.of();
    }

    private String stringAt(Map<?, ?> map, String key) {
        return stringValue(map == null ? null : map.get(key));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> listAt(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? "<unset>" : value;
    }

    private String cluster(String clusterId) {
        return clusterId == null || clusterId.isBlank() ? "dev-cluster" : clusterId.trim();
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

    private Stream<JsonNode> stream(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode item : iterable(node)) {
            values.add(item);
        }
        return values.stream();
    }

    private final class DeduplicatedEventAccumulator {
        private final String reason;
        private final String type;
        private final String involvedObject;
        private final String message;
        private int count;
        private Instant lastSeen = Instant.EPOCH;

        private DeduplicatedEventAccumulator(String reason, String type, String involvedObject, String message) {
            this.reason = reason;
            this.type = type;
            this.involvedObject = involvedObject;
            this.message = message;
        }

        private void add(int occurrences, Instant timestamp) {
            count += occurrences;
            if (timestamp != null && timestamp.isAfter(lastSeen)) {
                lastSeen = timestamp;
            }
        }

        private DeduplicatedEvent toEvent() {
            return new DeduplicatedEvent(reason, type, involvedObject, message, count, lastSeen);
        }
    }
}
