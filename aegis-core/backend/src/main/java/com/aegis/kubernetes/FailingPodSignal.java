package com.aegis.kubernetes;

import java.util.List;

public record FailingPodSignal(
        String namespace,
        String name,
        String phase,
        int readyContainers,
        int totalContainers,
        int restarts,
        String nodeName,
        List<String> reasons
) {
}
