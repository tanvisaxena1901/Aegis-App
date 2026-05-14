# Local Infrastructure

Phase 1 has no mandatory database and no Kafka.

Useful local services:

- Kind for a local Kubernetes cluster.
- Ollama for local model execution.
- Prometheus and Fluent Bit in Phase 2.

Create a local cluster:

```bash
kind create cluster --name aegis
kubectl config use-context kind-aegis
```

Run Ollama separately:

```bash
ollama pull qwen2.5
ollama run qwen2.5
```
