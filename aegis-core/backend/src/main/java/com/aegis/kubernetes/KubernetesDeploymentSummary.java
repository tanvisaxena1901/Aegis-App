package com.aegis.kubernetes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record KubernetesDeploymentSummary(
        String namespace,
        String name,
        int replicas,
        int readyReplicas,
        int updatedReplicas,
        int availableReplicas,
        String strategy,
        Map<String, String> selector,
        List<String> images,
        Instant createdAt
) {
}
