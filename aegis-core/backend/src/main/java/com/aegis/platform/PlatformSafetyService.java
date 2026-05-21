package com.aegis.platform;

import com.aegis.config.AegisPlatformProperties;
import com.aegis.config.AegisSafetyMode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformSafetyService {

    private final AegisPlatformProperties properties;

    public PlatformSafetyStatus status() {
        AegisSafetyMode mode = properties.safetyMode();
        boolean canMutateWithApproval = mode == AegisSafetyMode.APPROVAL_REQUIRED
                || mode == AegisSafetyMode.AUTONOMOUS_DISABLED;
        return new PlatformSafetyStatus(
                mode,
                true,
                canMutateWithApproval,
                false,
                canMutateWithApproval,
                guardrails(mode),
                Instant.now()
        );
    }

    public void validateMutationAllowed() {
        PlatformSafetyStatus status = status();
        if (!status.canMutateWithApproval()) {
            throw new IllegalArgumentException("Aegis is running in READ_ONLY safety mode. Kubernetes inspection is allowed, but mutation is blocked.");
        }
    }

    private List<String> guardrails(AegisSafetyMode mode) {
        if (mode == AegisSafetyMode.READ_ONLY) {
            return List.of(
                    "Kubernetes API access is inspection-only.",
                    "Remediation execution endpoints are blocked.",
                    "AI can suggest runbook steps but cannot mutate infrastructure."
            );
        }
        return List.of(
                "Human approval is required before any Kubernetes mutation.",
                "Autonomous AI execution is disabled.",
                "Only allowlisted remediation actions are executable.",
                "System namespaces are blocked."
        );
    }
}
