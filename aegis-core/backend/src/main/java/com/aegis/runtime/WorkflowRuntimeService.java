package com.aegis.runtime;

import com.aegis.ai.AiReasoningClient;
import com.aegis.ai.AiRuntimeTaskRequest;
import com.aegis.ai.AiRuntimeTaskResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class WorkflowRuntimeService {

    private static final int MAX_EVENT_BATCH = 25;
    private static final int MAX_WORKFLOW_STEPS_PER_RESUME = 8;

    private final RuntimeEventBusPort eventBus;
    private final RuntimeStateStorePort stateStore;
    private final OperationalMemoryGraphPort memoryGraphService;
    private final AgentRegistry agentRegistry;
    private final AiReasoningClient aiReasoningClient;

    public Mono<RuntimeDispatchResponse> createIncident(RuntimeIncidentRequest request) {
        return Mono.fromCallable(() -> createIncidentBlocking(request)).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<WorkflowExecution> resume(String workflowId) {
        return Mono.fromCallable(() -> resumeBlocking(workflowId)).subscribeOn(Schedulers.boundedElastic());
    }

    public RuntimeStatusResponse status() {
        return new RuntimeStatusResponse(
                eventBus.backendLabel(),
                stateStore.backendLabel(),
                memoryGraphService.backendLabel(),
                eventBus.queuedEvents(),
                eventBus.recent(20),
                stateStore.recent(12),
                memoryGraphService.snapshot(),
                Instant.now()
        );
    }

    @Scheduled(fixedDelayString = "${aegis.runtime.recovery-scan-ms:5000}", initialDelayString = "3000")
    public void recoverAndConsume() {
        for (RuntimeEvent event : eventBus.pending(MAX_EVENT_BATCH)) {
            if (event.workflowId() != null && !event.workflowId().isBlank()) {
                try {
                    resumeBlocking(event.workflowId());
                } catch (Exception ignored) {
                    // Recovery is best-effort; workflow retry state keeps the error visible.
                }
            }
            eventBus.acknowledge(event.streamId());
        }
        for (WorkflowExecution workflow : stateStore.resumable()) {
            try {
                resumeBlocking(workflow.workflowId());
            } catch (Exception ignored) {
                // Individual workflow errors are recorded as retry checkpoints.
            }
        }
    }

    private RuntimeDispatchResponse createIncidentBlocking(RuntimeIncidentRequest request) {
        String incidentId = "INC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String workflowId = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        String clusterId = blankOrDefault(request.clusterId(), "dev-cluster");
        List<String> signals = safeSignals(request.signals());
        WorkflowExecution workflow = new WorkflowExecution(
                workflowId,
                incidentId,
                request.service().trim(),
                request.namespace().trim(),
                clusterId,
                request.symptom().trim(),
                signals,
                WorkflowStepType.FETCH_LOGS.name(),
                WorkflowStatus.PENDING,
                initialSteps(workflowId),
                List.of(),
                List.of(),
                Instant.now(),
                Instant.now()
        );
        stateStore.save(workflow);
        memoryGraphService.ensureWorkflowNode(workflowId, request.service());
        memoryGraphService.ingestIncident(request, incidentId, workflowId);
        RuntimeEvent event = eventBus.publish(
                RuntimeEventType.INCIDENT_CREATED,
                incidentId,
                workflowId,
                request.service(),
                Map.of(
                        "namespace", request.namespace(),
                        "clusterId", clusterId,
                        "symptom", request.symptom()
                )
        );
        eventBus.publish(RuntimeEventType.WORKFLOW_STARTED, incidentId, workflowId, request.service(), Map.of("currentStep", WorkflowStepType.FETCH_LOGS.name()));
        resumeBlocking(workflowId);
        return new RuntimeDispatchResponse(incidentId, workflowId, event.streamId(), WorkflowStatus.RUNNING, signals, Instant.now());
    }

    private synchronized WorkflowExecution resumeBlocking(String workflowId) {
        WorkflowExecution workflow = stateStore.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow " + workflowId));
        int executed = 0;
        while (executed < MAX_WORKFLOW_STEPS_PER_RESUME) {
            WorkflowStepExecution step = nextExecutableStep(workflow);
            if (step == null) {
                WorkflowStatus finalStatus = allDone(workflow.steps()) ? WorkflowStatus.COMPLETED : workflow.status();
                if (finalStatus == WorkflowStatus.COMPLETED && workflow.status() != WorkflowStatus.COMPLETED) {
                    eventBus.publish(RuntimeEventType.WORKFLOW_COMPLETED, workflow.incidentId(), workflow.workflowId(), workflow.service(), Map.of("status", "COMPLETED"));
                    workflow = markWorkflowStatus(workflow, WorkflowStatus.COMPLETED);
                }
                return workflow;
            }
            workflow = executeStep(workflow, step);
            executed++;
            if (workflow.status() == WorkflowStatus.RETRYING || workflow.status() == WorkflowStatus.DEAD_LETTERED) {
                return workflow;
            }
        }
        return workflow;
    }

    private WorkflowExecution executeStep(WorkflowExecution workflow, WorkflowStepExecution step) {
        WorkflowStepExecution running = new WorkflowStepExecution(
                step.stepId(),
                step.stepType(),
                step.agentType(),
                WorkflowStepStatus.RUNNING,
                step.attempt() + 1,
                step.maxAttempts(),
                step.output(),
                step.evidence(),
                Instant.now(),
                null,
                null
        );
        List<AgentExecution> agents = new ArrayList<>(workflow.agentExecutions());
        List<RetryState> retries = new ArrayList<>(workflow.retries());
        WorkflowExecution updated = stateStore.updateStep(workflow, running, WorkflowStatus.RUNNING, agents, retries);
        eventBus.publish(RuntimeEventType.STEP_STARTED, workflow.incidentId(), workflow.workflowId(), workflow.service(), Map.of("step", step.stepType().name()));
        try {
            StepResult result = runStep(updated, running);
            WorkflowStepExecution done = new WorkflowStepExecution(
                    running.stepId(),
                    running.stepType(),
                    running.agentType(),
                    WorkflowStepStatus.DONE,
                    running.attempt(),
                    running.maxAttempts(),
                    result.output(),
                    result.evidence(),
                    running.startedAt(),
                    Instant.now(),
                    null
            );
            agents.add(result.agentExecution());
            memoryGraphService.ingestMemoryWrites(workflow.workflowId(), result.memoryWrites());
            eventBus.publish(RuntimeEventType.STEP_COMPLETED, workflow.incidentId(), workflow.workflowId(), workflow.service(), Map.of(
                    "step", step.stepType().name(),
                    "agent", step.agentType().name()
            ));
            return stateStore.updateStep(updated, done, WorkflowStatus.RUNNING, agents, retries);
        } catch (Exception exception) {
            return handleStepFailure(updated, running, agents, retries, exception);
        }
    }

    private StepResult runStep(WorkflowExecution workflow, WorkflowStepExecution step) {
        List<String> memory = memoryGraphService.retrieve(workflow.service(), workflow.namespace(), workflow.symptom());
        List<String> signals = new ArrayList<>();
        signals.add("service=" + workflow.service());
        signals.add("namespace=" + workflow.namespace());
        signals.add("clusterId=" + workflow.clusterId());
        signals.add("symptom=" + workflow.symptom());
        signals.addAll(workflow.signals());
        String output;
        List<String> evidence;
        List<String> memoryWrites = List.of();
        if (step.stepType() == WorkflowStepType.FETCH_LOGS) {
            evidence = workflow.signals().stream().filter(signal -> signal.toLowerCase().contains("log")).limit(6).toList();
            if (evidence.isEmpty()) {
                evidence = List.of("No live logs attached to runtime event; downstream agent should request pod logs if needed.");
            }
            output = "Fetched log context for " + workflow.service() + ".";
        } else if (step.stepType() == WorkflowStepType.FETCH_METRICS) {
            evidence = workflow.signals().stream().filter(signal -> signal.toLowerCase().matches(".*(latency|traffic|error|saturation|slo|cpu|memory).*")).limit(8).toList();
            if (evidence.isEmpty()) {
                evidence = List.of("No external metrics attached; using Kubernetes event and deployment signals.");
            }
            output = "Fetched metric context for " + workflow.service() + ".";
        } else if (step.stepType() == WorkflowStepType.FETCH_DEPLOYMENT) {
            evidence = workflow.signals().stream().filter(signal -> signal.toLowerCase().matches(".*(deployment|replicaset|image|rollout|replica).*")).limit(8).toList();
            if (evidence.isEmpty()) {
                evidence = List.of("Deployment detail will be inferred from runtime symptom and Kubernetes evidence.");
            }
            output = "Fetched deployment context for " + workflow.service() + ".";
        } else if (step.stepType() == WorkflowStepType.RETRIEVE_MEMORY) {
            evidence = memory.isEmpty() ? List.of("No prior memory nodes matched this incident.") : memory;
            output = memory.isEmpty()
                    ? "Operational memory has no matching prior incident."
                    : "Retrieved " + memory.size() + " related operational memory records.";
        } else if (step.stepType() == WorkflowStepType.PROPOSE_REMEDIATION) {
            evidence = List.of("Remediation remains approval-gated by Java policy.", "Candidate actions: restart managed pod, rollback deployment, scale deployment.");
            output = "Prepared approval-gated remediation candidates. Autonomous execution remains disabled.";
            memoryWrites = List.of("workflow " + workflow.workflowId() + " proposed approval-gated remediation for " + workflow.service());
        } else {
            AiRuntimeTaskResponse response = aiReasoningClient.runtimeTask(new AiRuntimeTaskRequest(
                    workflow.workflowId(),
                    workflow.incidentId(),
                    step.agentType().name(),
                    step.stepType().name(),
                    workflow.service(),
                    workflow.namespace(),
                    workflow.symptom(),
                    signals,
                    memory
            )).onErrorReturn(localAiFallback(workflow, step)).block();
            output = response == null ? "AI runtime returned no response." : response.output();
            evidence = response == null ? List.of() : response.evidence();
            memoryWrites = response == null ? List.of() : response.memoryWrites();
        }
        AgentExecution execution = new AgentExecution(
                "agent-" + UUID.randomUUID().toString().substring(0, 8),
                workflow.workflowId(),
                step.stepId(),
                step.agentType(),
                "SUCCEEDED",
                signals,
                output,
                step.startedAt(),
                Instant.now()
        );
        return new StepResult(output, evidence, memoryWrites, execution);
    }

    private WorkflowExecution handleStepFailure(
            WorkflowExecution workflow,
            WorkflowStepExecution step,
            List<AgentExecution> agents,
            List<RetryState> retries,
            Exception exception
    ) {
        boolean retryable = step.attempt() < step.maxAttempts();
        long backoffMillis = retryable ? (long) Math.pow(2, step.attempt()) * 1000L : 0L;
        Instant nextAttemptAt = retryable ? Instant.now().plusMillis(backoffMillis) : null;
        WorkflowStepExecution failed = new WorkflowStepExecution(
                step.stepId(),
                step.stepType(),
                step.agentType(),
                WorkflowStepStatus.FAILED,
                step.attempt(),
                step.maxAttempts(),
                exception.getMessage(),
                List.of(exception.getClass().getSimpleName() + ": " + exception.getMessage()),
                step.startedAt(),
                Instant.now(),
                nextAttemptAt
        );
        if (retryable) {
            retries.add(new RetryState(workflow.workflowId(), step.stepId(), step.attempt(), step.maxAttempts(), backoffMillis, nextAttemptAt, exception.getMessage()));
            eventBus.publish(RuntimeEventType.STEP_FAILED, workflow.incidentId(), workflow.workflowId(), workflow.service(), Map.of(
                    "step", step.stepType().name(),
                    "nextAttemptAt", String.valueOf(nextAttemptAt)
            ));
            return stateStore.updateStep(workflow, failed, WorkflowStatus.RETRYING, agents, retries);
        }
        eventBus.publish(RuntimeEventType.WORKFLOW_DEAD_LETTERED, workflow.incidentId(), workflow.workflowId(), workflow.service(), Map.of(
                "step", step.stepType().name(),
                "error", blankOrDefault(exception.getMessage(), exception.getClass().getSimpleName())
        ));
        return stateStore.updateStep(workflow, failed, WorkflowStatus.DEAD_LETTERED, agents, retries);
    }

    private List<WorkflowStepExecution> initialSteps(String workflowId) {
        List<WorkflowStepType> stepTypes = List.of(
                WorkflowStepType.FETCH_LOGS,
                WorkflowStepType.FETCH_METRICS,
                WorkflowStepType.FETCH_DEPLOYMENT,
                WorkflowStepType.RETRIEVE_MEMORY,
                WorkflowStepType.ANALYZE_DEPLOYMENT,
                WorkflowStepType.GENERATE_RCA,
                WorkflowStepType.PROPOSE_REMEDIATION
        );
        List<WorkflowStepExecution> steps = new ArrayList<>();
        for (int index = 0; index < stepTypes.size(); index++) {
            WorkflowStepType stepType = stepTypes.get(index);
            steps.add(new WorkflowStepExecution(
                    workflowId + "-step-" + (index + 1),
                    stepType,
                    agentRegistry.ownerFor(stepType),
                    WorkflowStepStatus.PENDING,
                    0,
                    3,
                    "",
                    List.of(),
                    null,
                    null,
                    null
            ));
        }
        return steps;
    }

    private WorkflowStepExecution nextExecutableStep(WorkflowExecution workflow) {
        Instant now = Instant.now();
        return workflow.steps().stream()
                .filter(step -> step.status() == WorkflowStepStatus.PENDING
                        || (step.status() == WorkflowStepStatus.FAILED
                        && step.nextAttemptAt() != null
                        && !step.nextAttemptAt().isAfter(now)
                        && step.attempt() < step.maxAttempts()))
                .findFirst()
                .orElse(null);
    }

    private WorkflowExecution markWorkflowStatus(WorkflowExecution workflow, WorkflowStatus status) {
        return stateStore.save(new WorkflowExecution(
                workflow.workflowId(),
                workflow.incidentId(),
                workflow.service(),
                workflow.namespace(),
                workflow.clusterId(),
                workflow.symptom(),
                workflow.signals(),
                "COMPLETE",
                status,
                workflow.steps(),
                workflow.agentExecutions(),
                workflow.retries(),
                workflow.createdAt(),
                Instant.now()
        ));
    }

    private boolean allDone(List<WorkflowStepExecution> steps) {
        return steps.stream().allMatch(step -> step.status() == WorkflowStepStatus.DONE || step.status() == WorkflowStepStatus.SKIPPED);
    }

    private AiRuntimeTaskResponse localAiFallback(WorkflowExecution workflow, WorkflowStepExecution step) {
        String output = step.stepType() == WorkflowStepType.ANALYZE_DEPLOYMENT
                ? "Deployment analysis fallback: inspect rollout, ReplicaSet, image, env, resource limits, and probe changes for " + workflow.service() + "."
                : "RCA fallback: correlate Kubernetes events, recent deployment changes, logs, and operational memory before remediation.";
        return new AiRuntimeTaskResponse(
                workflow.workflowId(),
                step.stepType().name(),
                step.agentType().name(),
                "FALLBACK",
                output,
                List.of("Python runtime or model provider unavailable; Java runtime used deterministic fallback."),
                List.of("fallback " + step.stepType().name() + " for " + workflow.service()),
                false,
                Instant.now()
        );
    }

    private List<String> safeSignals(List<String> signals) {
        return signals == null ? List.of() : signals.stream().filter(signal -> signal != null && !signal.isBlank()).limit(24).toList();
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record StepResult(
            String output,
            List<String> evidence,
            List<String> memoryWrites,
            AgentExecution agentExecution
    ) {
    }
}
