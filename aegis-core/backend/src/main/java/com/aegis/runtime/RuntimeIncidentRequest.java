package com.aegis.runtime;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RuntimeIncidentRequest(
        @NotBlank String service,
        @NotBlank String namespace,
        String clusterId,
        String severity,
        @NotBlank String symptom,
        List<String> signals
) {
}
