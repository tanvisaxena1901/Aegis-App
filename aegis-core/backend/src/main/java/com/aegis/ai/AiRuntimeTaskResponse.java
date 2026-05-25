package com.aegis.ai;

import java.time.Instant;
import java.util.List;

public record AiRuntimeTaskResponse(
        String workflowId,
        String step,
        String agentType,
        String status,
        String output,
        List<String> evidence,
        List<String> memoryWrites,
        boolean modelAvailable,
        Instant generatedAt
) {
}
