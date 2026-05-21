package com.aegis.kubernetes;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/kubernetes")
@RequiredArgsConstructor
public class KubernetesOperationsController {

    private final KubernetesOperationsService operationsService;
    private final KubernetesWatcherService watcherService;

    @GetMapping("/namespaces/{namespace}/pods")
    public Mono<List<KubernetesPodSummary>> pods(@PathVariable String namespace) {
        return operationsService.pods(namespace);
    }

    @GetMapping("/namespaces/{namespace}/pods/{podName}/logs")
    public Mono<KubernetesPodLogs> podLogs(
            @PathVariable String namespace,
            @PathVariable String podName,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) Integer tailLines
    ) {
        return operationsService.podLogs(namespace, podName, container, tailLines);
    }

    @GetMapping("/namespaces/{namespace}/deployments")
    public Mono<List<KubernetesDeploymentSummary>> deployments(@PathVariable String namespace) {
        return operationsService.deployments(namespace);
    }

    @GetMapping("/namespaces/{namespace}/deployments/{deploymentName}/describe")
    public Mono<KubernetesDeploymentDetail> describeDeployment(
            @PathVariable String namespace,
            @PathVariable String deploymentName
    ) {
        return operationsService.describeDeployment(namespace, deploymentName);
    }

    @GetMapping("/namespaces/{namespace}/deployments/{deploymentName}/rollout")
    public Mono<KubernetesRolloutStatus> rolloutStatus(
            @PathVariable String namespace,
            @PathVariable String deploymentName
    ) {
        return operationsService.rolloutStatus(namespace, deploymentName);
    }

    @GetMapping("/namespaces/{namespace}/events")
    public Mono<List<KubernetesEventSummary>> events(@PathVariable String namespace) {
        return operationsService.events(namespace);
    }

    @GetMapping("/watcher/status")
    public WatcherStatus watcherStatus() {
        return watcherService.status();
    }
}
