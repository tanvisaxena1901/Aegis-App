package com.aegis.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        WorkflowStatus status,
        String currentStep,
        int retryCount,
        String request,
        Instant createdAt,
        Instant updatedAt
) {

    static WorkflowResponse from(Workflow workflow) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getStatus(),
                workflow.getCurrentStep(),
                workflow.getRetryCount(),
                workflow.getRequest(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }
}
