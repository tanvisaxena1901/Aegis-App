#!/usr/bin/env bash
set -euo pipefail

cd aegis-core/ai-service
if [[ -f .env.local ]]; then
  set -a
  source .env.local
  set +a
fi
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8090
