package com.aegis.runbook;

public record RunbookStep(
        int order,
        String title,
        String command,
        String expectedSignal
) {
}
