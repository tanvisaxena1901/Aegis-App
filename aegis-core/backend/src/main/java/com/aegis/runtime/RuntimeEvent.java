package com.aegis.runtime;

import java.time.Instant;
import java.util.Map;

public record RuntimeEvent(
        String streamId,
        RuntimeEventType eventType,
        String incidentId,
        String workflowId,
        String service,
        Map<String, String> payload,
        Instant createdAt
) {
}
