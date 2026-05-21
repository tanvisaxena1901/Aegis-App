package com.aegis.intelligence;

import java.time.Instant;
import java.util.List;

public record DeploymentDiffResponse(
        String clusterId,
        String namespace,
        String deploymentName,
        boolean baselineCaptured,
        List<DeploymentSpecChange> changes,
        Instant observedAt
) {
}
