package com.aegis.runtime;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/runtime")
@RequiredArgsConstructor
public class RuntimeController {

    private final WorkflowRuntimeService workflowRuntimeService;
    private final AgentRegistry agentRegistry;

    @GetMapping("/status")
    public Mono<RuntimeStatusResponse> status() {
        return Mono.fromCallable(workflowRuntimeService::status).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/agents")
    public List<AgentRegistryEntry> agents() {
        return agentRegistry.agents();
    }

    @PostMapping("/incidents")
    public Mono<RuntimeDispatchResponse> createIncident(@Valid @RequestBody RuntimeIncidentRequest request) {
        return workflowRuntimeService.createIncident(request);
    }

    @PostMapping("/workflows/{workflowId}/resume")
    public Mono<WorkflowExecution> resume(@PathVariable String workflowId) {
        return workflowRuntimeService.resume(workflowId);
    }
}
