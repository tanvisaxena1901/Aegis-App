package com.aegis.runtime;

import java.time.Instant;

public record RetryState(
        String workflowId,
        String stepId,
        int attempt,
        int maxAttempts,
        long backoffMillis,
        Instant nextAttemptAt,
        String lastError
) {
}
