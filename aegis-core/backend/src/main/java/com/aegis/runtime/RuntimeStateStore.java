package com.aegis.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "aegis.runtime.state-store", havingValue = "memory", matchIfMissing = true)
@Primary
public class RuntimeStateStore implements RuntimeStateStorePort {

    private final ConcurrentMap<String, WorkflowExecution> workflows = new ConcurrentHashMap<>();

    @Override
    public String backendLabel() {
        return "IN_MEMORY_POSTGRES_READY";
    }

    public WorkflowExecution save(WorkflowExecution workflow) {
        workflows.put(workflow.workflowId(), workflow);
        return workflow;
    }

    public Optional<WorkflowExecution> find(String workflowId) {
        return Optional.ofNullable(workflows.get(workflowId));
    }

    public List<WorkflowExecution> recent(int limit) {
        return workflows.values().stream()
                .sorted(Comparator.comparing(WorkflowExecution::updatedAt).reversed())
                .limit(limit)
                .toList();
    }

    public List<WorkflowExecution> resumable() {
        Instant now = Instant.now();
        return workflows.values().stream()
                .filter(workflow -> workflow.status() == WorkflowStatus.PENDING
                        || workflow.status() == WorkflowStatus.RUNNING
                        || workflow.status() == WorkflowStatus.RETRYING)
                .filter(workflow -> workflow.steps().stream()
                        .anyMatch(step -> step.status() == WorkflowStepStatus.PENDING
                                || (step.status() == WorkflowStepStatus.FAILED
                                && step.nextAttemptAt() != null
                                && !step.nextAttemptAt().isAfter(now))))
                .sorted(Comparator.comparing(WorkflowExecution::updatedAt))
                .toList();
    }

    public WorkflowExecution updateStep(
            WorkflowExecution workflow,
            WorkflowStepExecution nextStep,
            WorkflowStatus status,
            List<AgentExecution> agentExecutions,
            List<RetryState> retries
    ) {
        List<WorkflowStepExecution> steps = new ArrayList<>();
        for (WorkflowStepExecution step : workflow.steps()) {
            steps.add(step.stepId().equals(nextStep.stepId()) ? nextStep : step);
        }
        WorkflowExecution updated = new WorkflowExecution(
                workflow.workflowId(),
                workflow.incidentId(),
                workflow.service(),
                workflow.namespace(),
                workflow.clusterId(),
                workflow.symptom(),
                workflow.signals(),
                nextCurrentStep(steps),
                status,
                List.copyOf(steps),
                List.copyOf(agentExecutions),
                List.copyOf(retries),
                workflow.createdAt(),
                Instant.now()
        );
        return save(updated);
    }

    private String nextCurrentStep(List<WorkflowStepExecution> steps) {
        return steps.stream()
                .filter(step -> step.status() == WorkflowStepStatus.PENDING
                        || step.status() == WorkflowStepStatus.RUNNING
                        || step.status() == WorkflowStepStatus.FAILED)
                .map(step -> step.stepType().name())
                .findFirst()
                .orElse("COMPLETE");
    }
}
