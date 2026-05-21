package com.aegis.remediation;

import com.aegis.kubernetes.KubernetesClientProvider;
import com.aegis.intelligence.NamespaceRiskClassifier;
import com.aegis.intelligence.NamespaceRiskProfile;
import com.aegis.intelligence.RiskLevel;
import com.aegis.platform.PlatformSafetyService;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.PatchUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class RemediationService {

    private static final List<String> BLOCKED_NAMESPACES = List.of(
            "kube-system",
            "kube-public",
            "kube-node-lease"
    );
    private static final List<String> MANAGED_POD_OWNERS = List.of(
            "ReplicaSet",
            "StatefulSet",
            "DaemonSet",
            "Job"
    );

    private final KubernetesClientProvider clientProvider;
    private final PlatformSafetyService platformSafetyService;
    private final NamespaceRiskClassifier namespaceRiskClassifier;

    public Mono<RemediationPlan> plan(RemediationRequest request) {
        return Mono.fromCallable(() -> {
            NamespaceRiskProfile namespaceRisk = namespaceRiskClassifier.classify(request.namespace());
            int riskScore = riskScore(request.action(), namespaceRisk);
            String dryRunPatch = dryRunPatch(request);
            return new RemediationPlan(
                    request.action(),
                    trim(request.namespace()),
                    trim(request.targetName()),
                    commandPreview(request),
                    risk(request.action()),
                    namespaceRisk.riskLevel().name(),
                    riskScore,
                    dryRunCommand(request),
                    dryRunPatch,
                    dryRunDiff(request, dryRunPatch, riskScore),
                    true,
                    platformSafetyService.status().canMutateWithApproval(),
                    deterministicChecks(request.action()),
                    guardrails(request.action()),
                    Instant.now()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<RemediationResponse> execute(RemediationRequest request) {
        return Mono.fromCallable(() -> executeBlocking(request)).subscribeOn(Schedulers.boundedElastic());
    }

    private RemediationResponse executeBlocking(RemediationRequest request) throws Exception {
        validateApproval(request);
        return switch (request.action()) {
            case ROLLOUT_RESTART_DEPLOYMENT -> restartDeployment(request);
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD -> restartManagedPod(request);
            case ROLLBACK_DEPLOYMENT -> rollbackDeployment(request);
            case SCALE_DEPLOYMENT -> scaleDeployment(request);
            case PATCH_RESOURCE_LIMITS -> patchResourceLimits(request);
        };
    }

    private void validateApproval(RemediationRequest request) {
        platformSafetyService.validateMutationAllowed();
        String namespace = trim(request.namespace());
        String targetName = trim(request.targetName());
        String reason = trim(request.reason());
        if (BLOCKED_NAMESPACES.contains(namespace)) {
            throw new IllegalArgumentException("Remediation is blocked for system namespace " + namespace);
        }
        if (!request.approved() || !"APPROVE".equals(trim(request.confirmation()))) {
            throw new IllegalArgumentException("Human approval is required. Type APPROVE before executing remediation.");
        }
        if (targetName.length() < 2) {
            throw new IllegalArgumentException("Target name is too short.");
        }
        if (reason.length() < 12) {
            throw new IllegalArgumentException("A specific remediation reason is required.");
        }
        NamespaceRiskProfile namespaceRisk = namespaceRiskClassifier.classify(namespace);
        if ((namespaceRisk.riskLevel() == RiskLevel.HIGH || namespaceRisk.riskLevel() == RiskLevel.CRITICAL)
                && reason.length() < 25) {
            throw new IllegalArgumentException("High-risk namespaces require a more specific remediation reason.");
        }
        if (request.action() == RemediationAction.SCALE_DEPLOYMENT
                && (request.replicas() == null || request.replicas() < 0 || request.replicas() > 50)) {
            throw new IllegalArgumentException("Scale remediation requires replicas between 0 and 50.");
        }
        if (request.action() == RemediationAction.PATCH_RESOURCE_LIMITS
                && (trim(request.containerName()).isBlank()
                || (trim(request.cpuLimit()).isBlank() && trim(request.memoryLimit()).isBlank()))) {
            throw new IllegalArgumentException("Resource limit patch requires a container and at least one CPU or memory limit.");
        }
    }

    private RemediationResponse restartDeployment(RemediationRequest request) throws Exception {
        ApiClient client = clientProvider.defaultClient();
        AppsV1Api appsApi = new AppsV1Api(client);
        String patch = """
                {
                  "spec": {
                    "template": {
                      "metadata": {
                        "annotations": {
                          "kubectl.kubernetes.io/restartedAt": "%s",
                          "aegis.io/remediation-reason": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(Instant.now(), jsonEscape(request.reason()));
        PatchUtils.patch(
                V1Deployment.class,
                () -> appsApi.patchNamespacedDeployment(
                                trim(request.targetName()),
                                trim(request.namespace()),
                                new V1Patch(patch)
                        )
                        .fieldManager("aegis-remediation")
                        .buildCall(null),
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                client
        );
        return response(
                request,
                "APPROVED_AND_EXECUTED",
                "Requested rollout restart for deployment " + trim(request.namespace()) + "/" + trim(request.targetName())
        );
    }

    private RemediationResponse restartManagedPod(RemediationRequest request) throws Exception {
        CoreV1Api coreApi = coreApi();
        V1Pod pod = coreApi.readNamespacedPod(trim(request.targetName()), trim(request.namespace())).execute();
        V1ObjectMeta metadata = pod.getMetadata();
        List<V1OwnerReference> ownerReferences = metadata == null || metadata.getOwnerReferences() == null
                ? List.of()
                : metadata.getOwnerReferences();
        boolean managed = ownerReferences.stream()
                .map(V1OwnerReference::getKind)
                .anyMatch(MANAGED_POD_OWNERS::contains);
        if (!managed) {
            throw new IllegalArgumentException("Pod restart is blocked because the pod is not managed by a ReplicaSet, StatefulSet, DaemonSet, or Job.");
        }
        coreApi.deleteNamespacedPod(trim(request.targetName()), trim(request.namespace()))
                .gracePeriodSeconds(30)
                .propagationPolicy("Background")
                .execute();
        return response(
                request,
                "APPROVED_AND_EXECUTED",
                "Requested restart for managed pod " + trim(request.namespace()) + "/" + trim(request.targetName())
                        + ". Its controller should recreate it if desired replicas require it."
        );
    }

    private RemediationResponse rollbackDeployment(RemediationRequest request) throws Exception {
        List<String> command = List.of(
                "kubectl",
                "rollout",
                "undo",
                "deployment/" + trim(request.targetName()),
                "-n",
                trim(request.namespace())
        );
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Rollback command timed out.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Rollback failed: " + output);
        }
        return response(
                request,
                "APPROVED_AND_EXECUTED",
                output.isBlank()
                        ? "Requested rollback for deployment " + trim(request.namespace()) + "/" + trim(request.targetName())
                        : output
        );
    }

    private RemediationResponse scaleDeployment(RemediationRequest request) throws Exception {
        ApiClient client = clientProvider.defaultClient();
        AppsV1Api appsApi = new AppsV1Api(client);
        String patch = """
                {
                  "spec": {
                    "replicas": %d
                  }
                }
                """.formatted(request.replicas());
        PatchUtils.patch(
                V1Deployment.class,
                () -> appsApi.patchNamespacedDeployment(
                                trim(request.targetName()),
                                trim(request.namespace()),
                                new V1Patch(patch)
                        )
                        .fieldManager("aegis-remediation")
                        .buildCall(null),
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                client
        );
        return response(
                request,
                "APPROVED_AND_EXECUTED",
                "Scaled deployment " + trim(request.namespace()) + "/" + trim(request.targetName())
                        + " to " + request.replicas() + " replicas."
        );
    }

    private RemediationResponse patchResourceLimits(RemediationRequest request) throws Exception {
        ApiClient client = clientProvider.defaultClient();
        AppsV1Api appsApi = new AppsV1Api(client);
        String patch = """
                {
                  "spec": {
                    "template": {
                      "spec": {
                        "containers": [
                          {
                            "name": "%s",
                            "resources": {
                              "limits": {%s}
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """.formatted(jsonEscape(request.containerName()), resourceLimitsPatch(request));
        PatchUtils.patch(
                V1Deployment.class,
                () -> appsApi.patchNamespacedDeployment(
                                trim(request.targetName()),
                                trim(request.namespace()),
                                new V1Patch(patch)
                        )
                        .fieldManager("aegis-remediation")
                        .buildCall(null),
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                client
        );
        return response(
                request,
                "APPROVED_AND_EXECUTED",
                "Patched resource limits for " + trim(request.containerName()) + " in deployment "
                        + trim(request.namespace()) + "/" + trim(request.targetName()) + "."
        );
    }

    private RemediationResponse response(RemediationRequest request, String status, String summary) {
        return new RemediationResponse(
                request.action(),
                trim(request.namespace()),
                trim(request.targetName()),
                status,
                summary,
                guardrails(request.action()),
                Instant.now()
        );
    }

    private CoreV1Api coreApi() throws Exception {
        ApiClient client = clientProvider.defaultClient();
        return new CoreV1Api(client);
    }

    private String commandPreview(RemediationRequest request) {
        String namespace = trim(request.namespace());
        String targetName = trim(request.targetName());
        return switch (request.action()) {
            case ROLLOUT_RESTART_DEPLOYMENT -> "kubectl rollout restart deployment/" + targetName + " -n " + namespace;
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD -> "kubectl delete pod/" + targetName + " -n " + namespace;
            case ROLLBACK_DEPLOYMENT -> "kubectl rollout undo deployment/" + targetName + " -n " + namespace;
            case SCALE_DEPLOYMENT -> "kubectl scale deployment/" + targetName + " --replicas=" + request.replicas() + " -n " + namespace;
            case PATCH_RESOURCE_LIMITS -> "kubectl set resources deployment/" + targetName
                    + " -c " + trim(request.containerName())
                    + resourceFlagPreview(request)
                    + " -n " + namespace;
        };
    }

    private String risk(RemediationAction action) {
        return switch (action) {
            case ROLLOUT_RESTART_DEPLOYMENT -> "Rolling restart can briefly reduce capacity while new pods become ready.";
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD -> "Managed pod replacement depends on the owning controller and readiness probes.";
            case ROLLBACK_DEPLOYMENT -> "Rollback changes the deployment template to the previous rollout revision.";
            case SCALE_DEPLOYMENT -> "Scaling changes capacity and can increase load on dependent services.";
            case PATCH_RESOURCE_LIMITS -> "Resource limit changes can trigger a rollout and alter scheduling or throttling behavior.";
        };
    }

    private int riskScore(RemediationAction action, NamespaceRiskProfile namespaceRisk) {
        int base = switch (action) {
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD -> 20;
            case ROLLOUT_RESTART_DEPLOYMENT -> 35;
            case SCALE_DEPLOYMENT -> 45;
            case ROLLBACK_DEPLOYMENT -> 55;
            case PATCH_RESOURCE_LIMITS -> 70;
        };
        return Math.min(100, base + namespaceRisk.riskScoreModifier());
    }

    private List<String> deterministicChecks(RemediationAction action) {
        return switch (action) {
            case ROLLOUT_RESTART_DEPLOYMENT -> List.of(
                    "Confirm deployment rollout is degraded or stale.",
                    "Review warning events and recent pod restarts.",
                    "Confirm there is enough replica capacity for a rolling restart."
            );
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD -> List.of(
                    "Confirm the pod is controlled by a workload controller.",
                    "Inspect current and previous container logs.",
                    "Verify the failure is isolated to this pod instance."
            );
            case ROLLBACK_DEPLOYMENT -> List.of(
                    "Check rollout history and identify the last known good revision.",
                    "Confirm current rollout is failing or serving bad behavior.",
                    "Verify rollback will not reintroduce a known incident."
            );
            case SCALE_DEPLOYMENT -> List.of(
                    "Confirm desired replica count and available node capacity.",
                    "Check HPA or autoscaling ownership before manual scaling.",
                    "Review pending pods and scheduling constraints."
            );
            case PATCH_RESOURCE_LIMITS -> List.of(
                    "Confirm OOMKilled, throttling, or scheduling evidence.",
                    "Patch one deployment container at a time.",
                    "Monitor rollout, node pressure, and pod readiness after the change."
            );
        };
    }

    private List<String> guardrails(RemediationAction action) {
        List<String> common = List.of(
                "Human approval required through explicit confirmation.",
                "System namespaces are blocked.",
                "Only allowlisted remediation actions are supported.",
                "Autonomous AI execution is disabled."
        );
        if (action == RemediationAction.RESTART_MANAGED_POD || action == RemediationAction.DELETE_MANAGED_POD) {
            List<String> podGuardrails = new ArrayList<>(common);
            podGuardrails.add("Pod deletion is limited to managed pods.");
            return podGuardrails;
        }
        return common;
    }

    private String resourceLimitsPatch(RemediationRequest request) {
        List<String> limits = new ArrayList<>();
        if (!trim(request.cpuLimit()).isBlank()) {
            limits.add("\"cpu\": \"" + jsonEscape(request.cpuLimit()) + "\"");
        }
        if (!trim(request.memoryLimit()).isBlank()) {
            limits.add("\"memory\": \"" + jsonEscape(request.memoryLimit()) + "\"");
        }
        return String.join(", ", limits);
    }

    private String dryRunCommand(RemediationRequest request) {
        String namespace = trim(request.namespace());
        String targetName = trim(request.targetName());
        return switch (request.action()) {
            case ROLLOUT_RESTART_DEPLOYMENT, SCALE_DEPLOYMENT, PATCH_RESOURCE_LIMITS ->
                    "kubectl patch deployment/" + targetName + " -n " + namespace
                            + " --type=strategic --patch '" + dryRunPatch(request).replace("\n", " ") + "' --dry-run=server";
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD ->
                    "kubectl delete pod/" + targetName + " -n " + namespace + " --dry-run=server";
            case ROLLBACK_DEPLOYMENT ->
                    "kubectl rollout undo deployment/" + targetName + " -n " + namespace + " --dry-run=server";
        };
    }

    private String dryRunPatch(RemediationRequest request) {
        return switch (request.action()) {
            case ROLLOUT_RESTART_DEPLOYMENT -> """
                    {"spec":{"template":{"metadata":{"annotations":{"kubectl.kubernetes.io/restartedAt":"<server-time>","aegis.io/remediation-reason":"%s"}}}}}
                    """.formatted(jsonEscape(request.reason())).trim();
            case SCALE_DEPLOYMENT -> """
                    {"spec":{"replicas":%d}}
                    """.formatted(request.replicas() == null ? 1 : request.replicas()).trim();
            case PATCH_RESOURCE_LIMITS -> """
                    {"spec":{"template":{"spec":{"containers":[{"name":"%s","resources":{"limits":{%s}}}]}}}}
                    """.formatted(jsonEscape(request.containerName()), resourceLimitsPatch(request)).trim();
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD -> "delete pod dry-run; no patch body";
            case ROLLBACK_DEPLOYMENT -> "server-side rollout undo dry-run; patch is generated by kubectl from rollout history";
        };
    }

    private String dryRunDiff(RemediationRequest request, String dryRunPatch, int riskScore) {
        return switch (request.action()) {
            case ROLLOUT_RESTART_DEPLOYMENT ->
                    "+ spec.template.metadata.annotations[kubectl.kubernetes.io/restartedAt]\n"
                            + "+ spec.template.metadata.annotations[aegis.io/remediation-reason]\n"
                            + "riskScore=" + riskScore;
            case SCALE_DEPLOYMENT ->
                    "~ spec.replicas -> " + (request.replicas() == null ? 1 : request.replicas()) + "\n"
                            + "riskScore=" + riskScore;
            case PATCH_RESOURCE_LIMITS ->
                    "~ spec.template.spec.containers[name=" + trim(request.containerName()) + "].resources.limits\n"
                            + dryRunPatch + "\n"
                            + "riskScore=" + riskScore;
            case RESTART_MANAGED_POD, DELETE_MANAGED_POD ->
                    "- pod/" + trim(request.targetName()) + " would be deleted by server dry-run; controller must recreate it.\n"
                            + "riskScore=" + riskScore;
            case ROLLBACK_DEPLOYMENT ->
                    "~ deployment/" + trim(request.targetName()) + " would move to previous rollout revision.\n"
                            + "riskScore=" + riskScore;
        };
    }

    private String resourceFlagPreview(RemediationRequest request) {
        List<String> flags = new ArrayList<>();
        if (!trim(request.cpuLimit()).isBlank()) {
            flags.add("cpu=" + trim(request.cpuLimit()));
        }
        if (!trim(request.memoryLimit()).isBlank()) {
            flags.add("memory=" + trim(request.memoryLimit()));
        }
        return flags.isEmpty() ? "" : " --limits=" + String.join(",", flags);
    }

    private String jsonEscape(String value) {
        return trim(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
