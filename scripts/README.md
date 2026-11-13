# Git Workflow Scripts — Ride-Hailing Microservices

## Branch strategy

```text
main
  ^
  | release PR
develop
  ^
  | Pull Request
feature/m1/*
feature/m2/*
feature/m3/*
```

## Team ownership

- `m1`: Identity + Driver + Security
- `m2`: Trip + Matching + Concurrency + Saga
- `m3`: Pricing + Payment + Notification + Kafka + Monitoring/Deployment

## Scripts

### Initialize

```powershell
.\scripts\git-start.ps1 -RemoteUrl "https://github.com/<org>/<repo>.git"
```

Run once only when the local directory has no `.git`.

### Create feature

```powershell
.\scripts\git-feature.ps1 m1 identity-service
.\scripts\git-feature.ps1 m2 matching-service
.\scripts\git-feature.ps1 m3 payment-service
```

### Commit + push

```powershell
.\scripts\git-push.ps1 "feat(matching): implement driver matching"
```

The script blocks direct commits to `main` and `develop`.

### Sync feature with develop

```powershell
.\scripts\git-sync.ps1
```

It fetches `origin`, updates `develop`, rebases the feature branch and pushes with `--force-with-lease`.

### PR pre-check

```powershell
.\scripts\git-pr-check.ps1
```

### Status

```powershell
.\scripts\git-status.ps1
```

### Release

```powershell
.\scripts\git-release.ps1 v1.0.0
```

Then create PR:

```text
release/v1.0.0 -> main
```

After release validation:

```powershell
.\scripts\git-finish-release.ps1 v1.0.0
```

### Cleanup merged local branches

```powershell
.\scripts\git-clean-merged.ps1
```

## Daily workflow

```powershell
.\scripts\git-feature.ps1 m2 trip-service

# code...

.\scripts\git-push.ps1 "feat(trip): implement trip creation"

.\scripts\git-sync.ps1
.\scripts\git-pr-check.ps1

# GitHub:
# feature/m2/trip-service -> develop
# review -> CI -> merge
```

## Team rules

1. No direct commits to `main`.
2. No direct commits to `develop`.
3. Feature branches start from `develop`.
4. Integration happens through Pull Requests.
5. Never commit `.env`, credentials, JWT secrets, private keys or tokens.
6. No cross-service database access.
7. Keep API/event contracts documented.
8. Prefer small PRs.
9. `git-sync.ps1` uses `--force-with-lease`, never plain `--force`.
10. `develop` should remain buildable and testable.

## Test scripts (bash, run from the repository root)

| Script | Purpose |
|---|---|
| `scripts/e2e-happy-path.sh` | Runs the W3 Happy Path through the gateway (register → trip → matching → payment → notification) and stops at the first broken step |
| `scripts/kafka/send-event.sh <EventType>` | Publishes a fixture of `docs/test-data/events` to Kafka to test a consumer alone |
| `scripts/kafka/tail-dlq.sh [topic]` | Shows rejected events in the DLQ topics, with the consumer group that rejected them |
| `scripts/kafka/replay-dlq.sh <topic> [filter]` | Message recovery: sends the original events of a DLQ topic back to their topic |
| `scripts/start.sh` / `scripts/stop.sh` | Builds and starts the whole system and waits until healthy / stops it |
| `scripts/fault-tests.sh [1\|2\|3]` | Fault and recovery tests of the payment flow: service down, duplicate event, database down → DLQ → replay |
| `scripts/load/run.sh [users...]` | k6 load test of pricing and payment with 10, 50, 100, 500 users |

See `docs/operations.md` for monitoring, the fault tests and the performance results.

See `scripts/kafka/README.md` for examples.
