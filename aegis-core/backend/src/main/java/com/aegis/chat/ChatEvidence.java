package com.aegis.chat;

public record ChatEvidence(
        String sourceType,
        String sourceName,
        String message
) {
    public String asPromptLine() {
        return sourceType + " " + sourceName + ": " + message;
    }
}
