package com.aegis.kubernetes;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final KubernetesOperationsService operationsService;

    @GetMapping("/health")
    public Mono<EnvironmentHealthResponse> health() {
        return operationsService.environmentHealth();
    }
}
