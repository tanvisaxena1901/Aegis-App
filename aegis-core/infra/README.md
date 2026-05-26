# Local Infrastructure

Phase 1 has no mandatory database and no Kafka.

Useful local services:

- Kind for a local Kubernetes cluster.
- Ollama for local model execution.
- OpenSearch for runtime memory search.
- Prometheus and Fluent Bit in Phase 2.

Create a local cluster:

```bash
kind create cluster --name aegis
kubectl config use-context kind-aegis
```

Run Ollama separately:

```bash
ollama pull qwen2.5:1.5b
ollama run qwen2.5:1.5b
```

Run local OpenSearch for runtime memory:

```bash
cd aegis-core/infra
docker compose --profile search up -d opensearch
```

Then start the backend with:

```bash
AEGIS_RUNTIME_MEMORY_GRAPH=opensearch \
AEGIS_OPENSEARCH_BASE_URL=http://127.0.0.1:9200 \
./aegis-core/scripts/dev-backend.sh
```
