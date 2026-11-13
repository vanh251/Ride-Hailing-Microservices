#!/bin/bash
# Fault and recovery tests of the payment flow (docs/timeline.md W5/W6). Each scenario publishes a
# TripCompleted fixture for a fresh trip and checks the result in payment-service and payment_db.
#
#   1. Payment Service DOWN  -> Kafka keeps the event -> restart -> the trip is charged
#   2. Duplicate event       -> same eventId twice + same trip with a new eventId -> charged once
#   3. payment-db DOWN       -> 3 retries -> trip-events.DLQ -> db back -> replay-dlq.sh -> charged
#
# Usage:   scripts/fault-tests.sh [1|2|3]      (no argument: all three)
# Needs:   kafka, payment-db and payment-service of docker-compose.yml running,
#          PAYMENT_FAILURE_MODE=none. The other services are not needed.
# Env:     PAYMENT_URL (default http://localhost:8086), WAIT_SECONDS (default 90)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8086}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"

uuid() {
  printf '%08x-%04x-4%03x-8%03x-%06x%06x\n' $((RANDOM * RANDOM)) $((RANDOM % 65536)) $((RANDOM % 4096)) \
    $((RANDOM % 4096)) $((RANDOM * RANDOM % 16777216)) $((RANDOM * RANDOM % 16777216))
}
fail() { echo "  ✘ $*"; echo "FAILED: scenario $SCENARIO"; exit 1; }
pass() { echo "  ✔ $*"; }
send() { scripts/kafka/send-event.sh TripCompleted "$@" >/dev/null || fail "could not publish TripCompleted"; }

payment_status() { curl -s "$PAYMENT_URL/api/payments/$1" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p'; }
payment_count() {
  docker compose exec -T payment-db psql -U "${POSTGRES_USER:-postgres}" -d payment_db -tAc \
    "select count(*) from payments where trip_id = '$1'"
}
dlq_size() {
  MSYS_NO_PATHCONV=1 docker exec "${KAFKA_CONTAINER:-kafka}" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic-partitions trip-events.DLQ 2>/dev/null | awk -F: '{sum += $3} END {print sum + 0}'
}
# wait_for <description> <command...> : polls until the command succeeds
wait_for() {
  local what="$1" deadline=$((SECONDS + WAIT_SECONDS)); shift
  until "$@"; do
    [ $SECONDS -lt $deadline ] || fail "$what not reached after ${WAIT_SECONDS}s"
    sleep 2
  done
}
is_paid() { [ "$(payment_status "$1")" = "SUCCESS" ]; }
payment_up() { curl -sf "$PAYMENT_URL/actuator/health" >/dev/null; }

scenario_1() {
  local trip; trip=$(uuid)
  echo "[1] Payment Service DOWN, trip $trip"
  docker compose stop payment-service >/dev/null 2>&1 || fail "could not stop payment-service"
  send --trip-id "$trip"; pass "TripCompleted published while payment-service is stopped"
  docker compose start payment-service >/dev/null 2>&1 || fail "could not start payment-service"
  wait_for "payment SUCCESS" is_paid "$trip"
  pass "after restart the consumer received the event and charged the trip"
}

scenario_2() {
  local trip event; trip=$(uuid); event=$(uuid)
  echo "[2] Duplicate event, trip $trip"
  send --trip-id "$trip" --event-id "$event"
  send --trip-id "$trip" --event-id "$event"
  send --trip-id "$trip"
  wait_for "payment SUCCESS" is_paid "$trip"
  sleep 5   # let the two duplicates be consumed too
  [ "$(payment_count "$trip")" = "1" ] || fail "trip has $(payment_count "$trip") payments, expected 1"
  pass "3 TripCompleted events, 1 payment"
}

scenario_3() {
  local trip before; trip=$(uuid); before=$(dlq_size)
  echo "[3] payment-db DOWN, trip $trip"
  docker compose stop payment-db >/dev/null 2>&1 || fail "could not stop payment-db"
  send --trip-id "$trip"
  dlq_grew() { [ "$(dlq_size)" -gt "$before" ]; }
  wait_for "event in trip-events.DLQ" dlq_grew
  pass "retries exhausted, event moved to trip-events.DLQ"
  docker compose start payment-db >/dev/null 2>&1 || fail "could not start payment-db"
  db_up() { docker compose exec -T payment-db pg_isready -q -U "${POSTGRES_USER:-postgres}" -d payment_db; }
  wait_for "payment-db ready" db_up
  wait_for "payment-service healthy" payment_up
  scripts/kafka/replay-dlq.sh trip-events "$trip" || fail "replay failed"
  wait_for "payment SUCCESS" is_paid "$trip"
  pass "replayed event charged the trip"
}

payment_up || { echo "payment-service is not reachable at $PAYMENT_URL"; exit 1; }
for SCENARIO in ${1:-1 2 3}; do
  "scenario_$SCENARIO"
done
echo "FAULT TESTS PASSED"
