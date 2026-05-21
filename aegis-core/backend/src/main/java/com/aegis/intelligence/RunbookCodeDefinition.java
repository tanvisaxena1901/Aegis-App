package com.aegis.intelligence;

import java.util.List;

public record RunbookCodeDefinition(
        String incidentType,
        String version,
        List<String> steps,
        List<String> aiAfter
) {
}
