package com.aegis.runbook;

import java.time.Instant;
import java.util.List;

public record RunbookResponse(
        RunbookIncidentType incidentType,
        String title,
        String summary,
        List<RunbookStep> deterministicSteps,
        List<RunbookRecommendation> suggestedActions,
        String aiEscalationPrompt,
        Instant generatedAt
) {
}
