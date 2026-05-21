package com.aegis.runbook;

import com.aegis.remediation.RemediationAction;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class RunbookService {

    public List<RunbookResponse> catalog() {
        return List.of(
                build(RunbookIncidentType.CRASH_LOOP_BACK_OFF, "aegis", "Pod", "<pod>", List.of()),
                build(RunbookIncidentType.IMAGE_PULL_BACK_OFF, "aegis", "Pod", "<pod>", List.of()),
                build(RunbookIncidentType.OOM_KILLED, "aegis", "Pod", "<pod>", List.of()),
                build(RunbookIncidentType.PENDING_PODS, "aegis", "Pod", "<pod>", List.of()),
                build(RunbookIncidentType.FAILED_ROLLOUT, "aegis", "Deployment", "<deployment>", List.of()),
                build(RunbookIncidentType.NODE_PRESSURE, "aegis", "Node", "<node>", List.of())
        );
    }

    public RunbookResponse evaluate(RunbookRequest request) {
        String namespace = blankOrDefault(request.namespace(), "aegis");
        String kind = blankOrDefault(request.resourceKind(), "Pod");
        String name = blankOrDefault(request.resourceName(), "selected-resource");
        RunbookIncidentType type = classify(request);
        return build(type, namespace, kind, name, request.signals() == null ? List.of() : request.signals());
    }

    private RunbookIncidentType classify(RunbookRequest request) {
        String text = ((request.symptom() == null ? "" : request.symptom()) + " "
                + String.join(" ", request.signals() == null ? List.of() : request.signals()))
                .toLowerCase(Locale.ROOT);
        if (text.contains("crashloopbackoff") || text.contains("back-off restarting")) {
            return RunbookIncidentType.CRASH_LOOP_BACK_OFF;
        }
        if (text.contains("imagepullbackoff") || text.contains("errimagepull")) {
            return RunbookIncidentType.IMAGE_PULL_BACK_OFF;
        }
        if (text.contains("oomkilled") || text.contains("out of memory")) {
            return RunbookIncidentType.OOM_KILLED;
        }
        if (text.contains("pending") || text.contains("failedscheduling") || text.contains("unschedulable")) {
            return RunbookIncidentType.PENDING_PODS;
        }
        if (text.contains("rollout") || text.contains("progressdeadlineexceeded")
                || text.contains("available 0") || text.contains("ready 0")) {
            return RunbookIncidentType.FAILED_ROLLOUT;
        }
        if (text.contains("nodepressure") || text.contains("diskpressure")
                || text.contains("memorypressure") || text.contains("pidpressure")) {
            return RunbookIncidentType.NODE_PRESSURE;
        }
        return RunbookIncidentType.GENERAL_TRIAGE;
    }

    private RunbookResponse build(
            RunbookIncidentType type,
            String namespace,
            String resourceKind,
            String resourceName,
            List<String> signals
    ) {
        return switch (type) {
            case CRASH_LOOP_BACK_OFF -> new RunbookResponse(
                    type,
                    "CrashLoopBackOff",
                    "Container repeatedly starts and exits. Run deterministic checks before asking AI for code or config hypotheses.",
                    List.of(
                            step(1, "Confirm container state", "kubectl describe pod " + resourceName + " -n " + namespace, "Waiting reason is CrashLoopBackOff and events show restart backoff."),
                            step(2, "Read previous logs", "kubectl logs " + resourceName + " -n " + namespace + " --previous", "Application error, missing config, or failed startup is visible."),
                            step(3, "Check probes and config", "kubectl get pod " + resourceName + " -n " + namespace + " -o yaml", "Probe, env, secret, or command changes explain the restart.")
                    ),
                    List.of(new RunbookRecommendation(RemediationAction.RESTART_MANAGED_POD, "Pod", resourceName, "Replace only after logs and describe indicate an instance-level failure.")),
                    aiPrompt(type, signals),
                    Instant.now()
            );
            case IMAGE_PULL_BACK_OFF -> new RunbookResponse(
                    type,
                    "ImagePullBackOff",
                    "The kubelet cannot pull the configured image. Registry, tag, secret, or network checks come before AI RCA.",
                    List.of(
                            step(1, "Inspect pull event", "kubectl describe pod " + resourceName + " -n " + namespace, "Event includes missing image, denied auth, or registry timeout."),
                            step(2, "Verify deployment image", "kubectl get pod " + resourceName + " -n " + namespace + " -o jsonpath='{.spec.containers[*].image}'", "Image reference and tag are the expected release."),
                            step(3, "Check pull secret", "kubectl get secret -n " + namespace, "Expected imagePullSecret exists in the namespace.")
                    ),
                    List.of(),
                    aiPrompt(type, signals),
                    Instant.now()
            );
            case OOM_KILLED -> new RunbookResponse(
                    type,
                    "OOMKilled",
                    "Container exceeded its memory limit or node memory pressure evicted work. Establish evidence before changing limits.",
                    List.of(
                            step(1, "Confirm last termination", "kubectl describe pod " + resourceName + " -n " + namespace, "Last state reason is OOMKilled with exit code 137."),
                            step(2, "Check current limits", "kubectl get pod " + resourceName + " -n " + namespace + " -o jsonpath='{.spec.containers[*].resources}'", "Memory limit is low relative to the workload."),
                            step(3, "Inspect node pressure", "kubectl describe node <node-name>", "Node conditions do not show broader memory pressure.")
                    ),
                    List.of(new RunbookRecommendation(RemediationAction.PATCH_RESOURCE_LIMITS, "Deployment", deploymentTarget(resourceKind, resourceName), "Patch limits only after confirming OOM evidence and owner deployment.")),
                    aiPrompt(type, signals),
                    Instant.now()
            );
            case PENDING_PODS -> new RunbookResponse(
                    type,
                    "Pending pods",
                    "Scheduler cannot place pods. Capacity, selectors, taints, and PVC binding are checked deterministically first.",
                    List.of(
                            step(1, "Read scheduling events", "kubectl describe pod " + resourceName + " -n " + namespace, "Events include FailedScheduling or PVC binding reason."),
                            step(2, "Check node capacity", "kubectl describe nodes", "CPU, memory, taints, or node selectors explain placement failure."),
                            step(3, "Check PVCs", "kubectl get pvc -n " + namespace, "Claims are Bound before pods can schedule.")
                    ),
                    List.of(new RunbookRecommendation(RemediationAction.SCALE_DEPLOYMENT, "Deployment", deploymentTarget(resourceKind, resourceName), "Scale only if capacity and ownership checks support it.")),
                    aiPrompt(type, signals),
                    Instant.now()
            );
            case FAILED_ROLLOUT -> new RunbookResponse(
                    type,
                    "Failed rollout",
                    "Deployment is not converging. Rollout status, ReplicaSets, and new pod events are checked before rollback.",
                    List.of(
                            step(1, "Check rollout status", "kubectl rollout status deployment/" + resourceName + " -n " + namespace, "Rollout is stuck or exceeded progress deadline."),
                            step(2, "Compare ReplicaSets", "kubectl get rs -n " + namespace + " -l app=" + resourceName, "New ReplicaSet has unavailable replicas or repeated failures."),
                            step(3, "Inspect new pod events", "kubectl describe deployment " + resourceName + " -n " + namespace, "Events identify image, scheduling, probe, or crash failure.")
                    ),
                    List.of(new RunbookRecommendation(RemediationAction.ROLLBACK_DEPLOYMENT, "Deployment", resourceName, "Rollback only after confirming the current revision is bad.")),
                    aiPrompt(type, signals),
                    Instant.now()
            );
            case NODE_PRESSURE -> new RunbookResponse(
                    type,
                    "Node pressure",
                    "Node resource pressure can cause evictions and scheduling failure. Confirm broad node state before workload remediation.",
                    List.of(
                            step(1, "List node conditions", "kubectl describe nodes", "MemoryPressure, DiskPressure, or PIDPressure is present."),
                            step(2, "Review recent node events", "kubectl get events --all-namespaces --field-selector involvedObject.kind=Node", "Eviction or pressure events align with pod failures."),
                            step(3, "Identify affected pods", "kubectl get pods --all-namespaces -o wide", "Failures are concentrated on pressured nodes.")
                    ),
                    List.of(),
                    aiPrompt(type, signals),
                    Instant.now()
            );
            case GENERAL_TRIAGE -> new RunbookResponse(
                    type,
                    "General Kubernetes triage",
                    "No specific incident signature was detected. Aegis starts with deterministic cluster facts, then escalates to AI.",
                    List.of(
                            step(1, "Inspect resource", "kubectl describe " + resourceKind.toLowerCase(Locale.ROOT) + " " + resourceName + " -n " + namespace, "Status and events identify the failure class."),
                            step(2, "Review warning events", "kubectl get events -n " + namespace + " --sort-by=.lastTimestamp", "Recent warnings correlate with the symptom."),
                            step(3, "Check owner rollout", "kubectl get deploy,rs,pod -n " + namespace, "Owner workload health explains the local symptom.")
                    ),
                    List.of(),
                    aiPrompt(type, signals),
                    Instant.now()
            );
        };
    }

    private RunbookStep step(int order, String title, String command, String expectedSignal) {
        return new RunbookStep(order, title, command, expectedSignal);
    }

    private String aiPrompt(RunbookIncidentType type, List<String> signals) {
        return "After deterministic " + type + " checks, ask AI to explain only the remaining unknowns using these signals: "
                + (signals == null || signals.isEmpty() ? "no explicit signals supplied" : String.join(" | ", signals));
    }

    private String deploymentTarget(String resourceKind, String resourceName) {
        return "Deployment".equalsIgnoreCase(resourceKind) ? resourceName : "<owning-deployment>";
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
