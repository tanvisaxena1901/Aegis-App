package com.aegis.runtime;

import java.time.Instant;
import java.util.List;

public record WorkflowExecution(
        String workflowId,
        String incidentId,
        String service,
        String namespace,
        String clusterId,
        String symptom,
        List<String> signals,
        String currentStep,
        WorkflowStatus status,
        List<WorkflowStepExecution> steps,
        List<AgentExecution> agentExecutions,
        List<RetryState> retries,
        Instant createdAt,
        Instant updatedAt
) {
}
