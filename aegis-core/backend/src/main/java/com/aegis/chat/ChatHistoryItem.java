package com.aegis.chat;

public record ChatHistoryItem(
        String role,
        String content
) {
}
