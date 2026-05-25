package com.aegis.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "aegis.runtime.event-bus", havingValue = "redis")
public class RedisRuntimeEventBus implements RuntimeEventBusPort {

    private static final String STREAM_KEY = "aegis.runtime.events";
    private static final String ACK_SET_KEY = "aegis.runtime.events:ack";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRuntimeEventBus(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String backendLabel() {
        return "REDIS_STREAMS_READY";
    }

    @Override
    public RuntimeEvent publish(
            RuntimeEventType eventType,
            String incidentId,
            String workflowId,
            String service,
            Map<String, String> payload
    ) {
        Map<String, String> fields = new HashMap<>();
        fields.put("eventType", eventType.name());
        fields.put("incidentId", blankOrDefault(incidentId, ""));
        fields.put("workflowId", blankOrDefault(workflowId, ""));
        fields.put("service", blankOrDefault(service, ""));
        fields.put("createdAt", Instant.now().toString());
        fields.put("payloadJson", toJson(payload == null ? Map.of() : payload));
        RecordId recordId = redisTemplate.opsForStream()
                .add(StreamRecords.newRecord().in(STREAM_KEY).ofStrings(fields));
        return new RuntimeEvent(
                recordId.getValue(),
                eventType,
                incidentId,
                workflowId,
                service,
                payload == null ? Map.of() : Map.copyOf(payload),
                Instant.parse(fields.get("createdAt"))
        );
    }

    @Override
    public List<RuntimeEvent> pending(int limit) {
        Set<String> acknowledged = redisTemplate.opsForSet().members(ACK_SET_KEY);
        return streamRange()
                .stream()
                .filter(event -> acknowledged == null || !acknowledged.contains(event.streamId()))
                .limit(limit)
                .toList();
    }

    @Override
    public void acknowledge(String streamId) {
        if (streamId != null && !streamId.isBlank()) {
            redisTemplate.opsForSet().add(ACK_SET_KEY, streamId);
        }
    }

    @Override
    public List<RuntimeEvent> recent(int limit) {
        return redisTemplate.opsForStream()
                .reverseRange(STREAM_KEY, Range.unbounded())
                .stream()
                .map(this::toRuntimeEvent)
                .limit(limit)
                .toList();
    }

    @Override
    public int queuedEvents() {
        Set<String> acknowledged = redisTemplate.opsForSet().members(ACK_SET_KEY);
        long total = redisTemplate.opsForStream().size(STREAM_KEY) == null ? 0L : redisTemplate.opsForStream().size(STREAM_KEY);
        long acked = acknowledged == null ? 0L : acknowledged.size();
        return (int) Math.max(0L, total - acked);
    }

    private List<RuntimeEvent> streamRange() {
        return redisTemplate.opsForStream()
                .range(STREAM_KEY, Range.unbounded())
                .stream()
                .map(this::toRuntimeEvent)
                .toList();
    }

    private RuntimeEvent toRuntimeEvent(MapRecord<String, ?, ?> record) {
        Map<?, ?> value = record.getValue();
        return new RuntimeEvent(
                record.getId().getValue(),
                RuntimeEventType.valueOf(text(value.get("eventType"), RuntimeEventType.INCIDENT_CREATED.name())),
                text(value.get("incidentId"), ""),
                text(value.get("workflowId"), ""),
                text(value.get("service"), ""),
                parsePayload(text(value.get("payloadJson"), "")),
                instant(text(value.get("createdAt"), ""))
        );
    }

    private Map<String, String> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception exception) {
            return Map.of("raw", payloadJson);
        }
    }

    private String toJson(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return Instant.parse(value);
    }

    private String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
