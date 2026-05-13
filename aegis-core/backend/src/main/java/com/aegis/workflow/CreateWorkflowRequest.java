package com.aegis.workflow;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkflowRequest(
        @NotBlank String request
) {
}
