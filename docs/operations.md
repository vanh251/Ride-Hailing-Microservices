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
