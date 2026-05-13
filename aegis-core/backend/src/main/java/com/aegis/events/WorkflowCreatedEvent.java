package com.aegis.events;

import java.time.Instant;
import java.util.UUID;

public record WorkflowCreatedEvent(
        UUID workflowId,
        String request,
        Instant occurredAt
) {
}
