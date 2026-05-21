package com.aegis.intelligence;

import java.time.Instant;
import java.util.List;

public record RbacScanResponse(
        String clusterId,
        List<RbacPermission> permissions,
        String error,
        Instant observedAt
) {
}
