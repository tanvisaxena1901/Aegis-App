package com.aegis.incident;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record IncidentInvestigationRequest(
        @NotBlank String namespace,
        @NotBlank String resourceKind,
        @NotBlank String resourceName,
        @NotBlank String symptom,
        List<String> events,
        List<String> logs,
        List<String> metrics
) {
}
