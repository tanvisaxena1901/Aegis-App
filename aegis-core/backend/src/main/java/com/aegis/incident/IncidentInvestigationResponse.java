package com.aegis.incident;

import java.time.Instant;
import java.util.List;

public record IncidentInvestigationResponse(
        String incidentId,
        IncidentSeverity severity,
        String probableCause,
        String summary,
        List<String> evidence,
        List<String> recommendedActions,
        boolean humanApprovalRequired,
        Instant generatedAt
) {
}
