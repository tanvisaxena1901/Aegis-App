package com.aegis.ai;

import com.aegis.incident.IncidentInvestigationRequest;
import com.aegis.incident.IncidentInvestigationResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AiReasoningClient {

    private final WebClient aiWebClient;

    public Mono<IncidentInvestigationResponse> investigate(IncidentInvestigationRequest request) {
        return aiWebClient.post()
                .uri("/v1/rca/investigate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(IncidentInvestigationResponse.class)
                .timeout(Duration.ofSeconds(90));
    }

    public Mono<AiChatResponse> chat(AiChatRequest request) {
        return aiWebClient.post()
                .uri("/v1/chat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiChatResponse.class)
                .timeout(Duration.ofSeconds(90));
    }
}
