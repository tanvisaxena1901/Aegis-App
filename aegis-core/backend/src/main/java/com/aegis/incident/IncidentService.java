package com.aegis.incident;

import com.aegis.ai.AiReasoningClient;
import com.aegis.remediation.RemediationPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class IncidentService {

    private final AiReasoningClient aiReasoningClient;
    private final RemediationPolicy remediationPolicy;

    public Mono<IncidentInvestigationResponse> investigate(IncidentInvestigationRequest request) {
        return aiReasoningClient.investigate(request)
                .map(response -> response.humanApprovalRequired()
                        ? response
                        : withPolicyApproval(response))
                .onErrorResume(error -> Mono.just(localFallback(request, error)));
    }

    private IncidentInvestigationResponse withPolicyApproval(IncidentInvestigationResponse response) {
        boolean approvalRequired = remediationPolicy.requiresHumanApproval(response.severity());
        return new IncidentInvestigationResponse(
                response.incidentId(),
                response.severity(),
                response.probableCause(),
                response.summary(),
                response.evidence(),
                response.recommendedActions(),
                approvalRequired,
                response.generatedAt()
        );
    }

    private IncidentInvestigationResponse localFallback(IncidentInvestigationRequest request, Throwable error) {
        return new IncidentInvestigationResponse(
                UUID.randomUUID().toString(),
                IncidentSeverity.MEDIUM,
                "AI service unavailable: " + error.getClass().getSimpleName(),
                "Aegis captured the incident context but could not reach the Python reasoning service.",
                List.of(request.symptom()),
                List.of("Start ai-service on port 8090", "Retry investigation", "Inspect Kubernetes events and container logs manually"),
                true,
                Instant.now()
        );
    }
}
