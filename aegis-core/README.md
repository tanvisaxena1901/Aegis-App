# Aegis Core

Aegis Core is an AI-native orchestration engine for workflow execution, task coordination, event processing, reliability actions, and agent-driven operations.

The project intentionally starts small. Phase 1 creates one Spring Boot backend that can create workflows, plan retryable tasks, persist state, and expose basic APIs. Kafka, independent workers, Ollama AI actions, observability, and Kubernetes deployment are staged for later phases.

## Structure

```text
aegis-core/
  backend/   Spring Boot API and workflow foundation
  frontend/  Simple React dashboard shell
  infra/     Local infrastructure notes and compose entrypoints
  docker/    Docker assets
  k8s/       Kubernetes manifests
  docs/      Architecture and phase docs
  scripts/   Local helper scripts
```

## Phase 1 API

- `POST /api/workflows` creates a workflow and planned tasks.
- `GET /api/workflows` lists workflows.
- `GET /api/workflows/{workflowId}` gets one workflow.
- `GET /api/workflows/{workflowId}/tasks` lists workflow tasks.

## Run Locally

In IntelliJ, set the Gradle JVM to the installed JDK 21:

```text
/Users/tanvisaxena/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home
```

Start PostgreSQL:

```bash
docker compose -f aegis-core/infra/docker-compose.yml up -d postgres
```

Run the backend:

```bash
./gradlew :aegis-core:backend:bootRun
```

Create a workflow:

```bash
curl -X POST http://localhost:8080/api/workflows \
  -H 'Content-Type: application/json' \
  -d '{"request":"Investigate failed deployment and notify team."}'
```
