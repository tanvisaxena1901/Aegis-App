# Aegis Core Architecture

## Target System

```text
Frontend (React)
        |
API Gateway (Spring Boot)
        |
Workflow Engine
        |
Kafka Event Bus
        |
Workers / Agents
 |-- AI Worker
 |-- Log Worker
 |-- Metrics Worker
 |-- K8s Worker
 `-- Notification Worker

Storage: PostgreSQL, Redis, OpenSearch
Observability: Prometheus, Grafana, OpenTelemetry
```

## Phase Plan

1. Foundation: workflows, tasks, execution logs, PostgreSQL, REST API.
2. Event-driven architecture: Kafka topics, producers, consumers, worker lifecycle events.
3. AI integration: Ollama-backed incident summaries and remediation suggestions.
4. Reliability: retries, recovery, scheduling, failure policies.
5. Observability: metrics, traces, dashboards, worker health.
6. Kubernetes: deploy backend, workers, Kafka, and PostgreSQL to Kind.

## First End-to-End Feature

The first full feature should stay narrow:

1. Create workflow.
2. Persist workflow and planned tasks.
3. Publish workflow-created event.
4. Worker processes first task.
5. Store result.
6. Display workflow state in the UI.
