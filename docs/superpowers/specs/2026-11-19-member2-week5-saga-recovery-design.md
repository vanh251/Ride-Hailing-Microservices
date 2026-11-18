# Member 2 W5: Saga assignment recovery design

Date: 2026-11-19. Status: proposed for review. Scope: Trip Service and Matching Service, with a Driver Service contract dependency owned by Member 1.

## Intent

Make a ride recover when `DriverReserved` arrives after its 30-second lease, without assigning an unreserved driver. Demonstrate a distributed Saga that remains consistent when Trip, Matching, or Driver pauses and Kafka redelivers messages. Reuse the current three-attempt, 60-second search budget. The final demo deadline makes this a focused reliability change, not a redesign of payment.

## Goals and acceptance criteria

1. A stale `DriverReserved` never moves Trip to `ASSIGNED` or emits an effective assignment. If the original search window and attempt budget remain, Trip requests recovery and Matching searches again. Otherwise Trip cancels and records compensation.
2. Trip does not expose `ASSIGNED` until Driver confirms that the exact reservation was atomically changed from `RESERVED` to `BUSY` before lease expiry.
3. Duplicate, delayed, and out-of-order assignment results do not assign the wrong driver or release a newer reservation. All decisions are durable across consumer restarts.
4. A lost confirmation leaves Trip in a bounded intermediate state. At the search deadline, Trip cancels and publishes `TripCancelled`; Driver releases a `BUSY` driver owned by that trip when it consumes the event. Do not rematch while confirmation may have succeeded but its result is unknown.
5. The existing payment Saga still settles `COMPLETED -> PAID` or `COMPLETED -> PAYMENT_FAILED` with a `saga_log` entry. Failure records the debt and never rolls back a completed driver service. Cancellation in `SEARCHING`, `ASSIGNING`, or `ASSIGNED` records compensation in `saga_log`.
6. Tests and one fault demo show stale event, successful rematch, exhaustion cancellation, duplicate/reordered events, and service restart with message recovery. Verify Trip, Matching, Driver, outbox, and saga records, not just HTTP responses.

## Non-goals and anti-goals

- No unbounded search, new payment retry workflow, manual DLQ replay UI, or new customer-facing feature.
- No `ASSIGNED` trip without a confirmed driver; no double assignment; no release of a reservation belonging to a later attempt or another trip; no indefinite `ASSIGNING` state.
- The existing Kafka consumer retry/DLQ mechanism remains the transport fallback. A business rejection (expired lease or version mismatch) is an ordinary outcome, not a poison message sent to DLQ.

## Current state and constraints

- Matching stores `MATCHED` and publishes `DriverReserved` through an outbox. Its existing rematch endpoint rejects committed `MATCHED`, intentionally leaving this recovery to W5.
- Trip currently accepts a valid `DriverReserved` payload without comparing `reservedUntil` to its clock. It directly commits `ASSIGNED` and publishes `DriverAssigned`.
- Driver consumes `DriverAssigned` and updates `RESERVED -> BUSY` for the same `tripId`, but the SQL update does not check lease expiry. Driver already has lease cleanup and conditional release by `tripId`.
- Contract v1.1 in `docs/events.md` lists eight official events. The confirmation events below require an agreed contract extension before implementation, including Kafka topic/consumer list, payloads, schema version, and event documentation. Keep the existing envelope conventions: PascalCase type, UUID identifiers, correlation and causation IDs, trip ID as Kafka key.
- Services have separate databases. Each service commits its local state, processed-event marker, and outgoing outbox row atomically. Cross-service changes are never one database transaction.

## Approaches considered

1. **Cancel on any stale reservation.** Smallest change, but a temporary delay unnecessarily loses a ride. Not selected.
2. **Bounded rematch with Driver's event acknowledgment. Selected.** Reuses Kafka/outbox and supports restart recovery. It needs an intermediate Trip state and a small Member 1 event/SQL change.
3. **Synchronous internal confirmation API.** Gives an immediate answer on a healthy network, but an HTTP timeout after Driver commits still leaves Trip uncertain. It would need the same durable reconciliation and compensation machinery. Not selected.

## Event contract and durable state

- The 60-second budget starts when Trip creates the ride, using the existing `RideRequested.payload.requestedAt`; it does not restart when Matching first consumes that event. Matching persists `expiresAt = requestedAt + 60 seconds` and includes the first reservation in the maximum of three attempts. A delayed first delivery may therefore leave no time to match. This is an explicit change from Matching's current receipt-time default.
- Matching adds the returned Driver reservation `version` to `DriverReserved.payload` as `reservationVersion`. It persists the active reservation's `driverId`, `reservationVersion`, `reservedUntil`, and `DriverReserved.eventId` in its match request or associated offer. Each new reservation attempt has a new version and event ID.
- Trip adds `ASSIGNING` between `SEARCHING` and `ASSIGNED`, plus durable pending `driverId`, `reservationVersion`, `reservedUntil`, and source event ID. Its public response can show `ASSIGNING` as a transient state. `start` and `accept` remain valid only at `ASSIGNED`. Trip's status history and saga log record the intermediate step.
- On a timely, current `DriverReserved`, Trip commits `ASSIGNING` and a `DriverAssigned` outbox row in one transaction. `DriverAssigned.payload` includes `reservationVersion`; its causation ID is the `DriverReserved.eventId`.
- Member 1 makes the Driver transition atomic with predicates `driverId`, `status=RESERVED`, `reserved_for_trip=tripId`, `version=reservationVersion`, and `reserved_until>now`. It publishes exactly one outcome through its outbox: `DriverAssignmentConfirmed` on success or `DriverAssignmentRejected` on a business mismatch. Both carry `tripId`, `driverId`, `reservationVersion`, and `driverAssignedEventId`; the envelope's causation ID is the `DriverAssigned.eventId`. Duplicate delivery of that same event reproduces the original outcome and does not increment Driver version twice.
- The two result events use a new `driver-events` topic, keyed by `tripId`, consumed by Trip. Add it to Compose/topic provisioning and `docs/events.md`. Driver processes and outbox records are committed together. Trip deduplicates by `eventId`, locks its Trip row, and accepts a result only when `ASSIGNING` and all pending identifiers match. On confirmation, it commits `ASSIGNED`, history, saga step, and processed marker. On rejection, it returns to `SEARCHING` and requests bounded Matching recovery.
- Matching recovery is internal-only and idempotent, addressed by `requestId=tripId` and the rejected or stale `DriverReserved.eventId`. It may reset only the matching committed `MATCHED` attempt. It checks Trip status and the original deadline/attempt count; it cannot replace a later attempt. A separate durable recovery request/outbox in Trip, or an internal command with retry persisted in Trip, ensures a crash between Trip's decision and Matching's receipt does not lose the rematch. Exact transport and table layout are builder choices; an unpersisted after-commit HTTP call is insufficient.

## Recovery rules

1. **Stale before assignment:** If `reservedUntil <= Trip.now` when `DriverReserved` is consumed, Trip marks the event processed without creating `DriverAssigned`. It durably requests Matching recovery. The old Driver lease is left to Driver's conditional cleanup; Trip does not call the current `DELETE /reserve?tripId=` here because it could release a newer reservation for the same trip and driver.
2. **Explicit Driver rejection:** Trip clears only the matching pending attempt, requests Matching recovery, and stays within the original three-attempt/60-second budget. Matching checks the source event ID before clearing `MATCHED` and counts the new match attempt against the existing budget.
3. **No Driver outcome:** Trip stays `ASSIGNING` while the outcome is unknown. At the original search deadline, its recovery job commits `CANCELLED`, `TripCancelled` outbox, history, and compensated saga step. Driver's existing `TripCancelled` consumer conditionally releases `RESERVED` or `BUSY` for this trip. Trip decides by its own processing time and row-lock order: a confirmation processed before the deadline may commit `ASSIGNED`; a confirmation arriving after the deadline is ignored even if Driver confirmed earlier. The timeout job and event consumers use the same row lock so only one terminal decision wins.
4. **Exhausted search:** Trip cancels once. Replayed stale or rejected results cannot reopen it. Matching becomes `EXPIRED` after seeing the cancelled Trip/event.
5. **Broker or service downtime:** Outbox rows remain until acknowledged; consumers redeliver after restart and deduplicate. Recovery jobs resume from database state. A pause beyond 60 seconds ends in cancellation rather than restarting the budget.
6. **Payment outcome:** Preserve existing payment event behavior. Use the existing `saga_log` as the durable failed-payment/debt record; enrich its `COMPENSATED` payload with payment ID, final fare, currency, and failure reason. No separate debt table, automatic recharge, or debt recovery workflow is in this W5 scope. A warning log alone is insufficient.

## Verification and demo

- Contract tests: new payloads and result event envelopes; malformed input reaches existing validation/DLQ path.
- Trip/Matching tests: stale reserve with time remaining causes exactly one recovery; no time or attempts causes cancellation; duplicate stale/rejection and late confirmation are no-ops; stale event for attempt A cannot clear attempt B; concurrent cancel and result produce one valid terminal outcome.
- Driver tests owned by M1: confirmation succeeds only for matching trip, version, and live lease; duplicates do not change version twice; rejection does not make Driver busy; `TripCancelled` releases a confirmed driver for that trip but never a new trip's driver.
- Integration demo: pause Trip consumer beyond the lease but within the 60-second window and observe rematch; pause Driver after `DriverAssigned`, restart it, and observe a single confirmation or bounded cancellation; repeat with downtime past the search deadline. Inspect database statuses, saga log, outbox, and Kafka events.

## Builder decisions and dependencies

- Builder chooses migrations, repository queries, scheduler cadence, and whether the durable rematch command uses a new Trip outbox event or a persisted internal-command job. Maintain the invariants above.
- M1 owns Driver predicate, idempotent outcome, Driver outbox, and Driver tests. M2 owns Trip/Matching state, recovery, saga log, and tests. M3 or the integrator provisions `driver-events` and deploys the contract. Update `docs/events.md`, `docs/api.md`, SRS state diagram, and test fixtures together before the integrated demo.
- The new event contract requires group agreement because it extends the existing eight-event catalogue. If M1 or topic provisioning cannot be delivered, this design cannot claim confirmed assignment; fall back to the explicitly weaker stale-event check and document its risk, rather than claiming the Saga is fully recovered.
