package com.aegis.intelligence;

import java.time.Instant;
import java.util.List;

public record PlatformIntelligenceResponse(
        String clusterId,
        List<ClusterContext> clusters,
        NamespaceRiskProfile namespaceRisk,
        List<IncidentTimelineItem> timeline,
        List<DeduplicatedEvent> deduplicatedEvents,
        List<PodRestartPattern> restartPatterns,
        List<GoldenSignal> goldenSignals,
        List<DriftSignal> drift,
        RbacScanResponse rbac,
        List<RunbookCodeDefinition> runbooksAsCode,
        String error,
        Instant observedAt
) {
}
