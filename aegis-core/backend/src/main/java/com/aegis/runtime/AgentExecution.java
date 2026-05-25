package com.aegis.runtime;

import java.time.Instant;
import java.util.List;

public record AgentExecution(
        String executionId,
        String workflowId,
        String stepId,
        AgentType agentType,
        String status,
        List<String> inputSignals,
        String output,
        Instant startedAt,
        Instant completedAt
) {
}
