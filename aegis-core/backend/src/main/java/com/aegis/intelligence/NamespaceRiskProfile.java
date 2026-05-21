package com.aegis.intelligence;

import java.util.List;

public record NamespaceRiskProfile(
        String namespace,
        RiskLevel riskLevel,
        int riskScoreModifier,
        boolean approvalRequiredForAllMutations,
        List<String> policyReasons
) {
}
