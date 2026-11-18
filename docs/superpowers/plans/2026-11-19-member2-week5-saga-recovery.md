# Member 2 W5 Saga Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover an expired driver reservation within the original matching budget and expose `ASSIGNED` only after Driver confirms the exact live reservation.

**Architecture:** Trip records an `ASSIGNING` attempt and emits `DriverAssigned` from its outbox. Driver confirms or rejects through a new `driver-events` topic; Trip applies the result under a row lock. A stale or rejected attempt produces a durable `AssignmentRecoveryRequested` event consumed by Matching, which can reset only that committed attempt and rematch within the original budget.

**Tech Stack:** Java, Spring Boot, Spring Data JPA, PostgreSQL, Spring Kafka, Gradle, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-19-member2-week5-saga-recovery-design.md`

## Global Constraints

- Three matching attempts, including the first, and a 60-second deadline starting at Trip creation (`RideRequested.payload.requestedAt`). Driver's lease is 30 seconds.
- Keep Kafka keys equal to `tripId`, PascalCase event types, UUID IDs, correlation/causation IDs, and transactional local state/outbox/processed-event writes.
- Preserve separate service databases. Do not create a cross-service transaction or automatically recharge a failed payment.
- No `ASSIGNED` before a matching `DriverAssignmentConfirmed`; an unknown Driver outcome leads to cancellation at the deadline, never a speculative rematch.
- Member 1 owns Driver transition/outcome and outbox. M3/integrator owns new topic provisioning. The M2 implementation must not claim integrated acceptance until both are delivered.

## Review Focus

1. `RideRequested` first reaches Matching after 60 seconds: it must expire without reserving a driver. Task 1 test.
2. An old reservation event for attempt A arrives after attempt B starts: it must not reset or release B. Task 3 test.
3. Driver confirms before the deadline but Trip receives the result afterward: Trip must cancel and ignore the result. Task 4 test.
4. Confirmation and timeout race on the same Trip: exactly one row-locked decision wins. Task 4 test.
5. Duplicate Driver confirmation/rejection and duplicate recovery requests: no extra transition or match attempt. Tasks 3 and 4 tests.

## File map and external handoff

| Responsibility | Files |
|---|---|
| Contract extension | `docs/events.md`, `docs/api.md`, `docs/SRS.md`; three new event types: `AssignmentRecoveryRequested`, `DriverAssignmentConfirmed`, `DriverAssignmentRejected` |
| Matching deadline and reservation identity | `src/matching-service/src/main/java/com/example/matchingservice/entity/MatchRequest.java`, `service/MatchingServiceImpl.java`, `dto/ReserveDriverResponse.java` (read existing version), `event/TripEventConsumer.java` |
| Trip pending attempt and state | `src/trip-service/src/main/java/com/example/tripservice/entity/Trip.java`, `entity/TripStatus.java`, `service/TripService.java`, `service/TripServiceImpl.java`, `event/DriverReservedConsumer.java` |
| Trip Driver outcome and deadline | Create `src/trip-service/src/main/java/com/example/tripservice/event/DriverAssignmentResultConsumer.java`, `service/AssignmentTimeoutScheduler.java`; modify `repository/TripRepository.java`, `application.yml` |
| Payment Saga durability | `src/trip-service/src/main/java/com/example/tripservice/service/TripServiceImpl.java`, existing `SagaLog` |
| Database changes | Create `scripts/db/2026-11-19-m2-w5-trip.sql` and `scripts/db/2026-11-19-m2-w5-matching.sql` with additive nullable columns for pending/active attempts; update `docs/database_schema.md`. Existing volumes must not be deleted; local `ddl-auto=update` alone is not the deployment migration. |

**M1 contract gate:** Before integrated testing, M1 must make Driver's `RESERVED -> BUSY` update require `reserved_for_trip=tripId`, exact `reservationVersion`, and `reserved_until>now`; write a deterministic confirmed/rejected outcome to Driver's outbox with `tripId`, `driverId`, `reservationVersion`, `driverAssignedEventId`; deduplicate replay; and retain `TripCancelled` release of `RESERVED/BUSY` by `tripId`. M2 can develop against event fixtures before this arrives. M3/integrator must provision `driver-events` and include it in Compose/demo fixtures.

---

### Task 1: Fix the original matching deadline and publish reservation identity

**Files:** Modify `MatchRequest.java`, `MatchingServiceImpl.java`, `docs/database_schema.md`; create `scripts/db/2026-11-19-m2-w5-matching.sql`; test `src/matching-service/src/test/java/com/example/matchingservice/service/MatchingServiceTest.java` and `repository/MatchRequestPersistenceTest.java`.

**Interfaces:** `RideRequested.payload.requestedAt: Instant`; `DriverReserved.payload.reservationVersion: int`. Persist `activeReservationEventId: UUID`, `activeReservationVersion: Integer`, `activeReservedUntil: Instant` alongside `matchedDriverId`.

- [ ] Write failing tests `lateRideRequestedExpiresWithoutReserve` (requestedAt 61 seconds old, zero reserve calls), `timelyRideRequestedUsesOriginalDeadline` (expiresAt equals requestedAt + 60 seconds), and `driverReservedCarriesActiveVersionAndEventId` (version equals reserve response and persisted event ID equals emitted event ID).
- [ ] Run `src/matching-service/gradlew.bat test --tests '*MatchingServiceTest' --tests '*MatchRequestPersistenceTest'`; verify the three tests fail for missing behavior.
- [ ] Add the nullable active-attempt fields, derive `expiresAt` from event `requestedAt`, and record the reservation version/event ID in the same transaction as `MATCHED` and the outbox row.
- [ ] Run the same tests; expect all pass. Commit the Matching source, tests, and schema documentation.

### Task 2: Add Trip's pending assignment state and stale-event decision

**Files:** Modify `TripStatus.java`, `Trip.java`, `TripService.java`, `TripServiceImpl.java`, `DriverReservedConsumer.java`, `docs/database_schema.md`; create `scripts/db/2026-11-19-m2-w5-trip.sql`; test `src/trip-service/src/test/java/com/example/tripservice/event/DriverReservedConsumerTest.java` and `service/TripServiceTest.java`.

**Interfaces:** Replace direct `assignDriver(UUID,UUID,UUID)` with `beginAssignment(UUID tripId, UUID driverId, int reservationVersion, Instant reservedUntil, UUID sourceEventId)`. Add `ASSIGNING` and nullable pending driver/version/lease/source-event columns. The method creates `DriverAssigned` with `reservationVersion` only for a live lease. For an expired lease it cancels if the original 60 seconds elapsed; otherwise it creates `AssignmentRecoveryRequested` with `sourceDriverReservedEventId` and reason `LEASE_EXPIRED`. Matching decides whether attempts remain.

- [ ] Write failing tests `staleReservationRequestsRecoveryWithoutAssignment`, `liveReservationEntersAssigningAndPublishesVersionedCommand`, `cancelWhileAssigningPublishesTripCancelledAndSagaCompensation`, and `startAndAcceptRejectAssigning`.
- [ ] Run `src/trip-service/gradlew.bat test --tests '*DriverReservedConsumerTest' --tests '*TripServiceTest'`; verify those tests fail for the intended reasons.
- [ ] Implement the state, pending columns, transactional event/processed-marker handling, and compensated saga row on cancellation. Do not call Driver's current `DELETE /reserve` for a stale event.
- [ ] Run the same tests; expect pass. Commit Trip state, consumer, tests, and schema documentation.

### Task 3: Recover a committed MATCHED attempt in Matching

**Files:** Modify `MatchingService.java`, `MatchingServiceImpl.java`, `event/TripEventConsumer.java`; test `MatchingServiceTest.java` and create `src/matching-service/src/test/java/com/example/matchingservice/event/AssignmentRecoveryConsumerTest.java`.

**Interfaces:** `handleAssignmentRecovery(EventEnvelope<Map<String,Object>> event): void`. Consume `AssignmentRecoveryRequested` from `trip-events`; payload includes `tripId`, `sourceDriverReservedEventId`, and `reason`. Match only if active event ID equals the source, Trip remains `SEARCHING`, and attempts/deadline permit another try. Duplicate event IDs use the existing processed-event store.

- [ ] Write failing tests `expiredAttemptRematchesOnce`, `oldAttemptCannotClearNewAttempt`, `duplicateRecoveryDoesNotIncrementAttempt`, `exhaustedRecoveryCancelsTripOnce`, and `recoveryForNonSearchingTripDoesNotRematch`.
- [ ] Run `src/matching-service/gradlew.bat test --tests '*MatchingServiceTest' --tests '*AssignmentRecoveryConsumerTest'`; verify failures.
- [ ] Add the recovery event branch, row-locked attempt identity check, and bounded rematch. Clear only the old attempt's fields; leave old Driver cleanup to its lease or `TripCancelled` consumer.
- [ ] Run the same tests; expect pass. Commit the Matching recovery change and tests.

### Task 4: Consume Driver outcomes and time out unknown results

**Files:** Create `DriverAssignmentResultConsumer.java`, `AssignmentTimeoutScheduler.java`; modify `TripService.java`, `TripServiceImpl.java`, `TripRepository.java`, `application.yml`; test `src/trip-service/src/test/java/com/example/tripservice/event/DriverAssignmentResultConsumerTest.java` and `service/TripServiceTest.java`.

**Interfaces:** Add `applyAssignmentOutcome(UUID tripId, UUID driverId, int reservationVersion, UUID driverAssignedEventId, boolean confirmed): void` and `expirePendingAssignment(UUID tripId): void`. Result events require the pending identity and matching `ASSIGNING` state. Scheduler scans `ASSIGNING` trips whose `createdAt + 60 seconds <= now`; repository row lock serializes it with the result consumer.

- [ ] Write failing tests `matchingConfirmationCommitsAssigned`, `rejectionEmitsOneRecoveryRequest`, `duplicateOutcomeIsNoOp`, `lateConfirmationAfterDeadlineCancels`, `timeoutReleasesThroughTripCancelled`, and `concurrentTimeoutAndConfirmationHasOneWinner`.
- [ ] Run `src/trip-service/gradlew.bat test --tests '*DriverAssignmentResultConsumerTest' --tests '*TripServiceTest'`; verify failures.
- [ ] Implement result envelope validation/dedup, pending identity checks, transactional transitions/outbox, and scheduled timeout. Ensure rejection clears pending fields and emits recovery in one transaction.
- [ ] Run the same tests; expect pass. Commit Trip outcome and timeout handling with tests.

### Task 5: Record payment debt durably and finalize the contract

**Files:** Modify `TripServiceImpl.java`, `docs/events.md`, `docs/api.md`, `docs/SRS.md`; test `src/trip-service/src/test/java/com/example/tripservice/event/PaymentEventConsumerTest.java` and `service/TripServiceTest.java`.

**Interfaces:** Existing `applyPaymentResult(...)` retains its signature. `saga_log` `COMPENSATED` payload stores `paymentId`, `finalFare`, `currency`, and failure `reason`; payment retry remains outside scope.

- [ ] Write failing test `paymentFailurePersistsDebtDetailsWithoutReleasingDriver` and contract tests for the three new event payloads, topic, and causation IDs.
- [ ] Run focused Trip tests; verify failures.
- [ ] Enrich the Saga payload and update contract/state documentation, including `ASSIGNING` and the three new events. Align the former eight-event catalogue and demo fixtures with the extension.
- [ ] Run focused Trip tests; expect pass. Commit payment Saga and contract documentation.

### Task 6: Verify integration and the fault demo

**Files:** Add focused integration tests under `src/trip-service/src/test/java/com/example/tripservice/integration/` and `src/matching-service/src/test/java/com/example/matchingservice/`; update `docs/operations.md` with the exact fault-demo commands and expected state queries. M3/integrator updates topic provisioning in Compose/scripts.

**Interfaces:** Requires M1's confirmed/rejected event contract and `driver-events` topic. Inspect `trips`, `match_requests`, Driver state, `saga_log`, outbox, and processed-event rows.

- [ ] Write an integration test for delayed `DriverReserved` within budget and one for downtime past 60 seconds; assert no orphan `ASSIGNED`, exactly one accepted assignment or cancellation, and no new search after cancellation.
- [ ] Run both service suites with their own wrappers: `src/trip-service/gradlew.bat test` and `src/matching-service/gradlew.bat test`; expect zero failures/skips in the relevant tests. Use the documented dedicated PostgreSQL test database when required.
- [ ] Run the Docker/Kafka fault demo after M1/M3 handoff: pause Trip past the 30-second lease but under 60 seconds; restart and observe bounded rematch. Pause Driver after command; restart and observe one result. Repeat past 60 seconds and observe cancellation plus Driver release. Save command/output evidence in `docs/operations.md`.
- [ ] Commit the integration tests and demo instructions. Report separately any external handoff that prevented the integrated run; do not mark it passed from mocked tests alone.

## Self-review against the approved spec

- Stale lease, bounded rematch, exact reservation fencing, unknown-result timeout, duplicate/reordered events, cancellation compensation, payment debt, and fault demo each map to Tasks 1–6.
- The M1 atomic Driver predicate/outcome and M3 topic are explicit acceptance gates, not silently assigned to M2.
- No production source changes are authorized by this plan alone; implementation begins after plan review and execution-method selection.
