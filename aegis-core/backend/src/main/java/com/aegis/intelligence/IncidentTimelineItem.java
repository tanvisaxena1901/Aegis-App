package com.aegis.intelligence;

import java.time.Instant;

public record IncidentTimelineItem(
        Instant timestamp,
        String stage,
        String reason,
        String involvedObject,
        String message
) {
}
