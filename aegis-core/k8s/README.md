# Kubernetes

This folder deploys Aegis Core in-cluster with Kustomize.

## Resources

- `namespace.yaml`: creates the `aegis` namespace.
- `aegis-readonly-rbac.yaml`: creates the `aegis-core` service account and read-only cluster permissions.
- `config.yaml`: shared runtime configuration.
- `ai-service.yaml`: Python FastAPI/LangGraph deployment and service.
- `backend.yaml`: Spring Boot backend deployment and service, wired to the `aegis-core` service account.
- `frontend.yaml`: React/nginx frontend deployment and service.
- `ingress.yaml`: optional host-based ingress for `aegis.local`.

## Build Images

From the repository root:

```bash
docker build -f aegis-core/ai-service/Dockerfile -t aegis-ai-service:0.2.0 aegis-core/ai-service
docker build -f aegis-core/backend/Dockerfile -t aegis-backend:0.2.0 .
docker build -f aegis-core/frontend/Dockerfile -t aegis-frontend:0.2.0 .
```

For Kind, load the images:

```bash
kind load docker-image aegis-ai-service:0.2.0
kind load docker-image aegis-backend:0.2.0
kind load docker-image aegis-frontend:0.2.0
```

## Deploy

For OpenAI-backed chat, create the optional secret after the `aegis` namespace exists:

```bash
kubectl apply -f aegis-core/k8s/namespace.yaml
kubectl create secret generic aegis-openai -n aegis --from-literal=OPENAI_API_KEY=your_api_key_here
```

```bash
kubectl apply -k aegis-core/k8s
kubectl get pods -n aegis
```

## Access

Without ingress:

```bash
kubectl port-forward -n aegis svc/aegis-frontend 8088:80
```

Then open `http://localhost:8088`.

With ingress, point `aegis.local` at your ingress controller address and open `http://aegis.local`.
