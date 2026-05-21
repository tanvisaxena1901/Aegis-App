package com.aegis.remediation;

import java.time.Instant;
import java.util.List;

public record RemediationPlan(
        RemediationAction action,
        String namespace,
        String targetName,
        String commandPreview,
        String risk,
        String namespaceRisk,
        int riskScore,
        String dryRunCommand,
        String dryRunPatch,
        String dryRunDiff,
        boolean approvalRequired,
        boolean executableInCurrentMode,
        List<String> deterministicChecks,
        List<String> guardrails,
        Instant generatedAt
) {
}
