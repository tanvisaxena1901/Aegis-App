package com.aegis.runtime;

import java.time.Instant;
import java.util.List;

public record RuntimeStatusResponse(
        String eventBus,
        String stateStore,
        String memoryGraph,
        int queuedEvents,
        List<RuntimeEvent> recentEvents,
        List<WorkflowExecution> workflows,
        OperationalMemoryGraph memory,
        Instant observedAt
) {
}
