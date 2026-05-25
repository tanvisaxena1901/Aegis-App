package com.aegis.runtime;

import java.time.Instant;
import java.util.List;

public record WorkflowStepExecution(
        String stepId,
        WorkflowStepType stepType,
        AgentType agentType,
        WorkflowStepStatus status,
        int attempt,
        int maxAttempts,
        String output,
        List<String> evidence,
        Instant startedAt,
        Instant completedAt,
        Instant nextAttemptAt
) {
}
