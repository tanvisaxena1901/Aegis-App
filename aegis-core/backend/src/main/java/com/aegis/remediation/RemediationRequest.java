package com.aegis.remediation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RemediationRequest(
        @NotNull RemediationAction action,
        @NotBlank String namespace,
        @NotBlank String targetName,
        @NotBlank String reason,
        boolean approved,
        @NotBlank String confirmation
) {
}
