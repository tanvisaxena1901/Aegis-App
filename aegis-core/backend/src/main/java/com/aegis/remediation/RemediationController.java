package com.aegis.remediation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/remediation")
@RequiredArgsConstructor
public class RemediationController {

    private final RemediationService remediationService;

    @PostMapping("/plan")
    public Mono<RemediationPlan> plan(@Valid @RequestBody RemediationRequest request) {
        return remediationService.plan(request);
    }

    @PostMapping("/execute")
    public Mono<RemediationResponse> execute(@Valid @RequestBody RemediationRequest request) {
        return remediationService.execute(request);
    }
}
