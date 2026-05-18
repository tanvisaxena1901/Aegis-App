package com.aegis.terminal;

import java.time.Instant;

public record TerminalResponse(
        String command,
        String output,
        int exitCode,
        long durationMs,
        Instant generatedAt
) {
}
