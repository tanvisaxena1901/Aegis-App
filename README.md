# Aegis

Aegis is an AI-native Kubernetes investigation and remediation platform. It combines a Spring Boot backend, a FastAPI/LangGraph reasoning service, and a React dashboard to help operators inspect cluster health, triage incidents, explain failures, and plan remediation steps.

## What It Does

- Watches Kubernetes resources and summarizes environment health.
- Surfaces deployments, pods, events, logs, and rollout status through backend APIs.
- Uses an isolated AI service for root-cause analysis and chat-based operational guidance.
- Supports OpenAI-backed reasoning or local Ollama fallback.
- Provides a React dashboard for incident investigation, remediation workflows, terminal actions, and cluster visibility.
- Includes Docker and Kubernetes manifests for local and in-cluster deployment.

## Repository Layout

```text
aegis-core/
  backend/     Java 21 Spring Boot platform backend
  ai-service/  Python FastAPI + LangGraph reasoning service
  frontend/    React dashboard
  infra/       Local infrastructure notes and compose services
  k8s/         Kubernetes manifests and Kustomize deployment
  docs/        Architecture notes
  scripts/     Local developer scripts
```

## Prerequisites

- Java 21
- Node.js and npm
- Python 3.11+
- Docker, optional for image builds
- kubectl and Kind, optional for local Kubernetes testing
- OpenAI API key or Ollama for AI reasoning

## Run Locally

Start the backend from the repository root:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :aegis-core:backend:bootRun
```

Start the AI service:

```bash
cd aegis-core/ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env.local
uvicorn app.main:app --reload --port 8090
```

Start the frontend:

```bash
cd aegis-core/frontend
npm install
npm run dev
```

Default local endpoints:

- Frontend: `http://localhost:5173`
- Backend health: `http://localhost:8080/actuator/health`
- AI service health: `http://localhost:8090/health`

## AI Configuration

The AI service reads environment variables from the shell or from `aegis-core/ai-service/.env.local` when using `aegis-core/scripts/dev-ai-service.sh`.

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
