package com.aegis.task;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID workflowId,
        TaskType type,
        TaskStatus status,
        String payload,
        int retries,
        Instant createdAt,
        Instant updatedAt
) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getWorkflowId(),
                task.getType(),
                task.getStatus(),
                task.getPayload(),
                task.getRetries(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
