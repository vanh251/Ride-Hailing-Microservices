# Operations: Docker, Monitoring, Fault Tests, Performance

How to run, watch and stress the system. The Kafka contract (topics, retry, DLQ, deduplication) is in
[events.md](events.md) §3 and §6–§8; this page only covers how to operate and test it.
All scripts are bash and run from the repository root (Git Bash on Windows).

## 1. Docker

| Task | Command |
|---|---|
| Build and start everything, wait until healthy | `scripts/start.sh` |
| Stop (keep data) / stop and delete data | `scripts/stop.sh` / `scripts/stop.sh -v` |
| Logs of one service | `docker compose logs -f payment-service` |

`start.sh` creates `.env` from `.env.example` when it is missing. `kafka-init` creates the 6 topics
before any Kafka-using service starts. The first build downloads Gradle and all dependencies once; the
services share that cache and build their jars one after another.

## 2. Monitoring

- Prometheus: <http://localhost:9090/targets> scrapes `/actuator/prometheus` of all 8 services every 15s.
- Grafana: <http://localhost:3000>, user and password from `.env`. The dashboard **DS03 Services** is
  loaded automatically from `deploy/monitoring/grafana/provisioning/dashboards/`.

| Panel | Shows |
|---|---|
| Service health | `up` per service (1 = Prometheus can reach it) |
| API requests / errors per second | request rate and HTTP 5xx rate per service |
| API latency P50 / P95 | only pricing, payment and notification export latency buckets |
| Kafka consumer lag | records a consumer still has to read; grows while a consumer is down |
| Kafka records consumed per second | queue processing speed per consumer |
| CPU usage, JVM heap used | per service |

## 3. Retry, DLQ and message recovery

A consumer retries a failed event 3 times (1s, 2s, 4s), then moves it to `<topic>.DLQ`
([events.md](events.md) §6, §8). A malformed event skips the retries.

| Task | Command |
|---|---|
| See rejected events and the reason | `scripts/kafka/tail-dlq.sh trip-events` |
| Send them again after the cause is fixed | `scripts/kafka/replay-dlq.sh trip-events [tripId or eventId]` |

Replay re-publishes the original event with the same `eventId`, so services that already processed it
skip it (`processed_events`). Notification Service has no deduplication and notifies again.

## 4. Fault tests

`scripts/fault-tests.sh` needs only `kafka`, `payment-db` and `payment-service`, with
`PAYMENT_FAILURE_MODE=none`. Each scenario publishes a `TripCompleted` for a new trip.

| # | Fault | Expected, checked by the script |
|---|---|---|
| 1 | Payment Service stopped while the event is published | Kafka keeps the event; after restart the trip is charged |
| 2 | Same event sent twice, plus the same trip with a new `eventId` | exactly 1 row in `payments` |
| 3 | `payment-db` stopped | 3 retries, event lands in `trip-events.DLQ`; after the database is back, replay charges the trip |

Result on 2026-11-13: all three scenarios passed.

To demo the `PaymentFailed` saga path, set `PAYMENT_FAILURE_MODE=always` in `.env` and restart payment-service.

## 4.1. Member 2 W5 assignment recovery demo (pending M1/M3 handoff)

Preconditions: deploy the W5 Trip and Matching images together; apply `scripts/db/2026-11-19-m2-w5-trip.sql` to `trip_db` and `scripts/db/2026-11-19-m2-w5-matching.sql` to `matching_db`; have M1 deploy the version/lease-fenced Driver transition and `DriverAssignmentConfirmed`/`DriverAssignmentRejected` outbox producer; have M3 provision `driver-events` (3 partitions) and `driver-events.DLQ` (1 partition) and move definitive assignment notifications from `DriverAssigned` to `DriverAssignmentConfirmed`. Existing W3 Driver does not produce these events, so it cannot complete this demo. Existing Notification may announce a driver whose assignment is later rejected; do not enable customer notifications until M3 updates that consumer.

**Cutover from W3 data:** Stop new requests at the Gateway before changing binaries, then leave the old Trip/Matching/Driver consumers and outbox relays running until their relevant topic lag is zero and their outboxes have no pending `DriverReserved`/`DriverAssigned`. Inspect with:

```powershell
docker compose stop api-gateway
docker compose exec -T matching-db psql -U postgres -d matching_db -c "SELECT count(*) FROM outbox_events WHERE event_type='DriverReserved';"
docker compose exec -T trip-db psql -U postgres -d trip_db -c "SELECT count(*) FROM outbox_events WHERE event_type='DriverAssigned';"
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group trip-service-group
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group driver-service-group
```

Also list old `MATCHED` requests and compare their Trip state; the two databases are separate:

```powershell
docker compose exec -T matching-db psql -U postgres -d matching_db -c "SELECT request_id,trip_id,matched_driver_id FROM match_requests WHERE status='MATCHED';"
docker compose exec -T trip-db psql -U postgres -d trip_db -c "SELECT trip_id,status FROM trips WHERE trip_id='TRIP_UUID';"
```

Any `MATCHED` request whose Trip is still `SEARCHING` must be resolved **before** deploying W5: wait for the old event if it is still in flight; otherwise cancel that Trip through the existing authenticated/internal Trip cancellation API and verify `TripCancelled` reaches Matching/Driver and the request becomes `EXPIRED`. Do not edit only the database status: that would omit the compensation event. Do not replay a W3 `DriverReserved` from DLQ after cutover; it lacks `reservationVersion`. Record affected trip/event IDs and reconcile or cancel them under the old contract. Confirm the queries and lag are clear, then apply the migrations, provision the new topic, deploy M1/M2/M3 together, and restart the Gateway. A nonzero old backlog blocks cutover.

If Matching's local transaction rolls back after Driver accepted a reservation, Matching deliberately leaves that reservation for Driver's 30-second lease cleanup. Its old DELETE API checks only `tripId`; a delayed rollback DELETE could release a newer attempt for the same trip and driver. Cancellation still emits `TripCancelled`, which releases the driver owned by that cancelled trip.

1. Through Gateway, create a Trip and note its UUID and correlation ID. Stop only `trip-service` immediately after Matching has written a `DriverReserved` outbox row: `docker compose stop trip-service`. Keep it stopped until the 30-second Driver lease expires, but restart before the original 60-second search deadline: `docker compose start trip-service`.
2. Inspect `trip_db.trips` and `matching_db.match_requests` for that UUID. The stale event must not set `ASSIGNED`; `trip-events` must contain `AssignmentRecoveryRequested`; Matching should increment `attempt_count` at most once and emit a new `DriverReserved` only if another driver is available. The new event must carry a different `eventId` and its own `reservationVersion`.
3. Stop `driver-service` after a new `DriverAssigned` is in `trip-events`, then start it again. Driver must emit exactly one logical confirmed/rejected result for the command, even if Kafka redelivers it. Trip reaches `ASSIGNED` only for a matching confirmation; rejection triggers bounded recovery.
4. Repeat with Trip or Driver paused past the original 60-second deadline. Trip must commit `CANCELLED`, a `TripCancelled` outbox row, and `saga_log.SAGA_COMPENSATED`. Driver eventually returns a `RESERVED` or `BUSY` driver for that trip to `AVAILABLE`. A late confirmation must not reopen the trip.

Useful state queries (replace the UUID and database user if `.env` overrides the default):

```powershell
docker compose exec -T trip-db psql -U postgres -d trip_db -c "SELECT trip_id,status,driver_id,pending_driver_id,pending_assignment_event_id FROM trips WHERE trip_id='TRIP_UUID';"
docker compose exec -T trip-db psql -U postgres -d trip_db -c "SELECT step_name,status,payload FROM saga_log WHERE trip_id='TRIP_UUID' ORDER BY created_at;"
docker compose exec -T matching-db psql -U postgres -d matching_db -c "SELECT request_id,status,attempt_count,matched_driver_id,active_reservation_event_id FROM match_requests WHERE trip_id='TRIP_UUID';"
docker compose exec -T driver-db psql -U postgres -d driver_db -c "SELECT driver_id,status,reserved_for_trip,reserved_until,version FROM drivers WHERE reserved_for_trip='TRIP_UUID';"
```

Record the trip UUID, Kafka event IDs, database output, and stop/start timestamps. Do not treat a unit test or a paused service without these state checks as the completed fault demo.

## 5. Performance experiment

`scripts/load/run.sh` runs k6 from its Docker image against pricing and payment directly (not through
the gateway). One iteration = quote a fare, charge it for a new trip, read the payment back: 3 requests,
of which the charge writes 3 rows (payment, ledger, outbox) in one transaction.

Result on 2026-11-13, 30s per stage, everything on one laptop (Docker Desktop, 16 GB):

| Users | Throughput (req/s) | P50 | P95 | Error rate |
|---:|---:|---:|---:|---:|
| 10 | 1,679 | 1.9 ms | 15 ms | 0% |
| 50 | 1,691 | 31 ms | 74 ms | 0% |
| 100 | 3,103 | 39 ms | 87 ms | 0% |
| 500 | 3,085 | 211 ms | 344 ms | 0% |

CPU and memory in the middle of the 100 and 500 user stages:

| Container | CPU (100 / 500 users) | Memory (100 / 500 users) |
|---|---|---|
| payment-service | 305% / 279% | 708 / 787 MiB |
| payment-db | 234% / 227% | 141 / 186 MiB |
| pricing-service | 61% / 62% | 406 / 429 MiB |
| kafka | 7% / 62% | 447 / 450 MiB |
| notification-service | 5% / 2% | 390 / 390 MiB |

What the numbers say:

- Throughput stops growing at about 3,100 req/s (about 1,030 charges/s). From 100 to 500 users only the
  latency grows, so the system is saturated; payment-service and its database use the CPU.
- No request failed at any stage.
- **Queue processing is the bottleneck.** The outbox poller publishes one event at a time and waits for
  the broker, which measured about 50 events/s. After the run about 92,000 `PaymentSucceeded` events were
  still waiting in `outbox_events` (about 30 minutes to drain). No event is lost, but trip-service and
  notification-service see the result late. In the real flow a charge only starts from a completed trip,
  far below 50 per second.
