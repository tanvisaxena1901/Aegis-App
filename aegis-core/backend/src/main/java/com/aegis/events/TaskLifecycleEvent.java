package com.aegis.events;

import com.aegis.task.TaskStatus;
import com.aegis.task.TaskType;
import java.time.Instant;
import java.util.UUID;

public record TaskLifecycleEvent(
        UUID workflowId,
        UUID taskId,
        TaskType type,
        TaskStatus status,
        Instant occurredAt
) {
}
