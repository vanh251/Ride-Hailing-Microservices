#!/bin/bash
# Builds and starts the whole system, then waits until every service is healthy.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || cp .env.example .env
docker compose up -d --build --wait
docker compose ps
