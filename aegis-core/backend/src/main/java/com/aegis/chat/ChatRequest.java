package com.aegis.chat;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank String message,
        String namespace,
        String deploymentName,
        String podName,
        Boolean includeLogs,
        List<ChatHistoryItem> history
) {
}
