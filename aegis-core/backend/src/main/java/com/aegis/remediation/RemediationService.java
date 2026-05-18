package com.aegis.remediation;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.PatchUtils;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.aegis.kubernetes.KubernetesClientProvider;

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

    public Mono<RemediationResponse> execute(RemediationRequest request) {
        return Mono.fromCallable(() -> executeBlocking(request)).subscribeOn(Schedulers.boundedElastic());
    }

    private RemediationResponse executeBlocking(RemediationRequest request) throws Exception {
        validateApproval(request);
        return switch (request.action()) {
            case ROLLOUT_RESTART_DEPLOYMENT -> restartDeployment(request);
            case DELETE_MANAGED_POD -> deleteManagedPod(request);
        };
    }

    private void validateApproval(RemediationRequest request) {
        String namespace = request.namespace().trim();
        String targetName = request.targetName().trim();
        String reason = request.reason().trim();
        if (BLOCKED_NAMESPACES.contains(namespace)) {
            throw new IllegalArgumentException("Remediation is blocked for system namespace " + namespace);
        }
        if (!request.approved() || !"APPROVE".equals(request.confirmation().trim())) {
            throw new IllegalArgumentException("Human approval is required. Type APPROVE before executing remediation.");
        }
        if (targetName.length() < 2) {
            throw new IllegalArgumentException("Target name is too short.");
        }
        if (reason.length() < 12) {
            throw new IllegalArgumentException("A specific remediation reason is required.");
        }
    }

    private RemediationResponse restartDeployment(RemediationRequest request) throws Exception {
        ApiClient client = clientProvider.defaultClient();
        AppsV1Api appsApi = new AppsV1Api(client);
        String timestamp = Instant.now().toString();
        String escapedReason = jsonEscape(request.reason().trim());
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
                """.formatted(timestamp, escapedReason);
        PatchUtils.patch(
                V1Deployment.class,
                () -> appsApi.patchNamespacedDeployment(
                                request.targetName().trim(),
                                request.namespace().trim(),
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
                "Requested rollout restart for deployment " + request.namespace().trim() + "/" + request.targetName().trim()
        );
    }

    private RemediationResponse deleteManagedPod(RemediationRequest request) throws Exception {
        CoreV1Api coreApi = coreApi();
        V1Pod pod = coreApi.readNamespacedPod(request.targetName().trim(), request.namespace().trim()).execute();
        V1ObjectMeta metadata = pod.getMetadata();
        List<V1OwnerReference> ownerReferences = metadata == null || metadata.getOwnerReferences() == null
                ? List.of()
                : metadata.getOwnerReferences();
        boolean managed = ownerReferences.stream()
                .map(V1OwnerReference::getKind)
                .anyMatch(MANAGED_POD_OWNERS::contains);
        if (!managed) {
            throw new IllegalArgumentException("Pod delete is blocked because the pod is not managed by a ReplicaSet, StatefulSet, DaemonSet, or Job.");
        }
        coreApi.deleteNamespacedPod(request.targetName().trim(), request.namespace().trim())
                .gracePeriodSeconds(30)
                .propagationPolicy("Background")
                .execute();
        return response(
                request,
                "APPROVED_AND_EXECUTED",
                "Requested delete for managed pod " + request.namespace().trim() + "/" + request.targetName().trim()
                        + ". Its controller should recreate it if desired replicas require it."
        );
    }

    private RemediationResponse response(RemediationRequest request, String status, String summary) {
        return new RemediationResponse(
                request.action(),
                request.namespace().trim(),
                request.targetName().trim(),
                status,
                summary,
                List.of(
                        "Human approval required through explicit confirmation.",
                        "System namespaces are blocked.",
                        "Only allowlisted remediation actions are supported.",
                        "Pod deletion is limited to managed pods."
                ),
                Instant.now()
        );
    }

    private CoreV1Api coreApi() throws Exception {
        ApiClient client = clientProvider.defaultClient();
        return new CoreV1Api(client);
    }

    private AppsV1Api appsApi() throws Exception {
        ApiClient client = clientProvider.defaultClient();
        return new AppsV1Api(client);
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
