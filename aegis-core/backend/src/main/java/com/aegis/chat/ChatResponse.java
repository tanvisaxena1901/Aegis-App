package com.aegis.chat;

import com.aegis.incident.IncidentSeverity;
import java.time.Instant;
import java.util.List;

public record ChatResponse(
        String answer,
        IncidentSeverity severity,
        List<ChatEvidence> evidence,
        List<String> recommendedActions,
        boolean humanApprovalRequired,
        Instant generatedAt
) {
}
