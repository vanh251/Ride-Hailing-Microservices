# Execution ledger — plan: docs/superpowers/plans/2026-11-09-member2-week3.md

Branch: feature/m2/week3-integration. Base: bb09de7.

Ruling: Implement in the current checkout on the requested new branch; no additional worktree was requested. Preserve existing files and the plan.
Ruling: The user's request to implement the plan authorizes its proposed local contract changes. Changes to Driver/Gateway/Compose remain handoff requirements for their owners; no shared deployment or messages to teammates.
Ruling: Use existing JUnit, Spring HTTP test tools/JDK HTTP server and PostgreSQL JDBC for verification. No Postman workspace exists and the installed postman.exe is a desktop application; adding a hosted workspace or dependencies is unnecessary for these local contracts.
Pre-flight: Task 1 requestId=tripId is consumed by Task 3 event ingestion and Task 7 internal rematch. Keep that policy consistently; no fabricated requests from REST.
Pre-flight: Task 4 ownership signatures affect Task 5 complete and Task 7 cancel; update controller and all call sites together.
Pre-flight: Task 3/6 dedup transactions and Task 8 outbox relay share the at-least-once guarantee. ACK failure must preserve eventId and outbox.
Pre-flight: Full E2E needs absent M1 reserve/Gateway code. Verify M2 paths with real DB/HTTP boundaries and report this integration dependency explicitly.

Baseline: Matching and Trip Gradle test suites both BUILD SUCCESSFUL (09/11/2026). Existing DeadLetterPublisher deprecation warnings observed.

Ruling: Serialize mutations of a Trip with a local PostgreSQL row lock in addition to its existing version. The cancel/start integration test proved that optimistic locking alone could lose cancellation after its external release side effect. This is not a driver/distributed lock. Cost: per-trip updates wait for bounded downstream calls.
Ruling: Keep baseline Trip/Outbox physical mappings and add only Matching processed_events already described by the schema. Do not apply the repository's illustrative DDL as a destructive migration. Cost: broader schema normalization remains separate work.
Ruling: Use one configured scheduler method to retry or expire requests through transactional MatchingService, rather than two racing scheduler methods. It still uses the original 60s deadline and 3-round budget. Cost: timeout action can lag by one 5s scan.
Ruling: Split local commits by independently reviewable component on the one branch requested by the user; shared Trip interfaces are changed together. No push/merge/PR publication is authorized or performed.

Evidence: MatchRequestPersistenceTest RED with OptimisticLockException/StaleObjectStateException against PostgreSQL 16, then GREEN after Persistable lifecycle fix. Initial insert/reload/update is exercised on a real DB, not a repository mock.
Evidence: DriverServiceClientTest RED when 503 became an empty list, then GREEN with propagated downstream error. Trip identity/accept overwrite tests RED then GREEN.
Evidence: Outbox async failed-future test RED (message deleted), then GREEN. KafkaRelayIntegrationTest delivered the exact key/value through a real embedded KRaft broker with 3 partitions after ACK.
Evidence: MatchingFlowIntegrationTest passed replay, concurrent creation and simulated commit failure with rollback compensation. TripLifecycleIntegrationTest passed payment success/failure/replay, history-failure rollback and concurrent payment replay against PostgreSQL.
Evidence: cancel/start interleaving RED (start completed while cancel was releasing), then GREEN with per-trip row serialization. The full Trip suite with PostgreSQL and embedded Kafka subsequently BUILD SUCCESSFUL.

## Status
- Task 0: implemented locally — API/event/schema clarifications and handoff doc; team review remains before shared merge.
- Task 1: complete — PostgreSQL regression RED→GREEN.
- Task 2: complete — real local HTTP tests, including 503 propagation; header/body/query verification added.
- Task 3: complete — event ingestion, composite dedup, atomic outbox and real DB concurrency tests.
- Task 4: complete — lifecycle/identity/payload and cancel/start race regression.
- Task 5: complete — typed quote/final client; no fallback price; 8-field numeric TripCompleted checked.
- Task 6: complete — payment events and DB rollback/replay tests; failure does not touch driver.
- Task 7: implemented for the agreed W3 boundary — bounded unsuccessful-search rematch, timeout, cancel/rollback compensation; post-commit stale assignment remains W5.
- Task 8: complete — async failure retention and actual KRaft ACK/round-trip test.
- Task 9: automated M2 checks implemented; full Gateway/Driver CAS SQL/E2E acceptance pending M1/M3 handoff, not reported as passed.

Whole-branch verification before review: matching `test bootJar` = 27 tests, 0 failures/errors/skips; trip `test bootJar` = 40 tests, 0 failures/errors/skips. PostgreSQL URL pointed to the dedicated test container, including all guarded DB integration tests. Both bootJar tasks passed. Embedded Kafka round-trip also passed. Existing Kafka kit deprecation and new generic-test unchecked warnings remain non-fatal; no runtime dependency changes.

Diff check passed with the repository's Windows line-ending handling. Seven formatter-only changes in M3's Kafka kit/test files were removed after comparing code with HEAD; the kit is unchanged.

Final reviewer findings and commit IDs will be recorded after review.
