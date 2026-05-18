package com.aegis.kubernetes;

import java.util.List;
import java.util.Map;

public record KubernetesDeploymentDetail(
        KubernetesDeploymentSummary summary,
        Map<String, String> labels,
        Map<String, String> annotations,
        List<String> conditions,
        List<String> containers,
        KubernetesRolloutStatus rollout
) {
}
