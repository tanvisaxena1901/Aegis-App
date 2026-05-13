# Local Infrastructure

Phase 1 only requires PostgreSQL.

```bash
docker compose -f aegis-core/infra/docker-compose.yml up -d postgres
```

Kafka and Redis are listed behind the `phase-2` profile so the future event system has a clear home without forcing extra services during the foundation phase.

```bash
docker compose -f aegis-core/infra/docker-compose.yml --profile phase-2 up -d
```
