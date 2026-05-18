package com.aegis.ai;

import java.time.Instant;

public record AiChatResponse(
        String answer,
        String model,
        boolean ollamaAvailable,
        Instant generatedAt
) {
}
