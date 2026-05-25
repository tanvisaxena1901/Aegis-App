package com.aegis.runtime;

import java.time.Instant;
import java.util.List;

public record OperationalMemoryGraph(
        List<MemoryNode> nodes,
        List<MemoryEdge> edges,
        List<String> causalPaths,
        String storageMode,
        Instant observedAt
) {
}
