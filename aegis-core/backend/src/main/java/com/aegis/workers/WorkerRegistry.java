package com.aegis.workers;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WorkerRegistry {

    public List<String> plannedWorkers() {
        return List.of("ai-worker", "log-worker", "metrics-worker", "k8s-worker", "notification-worker");
    }
}
