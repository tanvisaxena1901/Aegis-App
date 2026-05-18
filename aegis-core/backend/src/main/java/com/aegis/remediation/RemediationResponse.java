package com.aegis.remediation;

import java.time.Instant;
import java.util.List;

public record RemediationResponse(
        RemediationAction action,
        String namespace,
        String targetName,
        String status,
        String summary,
        List<String> guardrails,
        Instant executedAt
) {
}
