#!/bin/bash
# Message recovery (docs/events.md §6): re-publishes the original events of a DLQ topic to the topic
# they came from, with the same eventId. Consumers that already processed an event drop it through
# processed_events, so replaying twice is safe. The DLQ messages themselves are kept.
#
# Usage:
#   scripts/kafka/replay-dlq.sh <topic> [filter]
#     <topic>   trip-events | matching-events | payment-events (the .DLQ suffix is optional)
#     [filter]  only replay DLQ messages containing this text, e.g. a tripId or an eventId
#   DRY_RUN=true scripts/kafka/replay-dlq.sh trip-events    # print what would be sent
#
# Needs the kafka container of docker-compose.yml to be running.
set -euo pipefail

[ $# -ge 1 ] || { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
TOPIC="${1%.DLQ}"
FILTER="${2:-}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"

kafka() { MSYS_NO_PATHCONV=1 docker exec -i "$KAFKA_CONTAINER" "/opt/kafka/bin/$1" --bootstrap-server localhost:9092 "${@:2}"; }

# One line per DLQ message: <key>|<DeadLetterMessage JSON>. The consumer exits non-zero on its timeout.
DLQ=$(kafka kafka-console-consumer.sh --topic "$TOPIC.DLQ" --from-beginning --timeout-ms 5000 \
  --property print.key=true --property 'key.separator=|' 2>/dev/null || true)

# shortcut: originalMessage is cut out with sed, relying on the field order of DeadLetterMessage
# (originalMessage, then error); use jq if that class changes. Non-JSON originals are skipped.
EVENTS=$(printf '%s\n' "$DLQ" | grep -F -- "$FILTER" \
  | sed -n 's/^\([^|]*\)|.*"originalMessage":\({.*}\),"error":".*/\1|\2/p' || true)

if [ -z "$EVENTS" ]; then
  echo "Nothing to replay from $TOPIC.DLQ${FILTER:+ matching '$FILTER'}"
  exit 0
fi
COUNT=$(printf '%s\n' "$EVENTS" | wc -l | tr -d ' ')

if [ "${DRY_RUN:-false}" = "true" ]; then
  printf '%s\n' "$EVENTS"
  echo "Would replay $COUNT event(s) to $TOPIC"
  exit 0
fi

printf '%s\n' "$EVENTS" | kafka kafka-console-producer.sh --topic "$TOPIC" \
  --property parse.key=true --property 'key.separator=|' >/dev/null
echo "Replayed $COUNT event(s) from $TOPIC.DLQ to $TOPIC"
