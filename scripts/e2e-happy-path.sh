#!/bin/bash
# End-to-end Happy Path of W3 (docs/timeline.md §5), run through the API Gateway:
#   Register → Login → Driver AVAILABLE → Passenger creates Trip → Matching → ASSIGNING
#   → DriverAssignmentConfirmed → ASSIGNED → Driver Accept → Start Trip → Complete Trip
#   → Calculate Fare → Payment → Notification  (W5 saga, docs/events.md §9)
# Every step checks its result and the script stops at the first failure, saying which step broke.
#
# Usage:   scripts/e2e-happy-path.sh
# Needs:   docker compose up -d (all services healthy), curl. Works in Git Bash, bash and zsh.
# Env:     GATEWAY_URL (default http://localhost:8080), WAIT_SECONDS (default 30)
set -uo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
WAIT_SECONDS="${WAIT_SECONDS:-30}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
PASSWORD="E2e@123456"
RUN_ID="$(date +%s)$RANDOM"
CORRELATION_HEX="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
CORRELATION_ID="${CORRELATION_HEX:0:8}-${CORRELATION_HEX:8:4}-4${CORRELATION_HEX:13:3}-8${CORRELATION_HEX:17:3}-${CORRELATION_HEX:20:12}"

# Keep test runs in separate 0.2-degree cells, away from the seeded Hanoi drivers.
# This prevents an AVAILABLE driver left by an earlier run from winning the new match.
CELL=$((RUN_ID % 400))
PICKUP_LAT=$(awk -v cell="$CELL" 'BEGIN {printf "%.4f", 9 + int(cell / 20) * 0.2}')
PICKUP_LNG=$(awk -v cell="$CELL" 'BEGIN {printf "%.4f", 105 + (cell % 20) * 0.2}')
DEST_LAT=$(awk -v lat="$PICKUP_LAT" 'BEGIN {printf "%.4f", lat + 0.02}')
DEST_LNG=$(awk -v lng="$PICKUP_LNG" 'BEGIN {printf "%.4f", lng + 0.02}')
DRIVER_LAT=$(awk -v lat="$PICKUP_LAT" 'BEGIN {printf "%.4f", lat + 0.0003}')
DRIVER_LNG=$(awk -v lng="$PICKUP_LNG" 'BEGIN {printf "%.4f", lng + 0.0003}')
PICKUP="{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG,\"address\":\"E2E pickup\"}"
DESTINATION="{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG,\"address\":\"E2E destination\"}"

STEP=0
pass() { echo "  ✔ $*"; }
fail() { echo "  ✘ $*"; echo; echo "FAILED at step $STEP. Last response:"; echo "$BODY"; exit 1; }
step() { STEP=$((STEP + 1)); echo; echo "[$STEP] $*"; }

# json <field> : first string or number value of a field in $BODY
json() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -1; }

# call <METHOD> <path> [body] [token] : sets BODY and STATUS
call() {
  local method="$1" path="$2" data="${3:-}" token="${4:-}"
  local args=(-s -o /tmp/e2e-body.$$ -w '%{http_code}' -X "$method" "$GATEWAY$path"
              -H 'Content-Type: application/json' -H "X-Correlation-ID: $CORRELATION_ID")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-d "$data")
  STATUS=$(curl "${args[@]}")
  BODY=$(cat /tmp/e2e-body.$$ 2>/dev/null); rm -f /tmp/e2e-body.$$
}

expect_status() { [ "$STATUS" = "$1" ] || fail "expected HTTP $1, got $STATUS"; }

# wait_trip_status <status> : polls GET /api/trips/{id} until the trip reaches the status;
# SEEN_STATUSES keeps every status observed on the way
SEEN_STATUSES=""
wait_trip_status() {
  local deadline=$((SECONDS + WAIT_SECONDS)) status
  while [ $SECONDS -lt $deadline ]; do
    call GET "/api/trips/$TRIP_ID" "" "$PASSENGER_TOKEN"
    status=$(json status)
    case " $SEEN_STATUSES " in *" $status "*) ;; *) SEEN_STATUSES="$SEEN_STATUSES $status" ;; esac
    [ "$status" = "$1" ] && return 0
    sleep 0.2
  done
  fail "trip is still $(json status) after ${WAIT_SECONDS}s, expected $1"
}

dlq_size() {
  MSYS_NO_PATHCONV=1 docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 --topic-partitions 'trip-events.DLQ,matching-events.DLQ,payment-events.DLQ,driver-events.DLQ' 2>/dev/null \
    | awk -F: '{sum += $3} END {print sum + 0}'
}

echo "E2E Happy Path against $GATEWAY (correlationId=$CORRELATION_ID)"
DLQ_BEFORE=$(dlq_size)

step "Gateway is up"
call GET /actuator/health
expect_status 200; pass "gateway healthy"

step "Register a passenger and a driver"
PASSENGER_EMAIL="e2e.passenger.$RUN_ID@ds03.test"
call POST /api/auth/register/passenger "{\"fullName\":\"E2E Passenger\",\"email\":\"$PASSENGER_EMAIL\",\"password\":\"$PASSWORD\"}"
expect_status 201; pass "passenger $PASSENGER_EMAIL"
DRIVER_EMAIL="e2e.driver.$RUN_ID@ds03.test"
PLATE="51F-$(printf '%05d' $((RANDOM % 100000)))"
LICENSE="$(printf '%06d%06d' $((RANDOM % 1000000)) $((RANDOM % 1000000)))"
call POST /api/auth/register/driver "{\"fullName\":\"E2E Driver\",\"email\":\"$DRIVER_EMAIL\",\"password\":\"$PASSWORD\",\"licenseNumber\":\"$LICENSE\",\"vehicle\":{\"licensePlate\":\"$PLATE\",\"make\":\"Toyota\",\"model\":\"Vios\",\"color\":\"White\",\"year\":2022,\"vehicleType\":\"SEDAN\"}}"
expect_status 201; pass "driver $DRIVER_EMAIL, plate $PLATE"

step "Login"
call POST /api/auth/login "{\"email\":\"$PASSENGER_EMAIL\",\"password\":\"$PASSWORD\"}"
expect_status 200; PASSENGER_TOKEN=$(json accessToken); PASSENGER_ID=$(json userId)
[ -n "$PASSENGER_TOKEN" ] || fail "no accessToken"; pass "passenger $PASSENGER_ID"
call POST /api/auth/login "{\"email\":\"$DRIVER_EMAIL\",\"password\":\"$PASSWORD\"}"
expect_status 200; DRIVER_TOKEN=$(json accessToken); DRIVER_ID=$(json userId)
[ -n "$DRIVER_TOKEN" ] || fail "no accessToken"; pass "driver $DRIVER_ID"

step "Driver goes AVAILABLE near the pickup"
call PATCH "/api/drivers/$DRIVER_ID/location" "{\"latitude\":$DRIVER_LAT,\"longitude\":$DRIVER_LNG}" "$DRIVER_TOKEN"
expect_status 200; pass "location reported"
call PATCH "/api/drivers/$DRIVER_ID/status" '{"status":"AVAILABLE"}' "$DRIVER_TOKEN"
expect_status 200; [ "$(json status)" = "AVAILABLE" ] || fail "driver status is $(json status)"; pass "driver AVAILABLE"

step "Passenger creates a trip"
call POST /api/trips "{\"pickup\":$PICKUP,\"destination\":$DESTINATION}" "$PASSENGER_TOKEN"
expect_status 201; TRIP_ID=$(json tripId)
[ -n "$TRIP_ID" ] || fail "no tripId"
[ "$(json passengerId)" = "$PASSENGER_ID" ] || fail "trip passengerId $(json passengerId) is not the logged-in passenger $PASSENGER_ID (gateway X-User-Id)"
pass "trip $TRIP_ID, estimatedFare $(json estimatedFare)"

step "Matching reserves the driver, Driver confirms (trip ASSIGNING -> ASSIGNED)"
wait_trip_status ASSIGNED
[ "$(json driverId)" = "$DRIVER_ID" ] || fail "assigned driver $(json driverId), expected $DRIVER_ID"
case "$SEEN_STATUSES" in
  *ASSIGNING*) pass "trip went through ASSIGNING ($SEEN_STATUSES )" ;;
  *) pass "ASSIGNING lasted less than one poll (seen:$SEEN_STATUSES )" ;;
esac
# ASSIGNED is only accepted when driver-service published the confirmation of this trip
CONFIRMATION=$(MSYS_NO_PATHCONV=1 docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh   --bootstrap-server localhost:9092 --topic driver-events --from-beginning --timeout-ms 5000 2>/dev/null   | grep "\"tripId\":\"$TRIP_ID\"" | grep '"eventType":"DriverAssignment' || true)
BODY="$CONFIRMATION"
printf '%s' "$CONFIRMATION" | grep -q '"eventType":"DriverAssignmentConfirmed"' || fail "no DriverAssignmentConfirmed on driver-events for the trip"
pass "DriverAssignmentConfirmed eventId $(json eventId), driver $DRIVER_ID assigned"
call GET "/api/drivers/$DRIVER_ID" "" "$DRIVER_TOKEN"
[ "$(json status)" = "BUSY" ] || fail "driver status is $(json status), expected BUSY after DriverAssignmentConfirmed"
pass "driver BUSY"

step "Driver accepts, starts and completes the trip"
call POST "/api/trips/$TRIP_ID/accept" "" "$DRIVER_TOKEN"; expect_status 200; pass "accepted"
call POST "/api/trips/$TRIP_ID/start" "" "$DRIVER_TOKEN"; expect_status 200
[ "$(json status)" = "IN_PROGRESS" ] || fail "status $(json status)"; pass "IN_PROGRESS"
call POST "/api/trips/$TRIP_ID/complete" "" "$DRIVER_TOKEN"; expect_status 200
[ "$(json status)" = "COMPLETED" ] || fail "status $(json status)"
FINAL_FARE=$(json finalFare); [ -n "$FINAL_FARE" ] || fail "no finalFare"
pass "COMPLETED, finalFare $FINAL_FARE (Pricing /final)"

step "Payment charges the trip (trip PAID)"
wait_trip_status PAID
pass "trip PAID"
call GET "/api/payments/$TRIP_ID" "" "$PASSENGER_TOKEN"
expect_status 200; [ "$(json status)" = "SUCCESS" ] || fail "payment status $(json status)"
awk -v a="$(json amount)" -v b="$FINAL_FARE" 'BEGIN {exit !(a + 0 == b + 0)}' || fail "charged $(json amount), finalFare was $FINAL_FARE"
pass "payment SUCCESS, amount $(json amount)"

step "Driver is released (AVAILABLE)"
call GET "/api/drivers/$DRIVER_ID" "" "$DRIVER_TOKEN"
[ "$(json status)" = "AVAILABLE" ] || fail "driver status is $(json status), expected AVAILABLE after TripCompleted"
pass "driver AVAILABLE"

step "Passenger and driver are notified"
call GET "/api/notifications/trip/$TRIP_ID" "" "$PASSENGER_TOKEN"
expect_status 200
for title in "Ride Requested" "Driver Assigned" "Trip Started" "Trip Completed" "Payment Succeeded"; do
  printf '%s' "$BODY" | grep -q "\"recipientType\":\"PASSENGER\",\"recipientId\":\"$PASSENGER_ID\",\"tripId\":\"$TRIP_ID\",\"title\":\"$title\"" \
    || fail "passenger did not get \"$title\""
done
pass "passenger: Ride Requested, Driver Assigned, Trip Started, Trip Completed, Payment Succeeded"
for title in "New Trip Assigned" "Payment Received"; do
  printf '%s' "$BODY" | grep -q "\"recipientType\":\"DRIVER\",\"recipientId\":\"$DRIVER_ID\",\"tripId\":\"$TRIP_ID\",\"title\":\"$title\"" \
    || fail "driver did not get \"$title\""
done
pass "driver: New Trip Assigned, Payment Received"

printf '%s' "$BODY" | grep -q '"title":"Driver Reserved"' && fail "a tentative reservation was notified as an assignment"
pass "no notification before the confirmation"

step "No event was rejected"
DLQ_AFTER=$(dlq_size)
BODY="DLQ messages before=$DLQ_BEFORE after=$DLQ_AFTER (scripts/kafka/tail-dlq.sh shows them)"
[ "$DLQ_AFTER" = "$DLQ_BEFORE" ] || fail "$((DLQ_AFTER - DLQ_BEFORE)) new DLQ message(s)"
pass "no new DLQ message"

echo
echo "HAPPY PATH PASSED: trip $TRIP_ID, passenger $PASSENGER_ID, driver $DRIVER_ID, fare $FINAL_FARE VND"
