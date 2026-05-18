package com.aegis.kubernetes;

import java.time.Instant;
import java.util.List;

public record KubernetesPodSummary(
        String namespace,
        String name,
        String phase,
        int readyContainers,
        int totalContainers,
        int restarts,
        String nodeName,
        List<String> containers,
        List<String> statusReasons,
        boolean failing,
        Instant createdAt
) {
}
