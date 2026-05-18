package com.aegis.kubernetes;

import java.time.Instant;
import java.util.List;

public record EnvironmentHealthResponse(
        List<EnvironmentHealth> environments,
        Instant observedAt
) {
}
