# Scripts

## Test scripts (bash, run from the repository root)

| Script | Purpose |
|---|---|
| `scripts/e2e-happy-path.sh` | Runs the W3 Happy Path through the gateway (register → trip → matching → payment → notification) and stops at the first broken step |
| `scripts/e2e-concurrency.sh` | Runs the W4 two-passenger/one-driver race through the gateway and proves that only one trip owns the driver |
| `scripts/kafka/send-event.sh <EventType>` | Publishes a fixture of `tests/data/events` to Kafka to test a consumer alone |
| `scripts/kafka/tail-dlq.sh [topic]` | Shows rejected events in the DLQ topics, with the consumer group that rejected them |
| `scripts/kafka/replay-dlq.sh <topic> [filter]` | Message recovery: sends the original events of a DLQ topic back to their topic |
| `scripts/start.sh` / `scripts/stop.sh` | Builds and starts the whole system and waits until healthy / stops it |
| `scripts/fault-tests.sh [1\|2\|3]` | Fault and recovery tests of the payment flow: service down, duplicate event, database down → DLQ → replay |
| `scripts/load/run.sh [users...]` | k6 load test of pricing and payment with 10, 50, 100, 500 users |

See the project report in `docs/` for monitoring, the fault tests and the performance results.

See `scripts/kafka/README.md` for examples.

### W4 concurrency demo

Start the full Compose stack and wait until the Gateway and services are healthy. From the repository root, run in Git Bash or another Bash shell:

```bash
scripts/e2e-concurrency.sh
```

The script needs `curl`, `docker compose`, and permission to run a read-only `psql` query in `driver-db`. Override `GATEWAY_URL` (default `http://localhost:8080`) or `WAIT_SECONDS` (default `45`) if needed. It creates two passenger accounts and one driver in a search area without other available drivers, then releases both trip requests together. It prints both trip IDs, both final Trip statuses, the Driver status, `reserved_for_trip`, and the version change. A successful run ends with `PASS: winner=... loser=... driver=...` as soon as one Trip is `ASSIGNED` and the other remains `SEARCHING` without a driver. Both initial `POST /api/trips` requests return `201`; assignment occurs asynchronously after creation.

For the local contract check, run `bash tests/e2e-concurrency-test.sh`. It checks that malformed responses, failed creation, two assigned trips, and incorrect database ownership cannot produce PASS.
