package com.aegis.runtime;

import java.time.Instant;
import java.util.List;

public record RuntimeDispatchResponse(
        String incidentId,
        String workflowId,
        String eventStreamId,
        WorkflowStatus status,
        List<String> acceptedSignals,
        Instant createdAt
) {
}
