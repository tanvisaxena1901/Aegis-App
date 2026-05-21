package com.aegis.intelligence;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/platform-intelligence")
@RequiredArgsConstructor
public class PlatformIntelligenceController {

    private final PlatformIntelligenceService intelligenceService;
    private final NamespaceRiskClassifier namespaceRiskClassifier;

    @GetMapping("/clusters")
    public List<ClusterContext> clusters(@RequestParam(required = false) String clusterId) {
        return intelligenceService.clusters(clusterId);
    }

    @GetMapping("/namespaces/{namespace}")
    public Mono<PlatformIntelligenceResponse> intelligence(
            @PathVariable String namespace,
            @RequestParam(required = false) String clusterId
    ) {
        return intelligenceService.intelligence(clusterId, namespace);
    }

    @GetMapping("/namespaces/{namespace}/risk")
    public NamespaceRiskProfile namespaceRisk(@PathVariable String namespace) {
        return namespaceRiskClassifier.classify(namespace);
    }

    @GetMapping("/namespaces/{namespace}/rbac")
    public Mono<RbacScanResponse> rbac(
            @PathVariable String namespace,
            @RequestParam(required = false) String clusterId
    ) {
        return intelligenceService.rbac(clusterId, namespace);
    }

    @GetMapping("/namespaces/{namespace}/deployments/{deploymentName}/diff")
    public Mono<DeploymentDiffResponse> deploymentDiff(
            @PathVariable String namespace,
            @PathVariable String deploymentName,
            @RequestParam(required = false) String clusterId
    ) {
        return intelligenceService.deploymentDiff(clusterId, namespace, deploymentName);
    }

    @GetMapping("/runbooks-as-code")
    public List<RunbookCodeDefinition> runbooksAsCode() {
        return intelligenceService.runbooksAsCode();
    }

    @GetMapping("/replay/sample")
    public IncidentReplayResponse replaySample() {
        return intelligenceService.replaySample();
    }
}
