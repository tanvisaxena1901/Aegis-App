package com.aegis.ai;

import java.util.List;

public record AiRuntimeTaskRequest(
        String workflowId,
        String incidentId,
        String agentType,
        String step,
        String service,
        String namespace,
        String symptom,
        List<String> signals,
        List<String> memory
) {
}
