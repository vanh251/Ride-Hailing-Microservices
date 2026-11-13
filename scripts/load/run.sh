#!/bin/bash
# Runs scripts/load/load-test.js with 10, 50, 100 and 500 virtual users (docs/timeline.md W6) and
# prints throughput (http_reqs), P50/P95 (http_req_duration), error rate (http_req_failed) per stage,
# plus CPU and memory of the containers in the middle of each stage.
#
# Usage:   scripts/load/run.sh [vus...]        e.g. scripts/load/run.sh 10 50
# Needs:   pricing-service, payment-service, payment-db, kafka of docker-compose.yml running,
#          PAYMENT_FAILURE_MODE=none. k6 runs from its Docker image, nothing to install.
# Env:     DURATION per stage (default 30s)
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
DURATION="${DURATION:-30s}"
CONTAINERS="${CONTAINERS:-pricing-service payment-service notification-service payment-db kafka}"

for vus in ${@:-10 50 100 500}; do
  echo "================ $vus users, $DURATION ================"
  (sleep $(( ${DURATION%s} / 2 )); docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' $CONTAINERS) &
  docker run --rm -i --network ride-hailing-network -e VUS="$vus" -e DURATION="$DURATION" \
    grafana/k6:0.54.0 run --quiet - < "$DIR/load-test.js"
  wait
done
