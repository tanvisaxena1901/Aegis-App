package com.aegis.kubernetes;

import java.util.List;

public record KubernetesRolloutStatus(
        String namespace,
        String name,
        boolean ready,
        String summary,
        int replicas,
        int readyReplicas,
        int updatedReplicas,
        int availableReplicas,
        long generation,
        long observedGeneration,
        List<String> conditions
) {
}
