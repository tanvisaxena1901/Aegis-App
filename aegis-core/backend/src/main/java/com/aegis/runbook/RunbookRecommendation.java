package com.aegis.runbook;

import com.aegis.remediation.RemediationAction;

public record RunbookRecommendation(
        RemediationAction action,
        String targetKind,
        String targetName,
        String reason
) {
}
