# Aegis

Aegis is an AI-native Kubernetes operations platform. It combines a Spring Boot backend, a Python LangGraph reasoning service, and a React dashboard to help operators inspect cluster health, explain failures, reason over operational memory, and prepare approval-gated remediation.

## What It Is

Aegis is not a generic chatbot. It is a workflow-driven Kubernetes investigation system with three core layers:

- A Java platform backend that owns cluster access, incident workflow state, remediation policy, and telemetry aggregation.
- A Python LangGraph AI service that reasons over incidents, logs, deployment signals, and operational memory.
- A React dashboard that presents cluster health, RCA results, remediation plans, and read-only kubectl access.

The memory layer can run on an in-memory graph, Neo4j, or OpenSearch-backed operational memory search depending on configuration.

## How It Works

1. The backend watches Kubernetes signals, reads deployments, pods, events, and runtime status, and creates a workflow when an incident is created.
2. The runtime sends structured evidence to the AI service in steps, including logs, metrics, deployment context, and memory graph matches.
3. The AI service produces an analysis and can write new memory records for future incidents.
4. The backend exposes the result to the dashboard, where operators can review context, inspect runbooks, and choose approved remediation actions.
5. Remediation is approval-gated and executed through explicit backend policy, not autonomous AI action.

## Architecture

```text
Kubernetes Cluster
        |
Java Spring Boot Backend
 |-- Kubernetes signal collection
 |-- Incident workflow engine
 |-- Runtime memory graph
 |-- Remediation policy and execution
 |-- Platform telemetry APIs
        |
Python FastAPI + LangGraph AI Service
 |-- Deterministic reasoning graph
 |-- Operational memory retrieval
 |-- OpenAI or Ollama model access
        |
React Dashboard
 |-- Cluster overview
 |-- Incident investigation
 |-- Remediation planning
 |-- Live kubectl terminal
```

## Benefits

- Reduces incident triage time by combining logs, rollout state, events, and prior memory in one workflow.
- Keeps remediation controlled by explicit approval and backend policy.
- Gives a clear operator view of whether the cluster is healthy, serving traffic, or stuck.
- Reuses prior incidents through operational memory instead of starting from zero every time.
- Works locally with OpenSearch or Neo4j and can be deployed in Kubernetes.

## Repository Layout

```text
aegis-core/
  backend/     Java 21 Spring Boot platform backend
  ai-service/  Python FastAPI + LangGraph reasoning service
  frontend/    React dashboard
  infra/       Local infrastructure notes and compose services
  k8s/         Kubernetes manifests and Kustomize deployment
  docs/        Architecture notes
```

## Prerequisites

- Java 21
- Node.js and npm
- Python 3.11+
- Docker, optional for image builds and local infrastructure
- kubectl and Kind, optional for local Kubernetes testing
- OpenAI API key or Ollama for AI reasoning

## Install

Clone the repo and install the frontend dependencies:

```bash
git clone <repo-url>
cd Aegis
cd aegis-core/frontend
npm install
```

Set up the Python service:

```bash
cd ../ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Run Locally

Start the backend from the repository root:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :aegis-core:backend:bootRun
```

Start the AI service:

```bash
cd aegis-core/ai-service
source .venv/bin/activate
uvicorn app.main:app --reload --port 8090
```

Start the frontend:

```bash
cd aegis-core/frontend
npm run dev
```

Default local endpoints:

- Frontend: `http://localhost:5173`
- Backend health: `http://localhost:8080/actuator/health`
- AI service health: `http://localhost:8090/health`

## Configure AI

The AI service reads environment variables from the shell or from `aegis-core/ai-service/.env.local` when using the developer script.

For Ollama:

```env
AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=qwen2.5:1.5b
```

For OpenAI:

```env
AI_PROVIDER=openai
OPENAI_API_KEY=your_api_key_here
OPENAI_MODEL=gpt-4.1-mini
```

If `AI_PROVIDER=auto`, Aegis uses OpenAI when `OPENAI_API_KEY` is set and falls back to Ollama otherwise.

## Use It

The dashboard flow is:

1. Open the frontend.
2. Review workload readiness, running pods, warning events, namespace risk, and traffic status.
3. Use the overview, workloads, events, and AI tabs to inspect the cluster.
4. Select a deployment or pod to inspect rollout detail.
5. Run RCA from the AI tab or launch a remediation candidate from the suggested actions.
6. Confirm any remediation action explicitly before execution.

There is also a read-only kubectl terminal in the dashboard for quick cluster checks.

## Useful Commands

Run backend tests:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test
```

Build the frontend:

```bash
cd aegis-core/frontend
npm run build
```

Build the frontend for GitHub Pages:

```bash
cd aegis-core/frontend
npm run build:pages
```

The GitHub Pages deployment publishes the static frontend to:

```text
https://tanvisaxena1901.github.io/Aegis-App/
```

The hosted frontend still expects a reachable Aegis backend for live `/api` features.

To make the GitHub Pages UI fully functional:

1. Deploy backend publicly.
2. Deploy AI service publicly or alongside backend.
3. Set backend `AEGIS_AI_SERVICE_URL`.
4. Add `VITE_API_BASE_URL` to the GitHub Pages frontend build.
5. Enable CORS for the GitHub Pages origin.
6. Redeploy GitHub Pages.

Compile-check the AI service:

```bash
cd aegis-core/ai-service
.venv/bin/python -m compileall app
```

## Kubernetes Deployment

Build images from the repository root:

```bash
docker build -f aegis-core/ai-service/Dockerfile -t aegis-ai-service:0.2.0 aegis-core/ai-service
docker build -f aegis-core/backend/Dockerfile -t aegis-backend:0.2.0 .
docker build -f aegis-core/frontend/Dockerfile -t aegis-frontend:0.2.0 .
```

Deploy with Kustomize:

```bash
kubectl apply -k aegis-core/k8s
kubectl get pods -n aegis
```

See `aegis-core/k8s/README.md` for Kind image loading, secrets, ingress, and port-forwarding details.

## Documentation

- `aegis-core/README.md`: Aegis Core overview and quickstart.
- `aegis-core/docs/architecture.md`: architecture notes.
- `aegis-core/infra/README.md`: local infrastructure setup.
- `aegis-core/k8s/README.md`: Kubernetes deployment guide.
