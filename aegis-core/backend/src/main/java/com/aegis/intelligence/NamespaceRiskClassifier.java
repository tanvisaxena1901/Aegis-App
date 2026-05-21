package com.aegis.intelligence;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class NamespaceRiskClassifier {

    public NamespaceRiskProfile classify(String namespace) {
        String value = namespace == null || namespace.isBlank() ? "unknown" : namespace.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("prod") || normalized.contains("payment") || normalized.contains("checkout")) {
            return new NamespaceRiskProfile(
                    value,
                    RiskLevel.HIGH,
                    25,
                    true,
                    List.of("Customer-facing or revenue path namespace.", "Rollback, scale, and resource patches require elevated review.")
            );
        }
        if (normalized.contains("platform") || normalized.contains("infra") || normalized.contains("system")) {
            return new NamespaceRiskProfile(
                    value,
                    RiskLevel.MEDIUM,
                    15,
                    true,
                    List.of("Shared platform namespace.", "Blast radius can cross multiple services.")
            );
        }
        if (normalized.contains("dev") || normalized.contains("test") || normalized.contains("sandbox")) {
            return new NamespaceRiskProfile(
                    value,
                    RiskLevel.LOW,
                    0,
                    true,
                    List.of("Non-production namespace.", "Approval remains required because autonomous remediation is disabled.")
            );
        }
        return new NamespaceRiskProfile(
                value,
                RiskLevel.MEDIUM,
                10,
                true,
                List.of("Namespace risk is not explicitly classified.", "Defaulting to medium enterprise controls.")
        );
    }
}
