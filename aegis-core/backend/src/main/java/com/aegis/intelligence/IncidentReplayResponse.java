package com.aegis.intelligence;

import java.time.Instant;
import java.util.List;

public record IncidentReplayResponse(
        String replayId,
        String title,
        String clusterId,
        String namespace,
        List<IncidentTimelineItem> timeline,
        List<PodRestartPattern> restartPatterns,
        List<DeduplicatedEvent> deduplicatedEvents,
        List<String> investigationSteps,
        Instant loadedAt
) {
}
