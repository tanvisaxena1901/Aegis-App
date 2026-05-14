package com.aegis.kubernetes;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/cluster")
@RequiredArgsConstructor
public class KubernetesController {

    private final KubernetesSignalService kubernetesSignalService;

    @GetMapping("/snapshot")
    public Mono<ClusterSnapshot> snapshot() {
        return kubernetesSignalService.snapshot();
    }
}
