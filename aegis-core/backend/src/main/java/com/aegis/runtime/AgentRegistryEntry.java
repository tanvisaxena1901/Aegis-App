package com.aegis.runtime;

import java.util.List;

public record AgentRegistryEntry(
        AgentType agentType,
        String displayName,
        List<WorkflowStepType> ownsSteps,
        String runtime,
        String responsibility
) {
}
