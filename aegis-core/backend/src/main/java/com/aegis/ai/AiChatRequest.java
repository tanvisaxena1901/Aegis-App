package com.aegis.ai;

import java.util.List;

public record AiChatRequest(
        String message,
        List<AiChatMessage> history,
        List<String> evidence,
        List<String> metrics
) {
}
