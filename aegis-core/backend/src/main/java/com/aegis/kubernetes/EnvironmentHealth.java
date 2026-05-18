package com.aegis.kubernetes;

import java.util.List;

public record EnvironmentHealth(
        String environment,
        String namespace,
        EnvironmentSeverity severity,
        int pods,
        int runningPods,
        int failingPods,
        int restarts,
        int warningEvents,
        int deployments,
        int readyDeployments,
        List<FailingPodSignal> failingPodSignals,
        List<String> signals
) {
}
