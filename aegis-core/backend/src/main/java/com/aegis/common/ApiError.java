package com.aegis.common;

import java.time.Instant;

public record ApiError(
        String message,
        Instant timestamp
) {
}
