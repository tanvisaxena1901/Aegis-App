#!/usr/bin/env bash
set -euo pipefail

docker compose -f aegis-core/infra/docker-compose.yml up -d postgres
./gradlew :aegis-core:backend:bootRun
