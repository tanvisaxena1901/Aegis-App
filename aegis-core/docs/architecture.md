# Aegis Core Architecture

## Direction

Aegis Core is a Kubernetes intelligence platform, not a generic AI chatbot and not a Kafka-first telemetry system.

The first version should prove one valuable loop:

```text
Kubernetes signals -> Java incident engine -> Python LangGraph RCA -> remediation plan -> dashboard
```

## Phase 1

- Java Spring Boot backend owns platform APIs, Kubernetes access, incident intake, and remediation policy.
- Python FastAPI service owns LangGraph reasoning and local AI integration.
- React dashboard shows cluster and investigation state.
- Kafka is intentionally excluded from Phase 1.

## Phase 2

- Add Prometheus metrics collection.
- Add OpenTelemetry traces and service maps.
- Add Fluent Bit log collection.
- Add persistence for incidents and investigation history.

## Phase 3

- Add Kafka only when multi-cluster ingestion or high-volume stream processing is real.
- Add Kubernetes CRDs for investigation workflows.
- Add approval-gated remediation actions such as rollback, restart, and resource patch suggestions.

## Why Polyglot

Java is the platform layer:

- Kubernetes Java client
- WebFlux APIs
- reliability policies
- observability integration
- strong backend/platform signal

Python is the AI layer:

- LangGraph
- local LLM/Ollama experimentation
- RCA graph iteration
- remediation planning

This separation keeps Aegis credible as platform engineering while still using the best AI tooling.
