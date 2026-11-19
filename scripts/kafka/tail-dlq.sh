#!/bin/bash
# Prints the messages of the dead letter topics (docs/events.md §6), one line per message,
# so a member can see which event a consumer rejected and why.
#
# Usage:
#   scripts/kafka/tail-dlq.sh                 # all four DLQ topics, from the beginning
#   scripts/kafka/tail-dlq.sh trip-events     # only trip-events.DLQ
#   FOLLOW=true scripts/kafka/tail-dlq.sh     # keep waiting for new messages (Ctrl+C to stop)
set -euo pipefail

KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
FOLLOW="${FOLLOW:-false}"
TOPICS=("${1:-trip-events}" )
[ $# -eq 0 ] && TOPICS=(trip-events matching-events payment-events driver-events)

for topic in "${TOPICS[@]}"; do
  dlq="${topic%.DLQ}.DLQ"
  echo "== $dlq"
  args=(--bootstrap-server localhost:9092 --topic "$dlq" --from-beginning
        --property print.key=true --property print.timestamp=true --property print.headers=true)
  $FOLLOW || args+=(--timeout-ms 5000)
  MSYS_NO_PATHCONV=1 docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh "${args[@]}" 2>/dev/null || true
done
