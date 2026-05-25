package com.aegis.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "aegis.runtime.event-bus", havingValue = "memory", matchIfMissing = true)
@Primary
public class RuntimeEventBus implements RuntimeEventBusPort {

    private final AtomicLong sequence = new AtomicLong();
    private final List<RuntimeEvent> stream = new CopyOnWriteArrayList<>();
    private final List<String> acknowledged = new CopyOnWriteArrayList<>();

    @Override
    public String backendLabel() {
        return "IN_MEMORY_REDIS_STREAMS_READY";
    }

    public RuntimeEvent publish(
            RuntimeEventType eventType,
            String incidentId,
            String workflowId,
            String service,
            Map<String, String> payload
    ) {
        long now = Instant.now().toEpochMilli();
        String streamId = now + "-" + sequence.incrementAndGet();
        RuntimeEvent event = new RuntimeEvent(
                streamId,
                eventType,
                incidentId,
                workflowId,
                service,
                payload == null ? Map.of() : Map.copyOf(payload),
                Instant.now()
        );
        stream.add(event);
        return event;
    }

    public List<RuntimeEvent> pending(int limit) {
        return stream.stream()
                .filter(event -> !acknowledged.contains(event.streamId()))
                .sorted(Comparator.comparing(RuntimeEvent::createdAt))
                .limit(limit)
                .toList();
    }

    public void acknowledge(String streamId) {
        if (!acknowledged.contains(streamId)) {
            acknowledged.add(streamId);
        }
    }

    public List<RuntimeEvent> recent(int limit) {
        List<RuntimeEvent> copy = new ArrayList<>(stream);
        copy.sort(Comparator.comparing(RuntimeEvent::createdAt).reversed());
        return copy.stream().limit(limit).toList();
    }

    public int queuedEvents() {
        return pending(Integer.MAX_VALUE).size();
    }
}
