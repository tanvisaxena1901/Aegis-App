package com.aegis.runtime;

public record MemoryEdge(
        String from,
        String to,
        String relationship,
        double weight
) {
}
