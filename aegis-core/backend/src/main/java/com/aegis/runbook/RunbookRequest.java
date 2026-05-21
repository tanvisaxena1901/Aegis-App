package com.aegis.runbook;

import java.util.List;

public record RunbookRequest(
        String namespace,
        String resourceKind,
        String resourceName,
        String symptom,
        List<String> signals
) {
}
