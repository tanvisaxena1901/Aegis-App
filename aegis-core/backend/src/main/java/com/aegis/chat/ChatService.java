package com.aegis.chat;

import com.aegis.ai.AiChatMessage;
import com.aegis.ai.AiChatRequest;
import com.aegis.ai.AiChatResponse;
import com.aegis.ai.AiReasoningClient;
import com.aegis.incident.IncidentInvestigationRequest;
import com.aegis.incident.IncidentInvestigationResponse;
import com.aegis.incident.IncidentService;
import com.aegis.incident.IncidentSeverity;
import com.aegis.kubernetes.EnvironmentHealth;
import com.aegis.kubernetes.EnvironmentHealthResponse;
import com.aegis.kubernetes.EnvironmentSeverity;
import com.aegis.kubernetes.KubernetesDeploymentDetail;
import com.aegis.kubernetes.KubernetesDeploymentSummary;
import com.aegis.kubernetes.KubernetesEventSummary;
import com.aegis.kubernetes.KubernetesOperationsService;
import com.aegis.kubernetes.KubernetesPodLogs;
import com.aegis.kubernetes.KubernetesPodSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String DEFAULT_NAMESPACE = "aegis";
    private static final int MAX_CHAT_HISTORY_ITEMS = 4;
    private static final int MAX_CHAT_HISTORY_CHARS = 900;
    private static final int MAX_CONTEXTUAL_HISTORY_CHARS = 500;

    private final AiReasoningClient aiReasoningClient;
    private final KubernetesOperationsService operationsService;
    private final IncidentService incidentService;

    public Mono<ChatResponse> chat(ChatRequest request) {
        String namespace = blankOrDefault(request.namespace(), DEFAULT_NAMESPACE);
        String question = contextualQuestion(request);
        boolean operationsQuestion = isOperationsQuestion(question);
        boolean useSelectedPod = useSelectedPodContext(request, question);
        boolean useSelectedDeployment = useSelectedDeploymentContext(request, question);

        if (isKubernetesConceptQuestion(question)) {
            return generalChat(request);
        }
        if (!operationsQuestion) {
            return generalChat(request);
        }

        Mono<EnvironmentHealthResponse> environments = operationsService.environmentHealth()
                .onErrorReturn(new EnvironmentHealthResponse(List.of(), Instant.now()));
        Mono<List<KubernetesPodSummary>> pods = operationsService.pods(namespace).onErrorReturn(List.of());
        Mono<List<KubernetesDeploymentSummary>> deployments = operationsService.deployments(namespace).onErrorReturn(List.of());
        Mono<List<KubernetesEventSummary>> events = operationsService.events(namespace).onErrorReturn(List.of());
        Mono<Optional<KubernetesDeploymentDetail>> deploymentDetail = optionalDeploymentDetail(
                namespace,
                useSelectedDeployment ? request.deploymentName() : null
        );
        Mono<Optional<KubernetesPodLogs>> podLogs = optionalPodLogs(
                namespace,
                useSelectedPod ? request.podName() : null,
                request.includeLogs()
        );

        return Mono.zip(environments, pods, deployments, events, deploymentDetail, podLogs)
                .flatMap(tuple -> {
                    EnvironmentHealthResponse environmentHealth = tuple.getT1();
                    List<KubernetesPodSummary> podSummaries = tuple.getT2();
                    List<KubernetesDeploymentSummary> deploymentSummaries = tuple.getT3();
                    List<KubernetesEventSummary> eventSummaries = tuple.getT4();
                    Optional<KubernetesDeploymentDetail> detail = tuple.getT5();
                    Optional<KubernetesPodLogs> logs = tuple.getT6();

                    List<ChatEvidence> evidence = operationsQuestion
                            ? collectEvidence(
                                    environmentHealth,
                                    namespace,
                                    podSummaries,
                                    deploymentSummaries,
                                    eventSummaries,
                                    detail,
                                    logs,
                                    question
                            )
                            : List.of();
                    List<String> metricContext = operationsQuestion
                            ? metrics(namespace, podSummaries, deploymentSummaries, eventSummaries, environmentHealth)
                            : List.of();

                    Optional<ChatResponse> fastResponse = fastOperationalResponse(
                            operationsQuestion,
                            question,
                            evidence,
                            metricContext,
                            environmentHealth,
                            podSummaries
                    );
                    if (fastResponse.isPresent()) {
                        return Mono.just(fastResponse.get());
                    }

                    IncidentInvestigationRequest investigationRequest = new IncidentInvestigationRequest(
                            namespace,
                            resourceKind(request, useSelectedPod, useSelectedDeployment),
                            resourceName(request, namespace, useSelectedPod, useSelectedDeployment),
                            question,
                            evidence.stream()
                                    .filter(item -> !"log".equals(item.sourceType()))
                                    .map(ChatEvidence::asPromptLine)
                                    .limit(12)
                                    .toList(),
                            logs.map(value -> value.lines().stream().skip(Math.max(0, value.lines().size() - 12)).toList())
                                    .orElse(List.of("No pod logs were loaded for this question.")),
                            metricContext
                    );

                    AiChatRequest aiChatRequest = new AiChatRequest(
                            question,
                            chatHistory(request),
                            modelEvidence(namespace, question, evidence),
                            metricContext
                    );

                    return aiReasoningClient.chat(aiChatRequest)
                            .flatMap(aiResponse -> {
                                if (aiResponse.ollamaAvailable()) {
                                    IncidentSeverity severity = severityFromEvidence(evidence);
                                    return Mono.just(new ChatResponse(
                                            aiResponse.answer(),
                                            severity,
                                            evidence.stream().limit(18).toList(),
                                            modelRecommendedActions(question, severity),
                                            severity == IncidentSeverity.HIGH || severity == IncidentSeverity.CRITICAL,
                                            aiResponse.generatedAt() == null ? Instant.now() : aiResponse.generatedAt()
                                    ));
                                }
                                return structuredRcaFallback(investigationRequest, evidence, question, metricContext);
                            })
                            .onErrorResume(error -> structuredRcaFallback(investigationRequest, evidence, question, metricContext));
                });
    }

    private Mono<ChatResponse> generalChat(ChatRequest request) {
        AiChatRequest aiChatRequest = new AiChatRequest(
                request.message(),
                chatHistory(request),
                List.of(),
                List.of()
        );
        return aiReasoningClient.chat(aiChatRequest)
                .onErrorReturn(new AiChatResponse(
                        localGeneralFallback(request.message(), "AI service request failed."),
                        "unavailable",
                        false,
                        Instant.now()
                ))
                .map(aiResponse -> new ChatResponse(
                        aiResponse.ollamaAvailable()
                                ? aiResponse.answer()
                                : localGeneralFallback(request.message(), aiResponse.answer()),
                        IncidentSeverity.LOW,
                        List.of(),
                        List.of(),
                        false,
                        aiResponse.generatedAt() == null ? Instant.now() : aiResponse.generatedAt()
                ));
    }

    private String localGeneralFallback(String message, String providerDetail) {
        String normalized = normalize(message);
        String detail = providerDetail == null || providerDetail.isBlank()
                ? "The model provider is offline."
                : providerDetail;
        if (normalized.contains("hello") || normalized.equals("hi") || normalized.equals("hey")) {
            return "Hi. Aegis is online, but the model provider is currently unavailable. I can still answer Kubernetes dashboard questions using deterministic runbooks, events, pods, rollout state, and logs.";
        }
        if (normalized.contains("help") || normalized.contains("what can you do")) {
            return "I can inspect Kubernetes health, summarize failing pods, group noisy events, explain runbook steps, and prepare approval-gated remediation plans. Free-form model chat is degraded until Ollama or OpenAI is available.";
        }
        return "Aegis received your message, but model-backed chat is degraded. Ask about cluster health, failing pods, warning events, rollout state, logs, runbooks, or remediation approvals and I will answer from deterministic platform data. Provider status: "
                + detail;
    }

    private Mono<ChatResponse> structuredRcaFallback(
            IncidentInvestigationRequest investigationRequest,
            List<ChatEvidence> evidence,
            String question,
            List<String> metricContext
    ) {
        return incidentService.investigate(investigationRequest)
                .map(investigation -> new ChatResponse(
                        operationsAnswer(investigation, evidence, question, metricContext),
                        investigation.severity(),
                        evidence.stream().limit(18).toList(),
                        investigation.recommendedActions(),
                        investigation.humanApprovalRequired(),
                        investigation.generatedAt() == null ? Instant.now() : investigation.generatedAt()
                ));
    }

    private List<String> modelEvidence(String namespace, String question, List<ChatEvidence> evidence) {
        List<String> context = new ArrayList<>();
        context.add("selected_namespace=" + namespace);
        context.add("user_question=" + question);
        context.add("instruction=Answer like a normal LLM, but ground Kubernetes claims in the source signals below.");
        context.add("instruction=For Kubernetes incidents, include direct answer, evidence, likely RCA, deterministic runbook checks first, and AI-assisted remediation second.");
        context.add("instruction=If the user asks how to fix something, do not repeat generic advice; map the fix path to the provided pods/events/logs/deployments.");
        evidence.stream()
                .map(ChatEvidence::asPromptLine)
                .limit(32)
                .forEach(context::add);
        return context;
    }

    private List<String> modelRecommendedActions(String question, IncidentSeverity severity) {
        if (severity == IncidentSeverity.LOW) {
            return List.of();
        }
        if (asksForFix(question)) {
            return List.of(
                    "Confirm the exact pod or deployment from the cited evidence.",
                    "Check `describe`, previous logs, warning events, and rollout history before changing anything.",
                    "Use AI-assisted remediation only after human approval for a specific restart, rollback, scale, or resource limit patch."
            );
        }
        return List.of(
                "Review the cited pod, event, log, or deployment signals.",
                "Open the affected namespace and confirm the latest state before remediation."
        );
    }

    private Optional<ChatResponse> fastOperationalResponse(
            boolean operationsQuestion,
            String question,
            List<ChatEvidence> evidence,
            List<String> metricContext,
            EnvironmentHealthResponse environmentHealth,
            List<KubernetesPodSummary> pods
    ) {
        if (!operationsQuestion) {
            return Optional.empty();
        }
        String answer = null;
        IncidentSeverity severity = severityFromEvidence(evidence);
        List<String> actions = List.of();
        if (asksForUnavailableMetric(question, metricContext, "cpu")) {
            answer = unavailableMetricAnswer("CPU", "cpu_metrics=not_collected_yet", evidence);
        } else if (asksForUnavailableMetric(question, metricContext, "memory")) {
            answer = unavailableMetricAnswer("memory", "memory_metrics=not_collected_yet", evidence);
        } else if (asksForFix(question)) {
            answer = fixAnswer(
                    "The current signals point to restarts, probe failures, or container startup instability. "
                            + "For CrashLoopBackOff, confirm the exact pod with `describe` and previous logs before deleting or restarting it.",
                    evidence
            );
            actions = List.of(
                    "Confirm the exact pod/deployment from the cited evidence.",
                    "Check `kubectl describe pod`, previous logs, warning events, and rollout history.",
                    "Use AI-assisted remediation only after human approval for a specific restart, rollback, scale, or resource limit patch."
            );
        } else if (asksAboutEnvironment(question) && asksAboutFailingPods(question)) {
            answer = environmentAndPodsAnswer(environmentHealth);
            actions = List.of(
                    "Start with the highest-severity environments in the triage.",
                    "Use `kubectl describe pod` and previous logs for the listed pods before remediation."
            );
        } else if (asksAboutEnvironment(question) && !asksForFix(question)) {
            answer = environmentIssuesAnswer(environmentHealth);
            actions = List.of(
                    "Start with CRITICAL environments that have failing pods.",
                    "Review warning events and restart spikes before changing deployments."
            );
        }
        if (answer == null) {
            return Optional.empty();
        }
        return Optional.of(new ChatResponse(
                answer,
                severity,
                evidence.stream().limit(16).toList(),
                actions,
                severity == IncidentSeverity.HIGH || severity == IncidentSeverity.CRITICAL,
                Instant.now()
        ));
    }

    private Mono<Optional<KubernetesDeploymentDetail>> optionalDeploymentDetail(String namespace, String deploymentName) {
        if (isBlank(deploymentName)) {
            return Mono.just(Optional.empty());
        }
        return operationsService.describeDeployment(namespace, deploymentName)
                .map(Optional::of)
                .onErrorReturn(Optional.empty());
    }

    private Mono<Optional<KubernetesPodLogs>> optionalPodLogs(String namespace, String podName, Boolean includeLogs) {
        if (isBlank(podName) || !Boolean.TRUE.equals(includeLogs)) {
            return Mono.just(Optional.empty());
        }
        return operationsService.podLogs(namespace, podName, null, 120)
                .map(Optional::of)
                .onErrorReturn(Optional.empty());
    }

    private List<ChatEvidence> collectEvidence(
            EnvironmentHealthResponse environmentHealth,
            String namespace,
            List<KubernetesPodSummary> pods,
            List<KubernetesDeploymentSummary> deployments,
            List<KubernetesEventSummary> events,
            Optional<KubernetesDeploymentDetail> deploymentDetail,
            Optional<KubernetesPodLogs> logs,
            String question
    ) {
        List<ChatEvidence> evidence = new ArrayList<>();
        List<KubernetesPodSummary> matchingPods = matchingPods(pods, question);

        if (!matchingPods.isEmpty()) {
            long upMatches = matchingPods.stream().filter(this::isPodUp).count();
            evidence.add(new ChatEvidence(
                    "pod",
                    namespace + "/matching-pods",
                    upMatches + "/" + matchingPods.size() + " matching pods are Running and Ready."
            ));
            matchingPods.stream()
                    .limit(10)
                    .forEach(pod -> evidence.add(new ChatEvidence(
                            "pod",
                            pod.namespace() + "/" + pod.name(),
                            pod.phase() + ", ready " + pod.readyContainers() + "/" + pod.totalContainers()
                                    + ", restarts " + pod.restarts() + reasons(pod.statusReasons())
                    )));
        }

        if (matchingPods.isEmpty() || asksAboutEnvironment(question)) {
            environmentHealth.environments().stream()
                    .filter(environment -> environment.severity() != EnvironmentSeverity.HEALTHY)
                    .limit(6)
                    .forEach(environment -> evidence.add(new ChatEvidence(
                            "environment",
                            environment.environment(),
                            environment.severity() + ": " + environment.failingPods() + " failing pods, "
                                    + environment.warningEvents() + " warning events, " + environment.restarts() + " restarts"
                    )));
        }

        if (matchingPods.isEmpty()) {
            pods.stream()
                    .filter(KubernetesPodSummary::failing)
                    .limit(8)
                    .forEach(pod -> evidence.add(new ChatEvidence(
                            "pod",
                            pod.namespace() + "/" + pod.name(),
                            pod.phase() + ", ready " + pod.readyContainers() + "/" + pod.totalContainers()
                                    + ", restarts " + pod.restarts() + reasons(pod.statusReasons())
                    )));
        }

        deployments.stream()
                .filter(deployment -> deployment.readyReplicas() != deployment.replicas()
                        || deployment.availableReplicas() != deployment.replicas())
                .filter(deployment -> matchingPods.isEmpty())
                .limit(6)
                .forEach(deployment -> evidence.add(new ChatEvidence(
                        "deployment",
                        deployment.namespace() + "/" + deployment.name(),
                        "ready " + deployment.readyReplicas() + "/" + deployment.replicas()
                                + ", available " + deployment.availableReplicas() + "/" + deployment.replicas()
                )));

        events.stream()
                .filter(event -> "Warning".equalsIgnoreCase(event.type()))
                .filter(event -> matchingPods.isEmpty() || matchingPods.stream()
                        .anyMatch(pod -> event.involvedObject().contains(pod.name())))
                .limit(8)
                .forEach(event -> evidence.add(new ChatEvidence(
                        "event",
                        event.namespace() + "/" + event.involvedObject(),
                        event.reason() + ": " + event.message()
                )));

        deploymentDetail.ifPresent(detail -> {
            evidence.add(new ChatEvidence(
                    "deployment",
                    detail.summary().namespace() + "/" + detail.summary().name(),
                    detail.rollout().summary()
            ));
            detail.conditions().stream().limit(4).forEach(condition -> evidence.add(new ChatEvidence(
                    "deployment",
                    detail.summary().namespace() + "/" + detail.summary().name(),
                    condition
            )));
        });

        logs.ifPresent(podLogs -> podLogs.lines().stream()
                .skip(Math.max(0, podLogs.lines().size() - 6))
                .forEach(line -> evidence.add(new ChatEvidence(
                        "log",
                        podLogs.namespace() + "/" + podLogs.podName(),
                        line
                ))));

        if (evidence.isEmpty()) {
            evidence.add(new ChatEvidence("namespace", namespace, "No failing pods, warning events, or degraded deployments found."));
        }
        return evidence;
    }

    private List<String> metrics(
            String namespace,
            List<KubernetesPodSummary> pods,
            List<KubernetesDeploymentSummary> deployments,
            List<KubernetesEventSummary> events,
            EnvironmentHealthResponse environmentHealth
    ) {
        long runningPods = pods.stream().filter(pod -> "Running".equalsIgnoreCase(pod.phase())).count();
        int restarts = pods.stream().mapToInt(KubernetesPodSummary::restarts).sum();
        long readyDeployments = deployments.stream()
                .filter(deployment -> deployment.readyReplicas() == deployment.replicas()
                        && deployment.availableReplicas() == deployment.replicas())
                .count();
        long warningEvents = events.stream().filter(event -> "Warning".equalsIgnoreCase(event.type())).count();
        long unhealthyEnvironments = environmentHealth.environments().stream()
                .filter(environment -> environment.severity() != EnvironmentSeverity.HEALTHY)
                .count();

        return List.of(
                "namespace=" + namespace,
                "namespace_pods=" + pods.size(),
                "running_pods=" + runningPods,
                "failing_pods=" + pods.stream().filter(KubernetesPodSummary::failing).count(),
                "restarts=" + restarts,
                "deployments=" + deployments.size(),
                "ready_deployments=" + readyDeployments,
                "warning_events=" + warningEvents,
                "unhealthy_environments=" + unhealthyEnvironments,
                "cpu_metrics=not_collected_yet",
                "memory_metrics=not_collected_yet"
        );
    }

    private List<AiChatMessage> chatHistory(ChatRequest request) {
        if (request.history() == null) {
            return List.of();
        }
        return request.history().stream()
                .skip(Math.max(0, request.history().size() - MAX_CHAT_HISTORY_ITEMS))
                .filter(item -> !isBlank(item.content()))
                .map(item -> new AiChatMessage(blankOrDefault(item.role(), "user"), truncate(item.content(), MAX_CHAT_HISTORY_CHARS)))
                .toList();
    }

    private List<String> llmEvidence(
            List<ChatEvidence> evidence,
            IncidentInvestigationResponse investigation,
            boolean operationsQuestion
    ) {
        if (!operationsQuestion) {
            return List.of();
        }
        List<String> context = new ArrayList<>();
        context.add("Structured RCA severity: " + investigation.severity());
        context.add("Structured RCA summary: " + investigation.summary());
        context.add("Structured RCA probable cause: " + investigation.probableCause());
        investigation.recommendedActions().stream()
                .limit(6)
                .forEach(action -> context.add("Structured RCA action: " + action));
        evidence.stream()
                .map(ChatEvidence::asPromptLine)
                .limit(24)
                .forEach(context::add);
        return context;
    }

    private String finalAnswer(
            AiChatResponse aiResponse,
            IncidentInvestigationResponse investigation,
            boolean operationsQuestion,
            List<ChatEvidence> evidence,
            String question,
            List<String> metricContext
    ) {
        if (operationsQuestion && asksForUnavailableMetric(question, metricContext, "cpu")) {
            return unavailableMetricAnswer("CPU", "cpu_metrics=not_collected_yet", evidence);
        }
        if (operationsQuestion && asksForUnavailableMetric(question, metricContext, "memory")) {
            return unavailableMetricAnswer("memory", "memory_metrics=not_collected_yet", evidence);
        }
        if (operationsQuestion && asksForPodReadiness(question, evidence)) {
            return podReadinessAnswer(evidence);
        }
        if (aiResponse.ollamaAvailable()) {
            return aiResponse.answer();
        }
        if (!operationsQuestion) {
            return aiResponse.answer();
        }
        StringBuilder answer = new StringBuilder(aiResponse.answer());
        answer.append("\n\nStructured RCA fallback:");
        answer.append("\nSeverity: ").append(investigation.severity());
        answer.append("\nProbable cause: ").append(investigation.probableCause());
        if (!investigation.recommendedActions().isEmpty()) {
            answer.append("\nRecommended actions:\n- ")
                    .append(String.join("\n- ", investigation.recommendedActions()));
        }
        List<String> evidenceLines = evidence.stream()
                .limit(6)
                .map(ChatEvidence::asPromptLine)
                .toList();
        if (!evidenceLines.isEmpty()) {
            answer.append("\nEvidence:\n- ").append(String.join("\n- ", evidenceLines));
        }
        return answer.toString();
    }

    private String operationsAnswer(
            IncidentInvestigationResponse investigation,
            List<ChatEvidence> evidence,
            String question,
            List<String> metricContext
    ) {
        if (asksForUnavailableMetric(question, metricContext, "cpu")) {
            return unavailableMetricAnswer("CPU", "cpu_metrics=not_collected_yet", evidence);
        }
        if (asksForUnavailableMetric(question, metricContext, "memory")) {
            return unavailableMetricAnswer("memory", "memory_metrics=not_collected_yet", evidence);
        }
        if (asksForPodReadiness(question, evidence)) {
            return podReadinessAnswer(evidence);
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Here is the RCA from the current Kubernetes evidence.");
        answer.append("\n\nSummary\n- ").append(investigation.summary());
        answer.append("\n\nProbable cause\n- ").append(investigation.probableCause());

        List<String> podSignals = evidenceLines(evidence, "pod", 5);
        List<String> eventSignals = evidenceLines(evidence, "event", 5);
        List<String> logSignals = evidenceLines(evidence, "log", 6);
        List<String> deploymentSignals = evidenceLines(evidence, "deployment", 4);

        if (!podSignals.isEmpty()) {
            answer.append("\n\nPod signals\n- ").append(String.join("\n- ", podSignals));
        }
        if (!eventSignals.isEmpty()) {
            answer.append("\n\nWarning events\n- ").append(String.join("\n- ", eventSignals));
        }
        if (!logSignals.isEmpty()) {
            answer.append("\n\nRecent log signals\n- ").append(String.join("\n- ", logSignals));
        }
        if (!deploymentSignals.isEmpty()) {
            answer.append("\n\nDeployment state\n- ").append(String.join("\n- ", deploymentSignals));
        }
        if (!investigation.recommendedActions().isEmpty()) {
            answer.append("\n\nRecommended next steps\n- ")
                    .append(String.join("\n- ", investigation.recommendedActions()));
        }
        answer.append("\n\nI would avoid deleting pods until the cause is confirmed. Prefer describe, logs, rollout status, then a controlled restart or rollback if the evidence supports it.");
        return answer.toString();
    }

    private String environmentAndPodsAnswer(EnvironmentHealthResponse environmentHealth) {
        List<EnvironmentHealth> impacted = environmentHealth.environments().stream()
                .filter(environment -> environment.severity() != EnvironmentSeverity.HEALTHY)
                .sorted(Comparator
                        .comparing((EnvironmentHealth environment) -> environment.severity().ordinal()).reversed()
                        .thenComparing(EnvironmentHealth::failingPods, Comparator.reverseOrder())
                        .thenComparing(EnvironmentHealth::warningEvents, Comparator.reverseOrder()))
                .limit(6)
                .toList();
        if (impacted.isEmpty()) {
            return "No unhealthy environments or failing pods are visible from the current cluster scan.";
        }
        StringBuilder answer = new StringBuilder("Here is the current cluster triage.\n\n");
        int totalFailingPods = impacted.stream().mapToInt(EnvironmentHealth::failingPods).sum();
        answer.append("Summary\n");
        answer.append("- ").append(impacted.size()).append(" environments need attention; ")
                .append(totalFailingPods).append(" failing pod signals are visible across the top impacted environments.\n\n");
        answer.append("Highest priority environments\n");
        impacted.forEach(environment -> {
            answer.append("\n- ").append(environment.environment())
                    .append(" - ").append(environment.severity())
                    .append(": ").append(environment.failingPods()).append(" failing pods, ")
                    .append(environment.warningEvents()).append(" warnings, ")
                    .append(environment.restarts()).append(" restarts.");
            environment.failingPodSignals().stream().limit(2).forEach(pod -> answer.append("\n  - ")
                    .append(pod.namespace()).append("/").append(pod.name())
                    .append(": ").append(pod.phase())
                    .append(", ready ").append(pod.readyContainers()).append("/").append(pod.totalContainers())
                    .append(", ").append(pod.restarts()).append(" restarts")
                    .append(pod.reasons().isEmpty() ? "." : " (" + String.join("; ", pod.reasons().stream().limit(2).toList()) + ")."));
        });
        answer.append("\n\nRecommended next checks\n");
        answer.append("- Start with the CRITICAL environments above.\n");
        answer.append("- For each listed pod, run `kubectl describe pod` and check previous logs.\n");
        answer.append("- Prioritize NotReady, CrashLoopBackOff, ImagePullBackOff, OOMKilled, failed probes, and high restart counts.");
        return answer.toString();
    }

    private boolean asksForPodReadiness(String question, List<ChatEvidence> evidence) {
        String normalized = normalize(question);
        return evidence.stream().anyMatch(item -> "pod".equals(item.sourceType()) && item.sourceName().endsWith("/matching-pods"))
                && (normalized.contains("up")
                        || normalized.contains("ready")
                        || normalized.contains("running")
                        || normalized.contains("healthy"));
    }

    private String podReadinessAnswer(List<ChatEvidence> evidence) {
        ChatEvidence aggregate = evidence.stream()
                .filter(item -> "pod".equals(item.sourceType()) && item.sourceName().endsWith("/matching-pods"))
                .findFirst()
                .orElseThrow();
        boolean allReady = allMatchingPodsReady(aggregate.message());
        StringBuilder answer = new StringBuilder();
        answer.append(allReady ? "Yes, the matching pods are Running and Ready." : "No, not all matching pods are Running and Ready.");
        answer.append(" ").append(aggregate.message());
        answer.append("\n\nSource signals:");
        evidence.stream()
                .filter(item -> "pod".equals(item.sourceType()) && !item.sourceName().endsWith("/matching-pods"))
                .limit(8)
                .map(ChatEvidence::asPromptLine)
                .forEach(line -> answer.append("\n- ").append(line));
        evidence.stream()
                .filter(item -> "event".equals(item.sourceType()))
                .limit(4)
                .map(ChatEvidence::asPromptLine)
                .forEach(line -> answer.append("\n- ").append(line));
        answer.append("\n\nRCA context: ");
        if (allReady) {
            answer.append("the matching pod set is currently up, but the warning events/restarts show recent instability that should be reviewed.");
        } else {
            answer.append("at least one matching pod is not ready; the current signals point first to readiness/liveness probe failures, restarts, or container readiness issues.");
        }
        answer.append(" Treat a pod as up only when it is Running and all containers are Ready.");
        return answer.toString();
    }

    private List<String> podReadinessActions(List<ChatEvidence> evidence) {
        boolean hasWarnings = evidence.stream().anyMatch(item -> "event".equals(item.sourceType()));
        if (!hasWarnings) {
            return List.of();
        }
        return List.of(
                "Review readiness/liveness probe failures for the matching pod.",
                "Check previous container logs because restarts indicate recent instability."
        );
    }

    private long countMatchingPodSignals(List<ChatEvidence> evidence) {
        return evidence.stream()
                .filter(item -> "pod".equals(item.sourceType()) && !item.sourceName().endsWith("/matching-pods"))
                .count();
    }

    private boolean allMatchingPodsReady(String message) {
        String[] prefix = message.split(" ", 2)[0].split("/", 2);
        if (prefix.length != 2) {
            return false;
        }
        try {
            return Integer.parseInt(prefix[0]) == Integer.parseInt(prefix[1]);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean asksForUnavailableMetric(String question, List<String> metricContext, String metricName) {
        String normalized = normalize(question);
        return normalized.contains(metricName)
                && (normalized.contains("usage")
                        || normalized.contains("highest")
                        || normalized.contains("top")
                        || normalized.contains("max")
                        || normalized.contains("most"))
                && metricContext.contains(metricName + "_metrics=not_collected_yet");
    }

    private String unavailableMetricAnswer(
            String metricLabel,
            String missingMetricSignal,
            List<ChatEvidence> evidence
    ) {
        StringBuilder answer = new StringBuilder();
        answer.append("I cannot determine the highest ").append(metricLabel)
                .append(" usage from the current snapshot because Aegis is not collecting ")
                .append(metricLabel).append(" samples yet.");
        answer.append("\n\nSource signals:\n- metric ").append(missingMetricSignal);
        evidence.stream()
                .limit(5)
                .map(ChatEvidence::asPromptLine)
                .forEach(line -> answer.append("\n- ").append(line));
        answer.append("\n\nRCA context: current cluster health can still be reviewed from pod readiness, restarts, warning events, and deployments, but usage ranking needs real metric samples.");
        answer.append("\n\nTo answer this accurately, wire Aegis to Kubernetes metrics-server or Prometheus and include per-pod ")
                .append(metricLabel).append(" samples in the chat snapshot.");
        return answer.toString();
    }

    private String failingPodsAnswer(List<KubernetesPodSummary> pods) {
        List<KubernetesPodSummary> failing = pods.stream()
                .filter(KubernetesPodSummary::failing)
                .limit(8)
                .toList();
        if (failing.isEmpty()) {
            return "No failing pods are visible in the selected namespace. Source signal: every loaded pod is Running and Ready.";
        }
        StringBuilder answer = new StringBuilder("Here is what I found in the selected namespace.\n\n");
        answer.append("Summary\n");
        answer.append("- ").append(failing.size()).append(" pod signal(s) need attention. Some pods may be Running/Ready but still show restart or previous-termination risk.\n\n");
        answer.append("Pods to inspect first\n");
        failing.forEach(pod -> answer.append("\n- pod ")
                .append(pod.namespace()).append("/").append(pod.name())
                .append(": ").append(pod.phase())
                .append(", ready ").append(pod.readyContainers()).append("/").append(pod.totalContainers())
                .append(", restarts ").append(pod.restarts())
                .append(shortReasons(pod.statusReasons())));
        answer.append("\n\nRecommended next checks\n");
        answer.append("- Describe the highest-restart pod and review recent events.\n");
        answer.append("- Check previous container logs for pods with restart history.\n");
        answer.append("- Treat CrashLoopBackOff, ImagePullBackOff, OOMKilled, and failed probes as priority RCA signals.");
        return answer.toString();
    }

    private String environmentIssuesAnswer(EnvironmentHealthResponse environmentHealth) {
        List<EnvironmentHealth> impacted = environmentHealth.environments().stream()
                .filter(environment -> environment.severity() != EnvironmentSeverity.HEALTHY)
                .sorted(Comparator
                        .comparing((EnvironmentHealth environment) -> environment.severity().ordinal()).reversed()
                        .thenComparing(EnvironmentHealth::failingPods, Comparator.reverseOrder())
                        .thenComparing(EnvironmentHealth::warningEvents, Comparator.reverseOrder()))
                .limit(6)
                .toList();
        if (impacted.isEmpty()) {
            return "No unhealthy environments are visible from the current cluster scan.";
        }
        StringBuilder answer = new StringBuilder("Here are the environments that need attention.\n\n");
        answer.append("Priority order\n");
        impacted.forEach(environment -> answer.append("\n- environment ")
                .append(environment.environment())
                .append(": ").append(environment.severity())
                .append(", failing pods ").append(environment.failingPods())
                .append(", warning events ").append(environment.warningEvents())
                .append(", restarts ").append(environment.restarts()));
        answer.append("\n\nRecommended next checks\n");
        answer.append("- Start with CRITICAL environments that have failing pods.\n");
        answer.append("- Review warning events and restart spikes before changing deployments.");
        return answer.toString();
    }

    private IncidentSeverity severityFromEvidence(List<ChatEvidence> evidence) {
        String text = normalize(evidence.stream().map(ChatEvidence::message).reduce("", (left, right) -> left + " " + right));
        if (text.contains("critical") || text.contains("crashloopbackoff") || text.contains("imagepullbackoff")) {
            return IncidentSeverity.HIGH;
        }
        if (text.contains("failing") || text.contains("unhealthy") || text.contains("notready") || text.contains("warning")) {
            return IncidentSeverity.MEDIUM;
        }
        return IncidentSeverity.LOW;
    }

    private boolean asksAboutFailingPods(String question) {
        String normalized = normalize(question);
        return normalized.contains("failing pod")
                || normalized.contains("failing pods")
                || normalized.contains("pods are failing")
                || normalized.contains("pods failing")
                || normalized.contains("failed pod")
                || normalized.contains("unhealthy pod");
    }

    private boolean isGreeting(String question) {
        String normalized = normalize(question);
        return normalized.equals("hi")
                || normalized.equals("hello")
                || normalized.equals("hey")
                || normalized.equals("hi there")
                || normalized.equals("hello there");
    }

    private IncidentInvestigationResponse noRcaResponse() {
        return new IncidentInvestigationResponse(
                "general-chat",
                IncidentSeverity.LOW,
                "No Kubernetes RCA was run for this general question.",
                "General chat request.",
                List.of(),
                List.of(),
                false,
                Instant.now()
        );
    }

    private String answer(
            String originalQuestion,
            String question,
            String summary,
            String probableCause,
            EnvironmentHealthResponse environmentHealth,
            List<ChatEvidence> evidence
    ) {
        Optional<ChatEvidence> matchingPods = evidence.stream()
                .filter(item -> "pod".equals(item.sourceType()) && item.sourceName().endsWith("/matching-pods"))
                .findFirst();
        if (asksForFix(originalQuestion) || asksForFix(question)) {
            return fixAnswer(probableCause, evidence);
        }
        if (matchingPods.isPresent()) {
            List<String> podLines = evidence.stream()
                    .filter(item -> "pod".equals(item.sourceType()) && !item.sourceName().endsWith("/matching-pods"))
                    .limit(5)
                    .map(item -> item.sourceName() + ": " + item.message())
                    .toList();
            List<String> eventLines = evidence.stream()
                    .filter(item -> "event".equals(item.sourceType()))
                    .limit(4)
                    .map(item -> item.sourceName() + ": " + item.message())
                    .toList();
            boolean allUp = matchingPods.get().message().startsWith(podLines.size() + "/" + podLines.size())
                    && !podLines.isEmpty();
            String verdict = allUp ? "Yes, the matching pods are up." : "No, not all matching pods are up.";
            return verdict
                    + " " + matchingPods.get().message()
                    + "\n\nEvidence:\n- " + String.join("\n- ", podLines)
                    + (eventLines.isEmpty() ? "" : "\n\nRelated warning events:\n- " + String.join("\n- ", eventLines))
                    + "\n\nRCA: " + probableCause + " For an 'up' check, Aegis treats a pod as up when it is Running and all containers are Ready. "
                    + "Restarts or probe warnings do not necessarily mean the pod is down right now, but they do indicate recent instability that should be reviewed.";
        }
        Optional<EnvironmentHealth> worstEnvironment = environmentHealth.environments().stream()
                .filter(environment -> environment.severity() != EnvironmentSeverity.HEALTHY)
                .max(Comparator
                        .comparing((EnvironmentHealth environment) -> environment.severity().ordinal())
                        .thenComparing(EnvironmentHealth::failingPods)
                        .thenComparing(EnvironmentHealth::warningEvents));
        String lead = "";
        if (matchingPods.isPresent()) {
            lead = matchingPods.get().message() + " ";
        }
        if (question.toLowerCase().contains("env") || question.toLowerCase().contains("environment")
                || question.toLowerCase().contains("issue")) {
            lead += worstEnvironment
                    .map(environment -> "Most impacted environment: " + environment.environment()
                            + " (" + environment.severity() + ", " + environment.failingPods()
                            + " failing pods, " + environment.warningEvents() + " warning events). ")
                    .orElse("No unhealthy environment is visible from the current cluster scan. ");
        }
        return lead + summary + "\n\n" + probableCause;
    }

    private String resourceKind(ChatRequest request, boolean useSelectedPod, boolean useSelectedDeployment) {
        if (useSelectedPod) {
            return "Pod";
        }
        if (useSelectedDeployment) {
            return "Deployment";
        }
        return "Namespace";
    }

    private String resourceName(ChatRequest request, String namespace, boolean useSelectedPod, boolean useSelectedDeployment) {
        if (useSelectedPod) {
            return request.podName();
        }
        if (useSelectedDeployment) {
            return request.deploymentName();
        }
        return namespace;
    }

    private List<KubernetesPodSummary> matchingPods(List<KubernetesPodSummary> pods, String question) {
        String normalized = normalize(question);
        if (!normalized.contains("pod")) {
            return List.of();
        }
        List<String> tokens = normalized.lines()
                .flatMap(line -> Arrays.stream(line.split(" ")))
                .filter(token -> token.length() >= 2)
                .filter(token -> !List.of(
                        "are", "all", "pod", "pods", "up", "the", "and", "what", "why", "which",
                        "is", "for", "with", "this", "that", "show", "tell", "me"
                ).contains(token))
                .toList();
        if (tokens.isEmpty()) {
            return List.of();
        }
        return pods.stream()
                .filter(pod -> tokens.stream().anyMatch(token -> matchesPodToken(pod, token)))
                .toList();
    }

    private String podText(KubernetesPodSummary pod) {
        return normalize(pod.name() + " " + String.join(" ", pod.containers()) + " " + String.join(" ", pod.statusReasons()));
    }

    private boolean matchesPodToken(KubernetesPodSummary pod, String token) {
        if (token.length() <= 2) {
            return podTokens(pod).contains(token);
        }
        return podText(pod).contains(token);
    }

    private List<String> podTokens(KubernetesPodSummary pod) {
        return Arrays.stream(normalize(pod.name() + " " + String.join(" ", pod.containers()))
                        .replace("-", " ")
                        .split(" "))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private boolean isPodUp(KubernetesPodSummary pod) {
        return "Running".equalsIgnoreCase(pod.phase())
                && pod.totalContainers() > 0
                && pod.readyContainers() == pod.totalContainers();
    }

    private boolean asksAboutEnvironment(String question) {
        String normalized = normalize(question);
        return normalized.contains("env")
                || normalized.contains("environment")
                || normalized.contains("environments")
                || normalized.contains("issue")
                || normalized.contains("issues");
    }

    private boolean isOperationsQuestion(String question) {
        String normalized = normalize(question);
        boolean explicitKubernetesSignal = normalized.contains("kubernetes")
                || normalized.contains("k8s")
                || normalized.contains("cluster")
                || normalized.contains("namespace")
                || normalized.contains("env")
                || normalized.contains("environment")
                || normalized.contains("pod")
                || normalized.contains("pods")
                || normalized.contains("deployment")
                || normalized.contains("deploy")
                || normalized.contains("container")
                || normalized.contains("restart")
                || normalized.contains("crash")
                || normalized.contains("probe")
                || normalized.contains("readiness")
                || normalized.contains("liveness")
                || normalized.contains("logs")
                || normalized.contains("rca")
                || normalized.contains("root cause");
        boolean operatorFollowUp = normalized.equals("check again")
                || normalized.equals("recheck")
                || normalized.equals("refresh")
                || normalized.equals("refresh it")
                || normalized.equals("try again")
                || normalized.equals("scan again")
                || normalized.equals("run it again")
                || normalized.equals("check once more")
                || normalized.equals("check now");
        boolean metricOrFailureQuestion = (normalized.contains("cpu")
                || normalized.contains("memory")
                || normalized.contains("usage")
                || normalized.contains("latency")
                || normalized.contains("error")
                || normalized.contains("failing")
                || normalized.contains("unhealthy")
                || normalized.contains("fix")
                || normalized.contains("remediate"))
                && (normalized.contains("highest")
                        || normalized.contains("current")
                        || normalized.contains("status")
                        || normalized.contains("this")
                        || normalized.contains("that")
                        || normalized.contains("it")
                        || normalized.contains("aegis")
                        || normalized.contains("issue")
                        || normalized.contains("issues")
                        || normalized.contains("fail"));
        return explicitKubernetesSignal || metricOrFailureQuestion || operatorFollowUp;
    }

    private boolean isKubernetesConceptQuestion(String question) {
        String normalized = normalize(question);
        boolean asksForExplanation = normalized.startsWith("what is ")
                || normalized.startsWith("what are ")
                || normalized.startsWith("explain ")
                || normalized.startsWith("define ")
                || normalized.startsWith("meaning of ")
                || normalized.contains(" what is ")
                || normalized.contains(" explain ");
        if (!asksForExplanation) {
            return false;
        }
        boolean asksForLiveState = normalized.contains("my ")
                || normalized.contains("current")
                || normalized.contains("status")
                || normalized.contains("failing")
                || normalized.contains("unhealthy")
                || normalized.contains("issue")
                || normalized.contains("issues")
                || normalized.contains("logs")
                || normalized.contains("events")
                || normalized.contains("which")
                || normalized.contains("where");
        return !asksForLiveState;
    }

    private String contextualQuestion(ChatRequest request) {
        String message = request.message();
        if (!isFollowUp(message) || request.history() == null || request.history().isEmpty()) {
            return message;
        }
        String recentContext = request.history().stream()
                .skip(Math.max(0, request.history().size() - 3))
                .map(item -> blankOrDefault(item.role(), "context") + ": "
                        + truncate(blankOrDefault(item.content(), ""), MAX_CONTEXTUAL_HISTORY_CHARS))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return message + "\n\nRecent chat context:\n" + recentContext;
    }

    private boolean useSelectedPodContext(ChatRequest request, String question) {
        if (isBlank(request.podName())) {
            return false;
        }
        String normalizedQuestion = normalize(question);
        if (Boolean.TRUE.equals(request.includeLogs()) && (
                normalizedQuestion.contains("log")
                        || normalizedQuestion.contains("rca")
                        || normalizedQuestion.contains("analy")
                        || normalizedQuestion.contains("fix")
                        || normalizedQuestion.contains("fail")
                        || normalizedQuestion.contains("crash")
                        || normalizedQuestion.contains("restart")
                        || normalizedQuestion.contains("probe")
                        || normalizedQuestion.contains("error"))) {
            return true;
        }
        return normalizedQuestion.contains(normalize(request.podName()))
                || normalizedQuestion.contains("selected pod")
                || normalizedQuestion.contains("this pod")
                || normalizedQuestion.contains("current pod")
                || normalizedQuestion.contains("its logs");
    }

    private boolean useSelectedDeploymentContext(ChatRequest request, String question) {
        if (isBlank(request.deploymentName())) {
            return false;
        }
        String normalizedQuestion = normalize(question);
        return normalizedQuestion.contains(normalize(request.deploymentName()))
                || normalizedQuestion.contains("selected deployment")
                || normalizedQuestion.contains("this deployment")
                || normalizedQuestion.contains("current deployment")
                || normalizedQuestion.contains("rollout");
    }

    private boolean isFollowUp(String question) {
        String normalized = normalize(question);
        return normalized.contains(" it")
                || normalized.equals("check again")
                || normalized.equals("recheck")
                || normalized.equals("refresh")
                || normalized.equals("refresh it")
                || normalized.equals("try again")
                || normalized.equals("scan again")
                || normalized.equals("run it again")
                || normalized.equals("check once more")
                || normalized.equals("check now")
                || normalized.equals("how can we fix it")
                || normalized.equals("how to fix it")
                || normalized.contains("fix it")
                || normalized.contains("resolve it")
                || normalized.contains("what should we do")
                || normalized.contains("what next");
    }

    private boolean asksForFix(String question) {
        String normalized = normalize(question);
        return normalized.contains("fix")
                || normalized.contains("resolve")
                || normalized.contains("remediate")
                || normalized.contains("what should we do")
                || normalized.contains("what next")
                || normalized.contains("next steps");
    }

    private boolean wantsDeepRca(String question) {
        String normalized = normalize(question);
        return normalized.contains("rca")
                || normalized.contains("root cause")
                || normalized.contains("analyze")
                || normalized.contains("analysis")
                || normalized.contains("log")
                || normalized.contains("trace")
                || normalized.contains("stack");
    }

    private String fixAnswer(String probableCause, List<ChatEvidence> evidence) {
        List<String> pods = evidenceLines(evidence, "pod", 4);
        List<String> events = evidenceLines(evidence, "event", 4);
        List<String> deployments = evidenceLines(evidence, "deployment", 3);
        StringBuilder answer = new StringBuilder();
        answer.append("Here is the fix path I would take based on the current Kubernetes evidence.");
        answer.append("\n\nRCA: ").append(probableCause);
        if (!pods.isEmpty()) {
            answer.append("\n\nPods to inspect first:\n- ").append(String.join("\n- ", pods));
        }
        if (!events.isEmpty()) {
            answer.append("\n\nWarning events:\n- ").append(String.join("\n- ", events));
        }
        if (!deployments.isEmpty()) {
            answer.append("\n\nDeployment state:\n- ").append(String.join("\n- ", deployments));
        }
        answer.append("\n\nRecommended fix sequence:");
        answer.append("\n1. Confirm the affected pod/deployment from the evidence above.");
        answer.append("\n2. Run `kubectl describe pod` on the affected pod and check the latest warning events.");
        answer.append("\n3. Check current and previous logs for the affected container.");
        answer.append("\n4. If probes are failing, verify `/health`, port wiring, startup time, and probe timeout/initial delay.");
        answer.append("\n5. If restarts/OOM are present, inspect memory limits and recent rollout changes before restarting or rolling back.");
        answer.append("\n6. Apply a restart, probe patch, resource patch, or rollback only after confirming the specific cause.");
        return answer.toString();
    }

    private List<String> evidenceLines(List<ChatEvidence> evidence, String sourceType, int limit) {
        return evidence.stream()
                .filter(item -> sourceType.equals(item.sourceType()))
                .limit(limit)
                .map(item -> item.sourceName() + ": " + item.message())
                .toList();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n[truncated]";
    }

    private String reasons(List<String> reasons) {
        return reasons.isEmpty() ? "" : ", reasons: " + String.join("; ", reasons);
    }

    private String shortReasons(List<String> reasons) {
        if (reasons.isEmpty()) {
            return "";
        }
        return ", reason: " + String.join("; ", reasons.stream().limit(2).toList());
    }

    private String blankOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
