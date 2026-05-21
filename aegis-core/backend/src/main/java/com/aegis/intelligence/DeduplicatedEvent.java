package com.aegis.intelligence;

import java.time.Instant;

public record DeduplicatedEvent(
        String reason,
        String type,
        String involvedObject,
        String message,
        int occurrences,
        Instant lastSeen
) {
}
