package com.aegis.platform;

import com.aegis.config.AegisSafetyMode;
import java.time.Instant;
import java.util.List;

public record PlatformSafetyStatus(
        AegisSafetyMode mode,
        boolean canInspect,
        boolean canMutateWithApproval,
        boolean autonomousActionsEnabled,
        boolean approvalRequired,
        List<String> guardrails,
        Instant observedAt
) {
}
