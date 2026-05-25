package com.aegis.runtime;

import java.util.List;
import java.util.Map;

public interface RuntimeEventBusPort {

    String backendLabel();

    RuntimeEvent publish(
            RuntimeEventType eventType,
            String incidentId,
            String workflowId,
            String service,
            Map<String, String> payload
    );

    List<RuntimeEvent> pending(int limit);

    void acknowledge(String streamId);

    List<RuntimeEvent> recent(int limit);

    int queuedEvents();
}
