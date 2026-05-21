package com.aegis.intelligence;

import java.util.List;

public record PodRestartPattern(
        String namespace,
        String podName,
        String pattern,
        RiskLevel severity,
        int restarts,
        List<String> evidence
) {
}
