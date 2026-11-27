#!/bin/bash
# Publishes one of the shared event fixtures (tests/data/events/<EventType>.json) to Kafka,
# as if the producing service had sent it. Lets a member test a consumer without the other service.
#
# Usage:
#   scripts/kafka/send-event.sh <EventType> [options]
#
# Options (every value defaults to the fixture's, except --event-id which is always fresh):
#   --trip-id <uuid>         tripId / aggregateId of a trip event; also the Kafka key
#   --passenger-id <uuid>
#   --driver-id <uuid>
#   --payment-id <uuid>
#   --event-id <uuid>        reuse an id to test deduplication
#   --causation-id <uuid>
#   --correlation-id <id>
#   --dry-run                print the message instead of sending it
#
# Examples:
#   scripts/kafka/send-event.sh TripCompleted --trip-id 33333333-3333-4333-8333-333333333333
#   scripts/kafka/send-event.sh PaymentSucceeded --trip-id "$TRIP" --causation-id "$TRIP_COMPLETED_EVENT_ID"
#
# Needs the kafka container of docker-compose.yml to be running.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIXTURES="$ROOT/tests/data/events"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"

# Ids used in the fixtures (docs/events.md examples)
FIXTURE_TRIP_ID="550e8400-e29b-41d4-a716-446655440000"
FIXTURE_PASSENGER_ID="a0000000-0000-4000-8000-000000000001"
FIXTURE_DRIVER_ID="d0000000-0000-4000-8000-000000000001"
FIXTURE_PAYMENT_ID="9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b"
FIXTURE_CORRELATION_ID="f47ac10b-58cc-4372-a567-0e02b2c3d479"

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr 'A-Z' 'a-z'
  else
    local h
    h=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    # version 4, RFC 4122 variant
    printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $(( (0x${h:16:1} & 0x3) | 0x8 )) "${h:17:3}" "${h:20:12}"
  fi
}

[ $# -ge 1 ] || usage
EVENT_TYPE="$1"; shift
FIXTURE="$FIXTURES/$EVENT_TYPE.json"
[ -f "$FIXTURE" ] || { echo "No fixture for event type '$EVENT_TYPE' in $FIXTURES" >&2; exit 1; }

case "$EVENT_TYPE" in
  RideRequested|DriverAssigned|TripStarted|TripCompleted|TripCancelled) TOPIC="trip-events" ;;
  DriverReserved) TOPIC="matching-events" ;;
  PaymentSucceeded|PaymentFailed) TOPIC="payment-events" ;;
  DriverAssignmentConfirmed|DriverAssignmentRejected) TOPIC="driver-events" ;;
  *) echo "Unknown event type '$EVENT_TYPE' (docs/events.md §4)" >&2; exit 1 ;;
esac

TRIP_ID="$FIXTURE_TRIP_ID"; PASSENGER_ID="$FIXTURE_PASSENGER_ID"; DRIVER_ID="$FIXTURE_DRIVER_ID"
PAYMENT_ID="$FIXTURE_PAYMENT_ID"; CORRELATION_ID="$FIXTURE_CORRELATION_ID"
EVENT_ID="$(new_uuid)"; CAUSATION_ID=""; DRY_RUN=false

while [ $# -gt 0 ]; do
  case "$1" in
    --trip-id) TRIP_ID="$2"; shift 2 ;;
    --passenger-id) PASSENGER_ID="$2"; shift 2 ;;
    --driver-id) DRIVER_ID="$2"; shift 2 ;;
    --payment-id) PAYMENT_ID="$2"; shift 2 ;;
    --event-id) EVENT_ID="$2"; shift 2 ;;
    --causation-id) CAUSATION_ID="$2"; shift 2 ;;
    --correlation-id) CORRELATION_ID="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown option $1" >&2; usage ;;
  esac
done

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
MESSAGE=$(tr -d '\r' < "$FIXTURE" | sed \
  -e "s/$FIXTURE_TRIP_ID/$TRIP_ID/g" \
  -e "s/$FIXTURE_PASSENGER_ID/$PASSENGER_ID/g" \
  -e "s/$FIXTURE_DRIVER_ID/$DRIVER_ID/g" \
  -e "s/$FIXTURE_PAYMENT_ID/$PAYMENT_ID/g" \
  -e "s/$FIXTURE_CORRELATION_ID/$CORRELATION_ID/g" \
  -e "s/\"eventId\": \"[^\"]*\"/\"eventId\": \"$EVENT_ID\"/" \
  -e "s/\"occurredAt\": \"[^\"]*\"/\"occurredAt\": \"$NOW\"/" \
  | tr '\n' ' ' | sed 's/  */ /g')
if [ -n "$CAUSATION_ID" ]; then
  MESSAGE=$(printf '%s' "$MESSAGE" | sed -E "s/\"causationId\": (null|\"[^\"]*\")/\"causationId\": \"$CAUSATION_ID\"/")
fi

if $DRY_RUN; then
  echo "topic=$TOPIC key=$TRIP_ID"
  echo "$MESSAGE"
  exit 0
fi

# MSYS_NO_PATHCONV keeps Git Bash on Windows from rewriting the container path
printf '%s|%s\n' "$TRIP_ID" "$MESSAGE" | MSYS_NO_PATHCONV=1 docker exec -i "$KAFKA_CONTAINER" \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$TOPIC" \
  --property parse.key=true --property 'key.separator=|' >/dev/null

echo "Sent $EVENT_TYPE to $TOPIC: eventId=$EVENT_ID tripId=$TRIP_ID correlationId=$CORRELATION_ID"
