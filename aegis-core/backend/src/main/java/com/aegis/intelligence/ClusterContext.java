package com.aegis.intelligence;

public record ClusterContext(
        String clusterId,
        String displayName,
        String environment,
        boolean current,
        String status
) {
}
