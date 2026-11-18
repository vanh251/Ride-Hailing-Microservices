#!/usr/bin/env bash
# W4 demo: two passengers create trips together through the Gateway; only one owns the driver.
# Run from the repository root with Docker Compose healthy. Requires Bash, curl, Docker Compose.
set -uo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
WAIT_SECONDS="${WAIT_SECONDS:-45}"
PASSWORD='E2e@123456'
BODY=''
STATUS=''
WORK=''

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  if [[ -n "$BODY" ]]; then printf 'Last response: %s\n' "$BODY" >&2; fi
  exit 1
}

new_uuid() {
  local hex
  hex="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
  printf '%s-%s-4%s-8%s-%s\n' "${hex:0:8}" "${hex:8:4}" "${hex:13:3}" "${hex:17:3}" "${hex:20:12}"
}

# The Gateway responses used here are flat for the selected fields. Reject broken envelopes.
json_field() {
  local body="$1" key="$2" value
  [[ "$body" =~ ^\{.*\}$ ]] || return 1
  value="$(printf '%s' "$body" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p")"
  [[ -n "$value" ]] || value="$(printf '%s' "$body" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p")"
  [[ -n "$value" ]] || return 1
  printf '%s\n' "$value"
}

optional_driver_id() {
  local body="$1" value
  [[ "$body" =~ ^\{.*\}$ ]] || return 1
  if [[ "$body" =~ \"driverId\"[[:space:]]*:[[:space:]]*null ]]; then
    printf '\n'
    return 0
  fi
  if [[ "$body" == *'"driverId"'* ]]; then
    json_field "$body" driverId
    return $?
  fi
  printf '\n'
}

call() {
  local method="$1" path="$2" data="${3:-}" token="${4:-}" correlation="${5:-$(new_uuid)}"
  local args=(-sS --max-time 10 -o "$WORK/body" -w '%{http_code}' -X "$method" "$GATEWAY$path"
              -H 'Content-Type: application/json' -H "X-Correlation-ID: $correlation")
  [[ -z "$token" ]] || args+=(-H "Authorization: Bearer $token")
  [[ -z "$data" ]] || args+=(-d "$data")
  STATUS="$(curl "${args[@]}")" || fail "curl $method $path failed"
  BODY="$(cat "$WORK/body")"
}

expect_status() {
  [[ "$STATUS" == "$1" ]] || fail "expected HTTP $1, got $STATUS"
}

require_created() {
  local status="$1" body="$2" passenger_id="$3" trip_id actual_passenger
  [[ "$status" == 201 ]] || return 1
  trip_id="$(json_field "$body" tripId)" || return 1
  actual_passenger="$(json_field "$body" passengerId)" || return 1
  [[ "$actual_passenger" == "$passenger_id" ]] || return 1
  [[ "$(json_field "$body" status)" == SEARCHING ]] || return 1
  [[ "$trip_id" =~ ^[0-9a-fA-F-]{36}$ ]]
}

# Arguments are the two Trip snapshots, Driver/DB snapshot, IDs, and pre-race version.
validate_snapshot() {
  local a_status="$1" a_driver="$2" b_status="$3" b_driver="$4"
  local db_status="$5" db_owner="$6" db_version="$7"
  local trip_a="$8" trip_b="$9" driver="${10}" old_version="${11}" winner
  [[ "$db_version" =~ ^[0-9]+$ && "$old_version" =~ ^[0-9]+$ ]] || return 1
  (( db_version > old_version )) || return 1
  [[ "$db_status" == BUSY ]] || return 1
  if [[ "$a_status" == ASSIGNED && "$a_driver" == "$driver" && "$b_status" == SEARCHING && -z "$b_driver" ]]; then
    winner="$trip_a"
  elif [[ "$b_status" == ASSIGNED && "$b_driver" == "$driver" && "$a_status" == SEARCHING && -z "$a_driver" ]]; then
    winner="$trip_b"
  else
    return 1
  fi
  [[ "$db_owner" == "$winner" ]]
}

db_row() {
  local driver_id="$1" query
  [[ "$driver_id" =~ ^[0-9a-fA-F-]{36}$ ]] || return 1
  query="SELECT status, COALESCE(reserved_for_trip::text, ''), version FROM drivers WHERE driver_id = '$driver_id'"
  docker compose exec -T driver-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "$1"' sh "$query"
}

post_trip() {
  local name="$1" token="$2" payload="$3" correlation="$4" result=0
  : > "$WORK/$name.ready"
  while [[ ! -e "$WORK/go" ]]; do sleep 0.01; done
  curl -sS --max-time 10 -o "$WORK/$name.body" -w '%{http_code}' -X POST "$GATEWAY/api/trips" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
    -H "X-Correlation-ID: $correlation" -d "$payload" > "$WORK/$name.status" || result=$?
  printf '%s\n' "$result" > "$WORK/$name.exit"
  return "$result"
}

main() {
  local run_id cell lat lng dest_lat dest_lng driver_lat driver_lng pickup destination payload
  local candidate ids count passenger_a passenger_b token_a token_b driver_token driver_id
  local trip_a trip_b pid_a pid_b deadline a_status b_status a_driver b_driver driver_status
  local row db_status db_owner db_version old_version winner loser correlation_a correlation_b
  [[ "$WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail 'WAIT_SECONDS must be a positive integer'
  WORK="$(mktemp -d)" || fail 'cannot create temporary directory'
  trap 'rm -f -- "$WORK/body" "$WORK/a.ready" "$WORK/b.ready" "$WORK/go" "$WORK/a.body" "$WORK/b.body" "$WORK/a.status" "$WORK/b.status" "$WORK/a.exit" "$WORK/b.exit"; rmdir -- "$WORK"' EXIT
  run_id="$(date +%s)${RANDOM}"

  printf 'W4 Gateway concurrency demo (%s)\n' "$GATEWAY"
  call GET /actuator/health
  expect_status 200

  # 0.2-degree cells are much wider than Matching's 5 km search radius.
  for attempt in $(seq 1 20); do
    cell=$(( (RANDOM + run_id + attempt) % 400 ))
    lat="$(awk -v c="$cell" 'BEGIN {printf "%.4f", 9 + int(c / 20) * 0.2}')"
    lng="$(awk -v c="$cell" 'BEGIN {printf "%.4f", 105 + (c % 20) * 0.2}')"
    call GET "/api/drivers/available?latitude=$lat&longitude=$lng&radiusKm=5&size=50"
    expect_status 200
    [[ "$BODY" =~ ^\[.*\]$ ]] || fail 'available-driver response is not an array'
    [[ "$BODY" != '[]' ]] || break
  done
  [[ "$BODY" == '[]' ]] || fail 'could not find an empty driver search area'
  dest_lat="$(awk -v x="$lat" 'BEGIN {printf "%.4f", x + 0.02}')"
  dest_lng="$(awk -v x="$lng" 'BEGIN {printf "%.4f", x + 0.02}')"
  driver_lat="$(awk -v x="$lat" 'BEGIN {printf "%.4f", x + 0.0003}')"
  driver_lng="$(awk -v x="$lng" 'BEGIN {printf "%.4f", x + 0.0003}')"
  pickup="{\"latitude\":$lat,\"longitude\":$lng,\"address\":\"W4 pickup\"}"
  destination="{\"latitude\":$dest_lat,\"longitude\":$dest_lng,\"address\":\"W4 destination\"}"
  payload="{\"pickup\":$pickup,\"destination\":$destination}"
  printf 'Isolated pickup: %s, %s\n' "$lat" "$lng"

  for label in a b; do
    call POST /api/auth/register/passenger \
      "{\"fullName\":\"W4 Passenger $label\",\"email\":\"w4.passenger.$label.$run_id@ds03.test\",\"password\":\"$PASSWORD\"}"
    expect_status 201
    call POST /api/auth/login "{\"email\":\"w4.passenger.$label.$run_id@ds03.test\",\"password\":\"$PASSWORD\"}"
    expect_status 200
    if [[ "$label" == a ]]; then
      passenger_a="$(json_field "$BODY" userId)" || fail 'passenger A userId missing'
      token_a="$(json_field "$BODY" accessToken)" || fail 'passenger A token missing'
    else
      passenger_b="$(json_field "$BODY" userId)" || fail 'passenger B userId missing'
      token_b="$(json_field "$BODY" accessToken)" || fail 'passenger B token missing'
    fi
  done
  local plate license
  plate="51F-$(printf '%05d' $((RANDOM % 100000)))"
  license="$(printf '%06d%06d' $((RANDOM % 1000000)) $((RANDOM % 1000000)))"
  call POST /api/auth/register/driver \
    "{\"fullName\":\"W4 Driver\",\"email\":\"w4.driver.$run_id@ds03.test\",\"password\":\"$PASSWORD\",\"licenseNumber\":\"$license\",\"vehicle\":{\"licensePlate\":\"$plate\",\"make\":\"Toyota\",\"model\":\"Vios\",\"color\":\"White\",\"year\":2022,\"vehicleType\":\"SEDAN\"}}"
  expect_status 201
  call POST /api/auth/login "{\"email\":\"w4.driver.$run_id@ds03.test\",\"password\":\"$PASSWORD\"}"
  expect_status 200
  driver_id="$(json_field "$BODY" userId)" || fail 'driver userId missing'
  driver_token="$(json_field "$BODY" accessToken)" || fail 'driver token missing'
  call PATCH "/api/drivers/$driver_id/location" "{\"latitude\":$driver_lat,\"longitude\":$driver_lng}" "$driver_token"
  expect_status 200
  call PATCH "/api/drivers/$driver_id/status" '{"status":"AVAILABLE"}' "$driver_token"
  expect_status 200
  [[ "$(json_field "$BODY" status)" == AVAILABLE ]] || fail 'driver did not become AVAILABLE'

  call GET "/api/drivers/available?latitude=$lat&longitude=$lng&radiusKm=5&size=50"
  expect_status 200
  ids="$(printf '%s' "$BODY" | grep -oE '"id":"[0-9a-fA-F-]{36}"' || true)"
  count="$(printf '%s\n' "$ids" | sed '/^$/d' | wc -l | tr -d ' ')"
  [[ "$count" == 1 && "$ids" == "\"id\":\"$driver_id\"" ]] || fail 'demo driver is not the sole available candidate'
  row="$(db_row "$driver_id")" || fail 'cannot read driver database before the race'
  IFS='|' read -r db_status db_owner old_version <<< "$row"
  [[ "$db_status" == AVAILABLE && -z "$db_owner" && "$old_version" =~ ^[0-9]+$ ]] || fail "invalid pre-race DB row: $row"
  printf 'Sole candidate: driver=%s, version=%s\n' "$driver_id" "$old_version"

  correlation_a="$(new_uuid)"; correlation_b="$(new_uuid)"
  post_trip a "$token_a" "$payload" "$correlation_a" & pid_a=$!
  post_trip b "$token_b" "$payload" "$correlation_b" & pid_b=$!
  deadline=$((SECONDS + 5))
  while [[ (! -e "$WORK/a.ready" || ! -e "$WORK/b.ready") && $SECONDS -lt $deadline ]]; do sleep 0.01; done
  [[ -e "$WORK/a.ready" && -e "$WORK/b.ready" ]] || fail 'concurrent workers did not become ready'
  : > "$WORK/go"
  wait "$pid_a" || true
  wait "$pid_b" || true
  [[ "$(cat "$WORK/a.exit")" == 0 && "$(cat "$WORK/b.exit")" == 0 ]] || fail 'one trip POST failed at the transport layer'
  require_created "$(cat "$WORK/a.status")" "$(cat "$WORK/a.body")" "$passenger_a" || fail 'passenger A trip creation failed'
  require_created "$(cat "$WORK/b.status")" "$(cat "$WORK/b.body")" "$passenger_b" || fail 'passenger B trip creation failed'
  trip_a="$(json_field "$(cat "$WORK/a.body")" tripId)"
  trip_b="$(json_field "$(cat "$WORK/b.body")" tripId)"
  [[ "$trip_a" != "$trip_b" ]] || fail 'both requests returned the same trip ID'
  printf 'Both POSTs returned 201: trip A=%s, trip B=%s\n' "$trip_a" "$trip_b"

  deadline=$((SECONDS + WAIT_SECONDS))
  a_status='unread'; b_status='unread'; a_driver=''; b_driver=''; driver_status='unread'; db_status='unread'; db_owner=''; db_version=''
  while (( SECONDS < deadline )); do
    call GET "/api/trips/$trip_a" '' "$token_a"; expect_status 200
    a_status="$(json_field "$BODY" status)" || fail 'trip A status missing'
    a_driver="$(optional_driver_id "$BODY")" || fail 'trip A driverId malformed'
    call GET "/api/trips/$trip_b" '' "$token_b"; expect_status 200
    b_status="$(json_field "$BODY" status)" || fail 'trip B status missing'
    b_driver="$(optional_driver_id "$BODY")" || fail 'trip B driverId malformed'
    [[ "$a_driver" != "$driver_id" || "$b_driver" != "$driver_id" ]] || fail 'both trips acquired the same driver'
    [[ -z "$a_driver" || "$a_driver" == "$driver_id" ]] || fail 'trip A acquired an unexpected driver'
    [[ -z "$b_driver" || "$b_driver" == "$driver_id" ]] || fail 'trip B acquired an unexpected driver'
    call GET "/api/drivers/$driver_id" '' "$driver_token"; expect_status 200
    driver_status="$(json_field "$BODY" status)" || fail 'driver status missing'
    row="$(db_row "$driver_id")" || fail 'cannot read driver database after the race'
    IFS='|' read -r db_status db_owner db_version <<< "$row"
    [[ "$db_version" =~ ^[0-9]+$ ]] || fail "invalid driver database row: $row"
    if validate_snapshot "$a_status" "$a_driver" "$b_status" "$b_driver" "$db_status" "$db_owner" "$db_version" "$trip_a" "$trip_b" "$driver_id" "$old_version" && [[ "$driver_status" == BUSY ]]; then
      if [[ "$a_status" == ASSIGNED ]]; then winner="$trip_a"; loser="$trip_b"; else winner="$trip_b"; loser="$trip_a"; fi
      printf 'Trip A: %s driver=%s\n' "$a_status" "${a_driver:-none}"
      printf 'Trip B: %s driver=%s\n' "$b_status" "${b_driver:-none}"
      printf 'Driver: %s; DB reserved_for_trip=%s; version %s -> %s\n' "$driver_status" "$db_owner" "$old_version" "$db_version"
      printf 'PASS: winner=%s loser=%s driver=%s\n' "$winner" "$loser" "$driver_id"
      return 0
    fi
    sleep 0.5
  done
  fail "timed out: A=$a_status/$a_driver B=$b_status/$b_driver driver=$driver_status DB=$db_status/$db_owner/$db_version"
}

if [[ "${E2E_CONCURRENCY_LIB_ONLY:-0}" != 1 ]]; then main "$@"; fi
