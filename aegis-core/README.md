# Aegis Core

Aegis Core is an AI-native Kubernetes investigation and remediation workflow platform.

The MVP is intentionally not a Kafka project. Phase 1 focuses on Kubernetes intelligence: watch cluster signals, collect incidents, ask an isolated Python LangGraph service for root-cause reasoning, and expose the result through a Java platform backend and a simple dashboard.

## Architecture

```text
Kubernetes Cluster
        |
Java Platform Backend
 |-- Kubernetes Watcher
 |-- Incident Engine
 |-- Remediation API
 `-- Observability Hooks
        |
Python AI Service
 |-- LangGraph RCA Graph
 |-- Remediation Planner
 `-- OpenAI / Ollama LLM interface
        |
React Dashboard
```

## Structure

```text
aegis-core/
  backend/     Java 21 Spring Boot platform backend
  ai-service/  Python FastAPI + LangGraph reasoning service
  frontend/    React dashboard shell
  infra/       Local runtime notes and compose files
  k8s/         Kubernetes manifests for later deployment
  docs/        Architecture and phase plan
  scripts/     Local developer scripts
```

## Run Locally

Backend:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :aegis-core:backend:bootRun
```

Python AI service:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis/aegis-core/ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export AI_PROVIDER=openai
export OPENAI_API_KEY=your_api_key_here
export OPENAI_MODEL=gpt-4.1-mini
uvicorn app.main:app --reload --port 8090
```

If `AI_PROVIDER=auto`, Aegis uses OpenAI when `OPENAI_API_KEY` is present and falls back to Ollama otherwise.

Frontend:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis/aegis-core/frontend
npm install
npm run dev
```

Try the investigation API:

```bash
curl -X POST http://localhost:8080/api/incidents/investigate \
  -H 'Content-Type: application/json' \
  -d '{
    "namespace":"default",
    "resourceKind":"Pod",
    "resourceName":"checkout-api-7d9f",
    "symptom":"CrashLoopBackOff after deployment",
    "events":["Back-off restarting failed container"],
    "logs":["OutOfMemoryError: Java heap space"],
    "metrics":["container_memory_working_set_bytes near limit"]
  }'
```
