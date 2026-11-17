#!/bin/bash
# Creates the Kafka topics of docs/events.md §3 and raises the partition count of a topic
# that already exists with fewer partitions (e.g. one auto-created with the broker default of 1).
# Run by the kafka-init service in docker-compose.yml; safe to run again.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"
RETENTION_MS=604800000   # 7 days

# topic:partitions
TOPICS=(
  "trip-events:3"
  "matching-events:3"
  "payment-events:3"
  "trip-events.DLQ:1"
  "matching-events.DLQ:1"
  "payment-events.DLQ:1"
)

for entry in "${TOPICS[@]}"; do
  topic="${entry%%:*}"
  partitions="${entry##*:}"

  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 \
    --config "retention.ms=$RETENTION_MS"

  current=$("$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe --topic "$topic" \
    | sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p' | head -1)
  if [ "${current:-0}" -lt "$partitions" ]; then
    echo "Raising $topic from $current to $partitions partitions"
    "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --alter --topic "$topic" --partitions "$partitions"
  fi

  # --create sets retention only on new topics; apply it to topics that already existed too
  "$KAFKA_BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name "$topic" --add-config "retention.ms=$RETENTION_MS" >/dev/null
done

echo "Kafka topics ready:"
"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe | grep "PartitionCount"
