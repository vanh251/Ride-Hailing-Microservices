# Kafka scripts

Run from the repository root in Git Bash (Windows), bash or zsh, with `docker compose up -d` running.

| Script | What it does |
|---|---|
| `create-topics.sh` | Creates the 6 topics of `docs/events.md` §3. Run automatically by the `kafka-init` service; safe to run again |
| `send-event.sh <EventType> [options]` | Publishes `tests/data/events/<EventType>.json` to its topic with a fresh `eventId`, as if the producing service had sent it |
| `tail-dlq.sh [topic]` | Prints the dead letter messages (`<topic>.DLQ`) and why each event was rejected |

## Test a consumer without the other service

**Member 2: Trip reacts to payment results**

```bash
TRIP=<tripId of a COMPLETED trip>
scripts/kafka/send-event.sh PaymentSucceeded --trip-id "$TRIP"     # trip → PAID
scripts/kafka/send-event.sh PaymentFailed    --trip-id "$TRIP"     # trip → PAYMENT_FAILED
scripts/kafka/send-event.sh DriverReserved   --trip-id "$TRIP"     # SEARCHING → ASSIGNED
```

**Member 3: Payment charges a completed trip**

```bash
scripts/kafka/send-event.sh TripCompleted --trip-id 33333333-3333-4333-8333-333333333333
curl -s http://localhost:8080/api/payments/33333333-3333-4333-8333-333333333333
```

**Deduplication**: send the same event twice with `--event-id <uuid>`; the second one must be skipped.

**Rejected events**: `scripts/kafka/tail-dlq.sh trip-events`.

`--dry-run` prints the message without sending it. Run `scripts/kafka/send-event.sh --help` for every option.
