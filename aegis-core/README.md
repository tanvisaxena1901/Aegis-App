# Aegis Core

Aegis Core is the main application bundle for the platform. It includes the Java backend, the Python AI service, the React dashboard, and the Kubernetes and local infrastructure assets needed to run the stack.

## What This Project Does

Aegis Core helps operators investigate Kubernetes incidents faster by combining live cluster signals, workflow state, operational memory, and AI reasoning.

It focuses on:

- Reading Kubernetes deployments, pods, events, logs, and runtime status.
- Building incident workflows that collect evidence in stages.
- Asking the AI service to reason over evidence and operational memory.
- Suggesting approval-gated remediation instead of performing autonomous changes.
- Showing the operator a single dashboard for triage, analysis, and follow-up actions.

## How It Works

1. The backend gathers Kubernetes telemetry and creates or resumes a workflow when an incident is created.
2. The workflow runtime sends signals to the AI service step by step.
3. The AI service reasons over the evidence and retrieves related memory when available.
4. The backend stores workflow state and operational memory records.
5. The dashboard renders the live cluster view, AI analysis, and remediation candidates.

## Architecture

```text
Kubernetes Cluster
        |
Java Spring Boot Backend
 |-- Kubernetes signal collection
 |-- Workflow runtime
 |-- Operational memory graph
 |-- Remediation policy and execution
        |
Python FastAPI + LangGraph AI Service
 |-- Evidence normalization
 |-- Memory retrieval
 |-- Model-backed reasoning
        |
React Dashboard
 |-- Cluster overview
 |-- Incident investigation
 |-- Remediation prompt
 |-- Read-only terminal
```

The runtime memory layer can use an in-memory graph, Neo4j, or OpenSearch-backed search depending on configuration.

## Benefits

- Cuts down on manual triage by keeping cluster evidence, memory, and reasoning in one flow.
- Keeps remediation explicit and approval-gated.
- Makes repeated incidents easier to diagnose because prior memory is searchable.
- Works locally and in Kubernetes.

## Structure

```text
aegis-core/
  backend/     Java 21 Spring Boot platform backend
  ai-service/  Python FastAPI + LangGraph reasoning service
  frontend/    React dashboard shell
  infra/       Local runtime notes and compose files
  k8s/         Kubernetes manifests for deployment
  docs/        Architecture and phase notes
  scripts/     Local developer scripts
```

## Install

From the repository root:

```bash
cd aegis-core/frontend
npm install

cd ../ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
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
source .venv/bin/activate
uvicorn app.main:app --reload --port 8090
```

Frontend:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis/aegis-core/frontend
npm run dev
```

## Use It

1. Open the frontend in the browser.
2. Review the cluster metrics and traffic status.
3. Inspect workloads, events, logs, and AI triage.
4. Open the suggested remediation card if you want to plan a change.
5. Confirm the action explicitly before execution.

There is also a read-only kubectl terminal in the dashboard for quick checks.

## AI Setup

OpenAI:

```env
AI_PROVIDER=openai
OPENAI_API_KEY=your_api_key_here
OPENAI_MODEL=gpt-4.1-mini
```

Ollama:

```env
AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=qwen2.5:1.5b
```

If `AI_PROVIDER=auto`, OpenAI is used when the key is available and Ollama is used otherwise.

## Useful Commands

Backend tests:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
```

Frontend build:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis/aegis-core/frontend
npm run build
```

AI service compile check:

```bash
cd /Users/tanvisaxena/IdeaProjects/Aegis/aegis-core/ai-service
.venv/bin/python -m compileall app
```

## Kubernetes

Use the manifests in `k8s/` to deploy the full stack in-cluster with Kustomize.

## Related Docs

- `../README.md`: top-level product overview and full setup guide.
- `docs/architecture.md`: design notes and platform structure.
- `infra/README.md`: local infrastructure and OpenSearch setup.
- `k8s/README.md`: Kubernetes deployment guide.
