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

## Final review and fix pass

Fresh read-only reviewer examined bb09de7..4b22dd3. It reported two Important findings and no Critical/Minor findings. Both findings were reproduced before changing production code: three cancellation regressions and the abbreviated-correlation test failed, then the same four tests passed after the fix.

- Final: fixed cancellation rollback freeing an active trip's driver — cancellation state/outbox now commit before an after-commit DELETE; outbox-write and commit-failure regressions prove no release on rollback. The updated interleaving test proves CANCELLED is visible before release, so a concurrent start is rejected.
- Final: fixed noncanonical correlation creating a trip whose event goes to DLQ — reject abbreviated UUID groups with 400 before Pricing/persistence/event emission. Canonical UUIDs retain the original business correlation.
- Final: added real Spring Boot Kafka auto-configuration test; it passed, proving the Optional KafkaTemplate is actually wired and outbox deletes only after publish. DirtiesContext closes the broker before JVM cleanup on Windows.
- Final: Ruling: move cancellation DELETE after commit, clarifying the original pre-publish ordering — never release a driver for a rolled-back cancellation; event/REST cleanup remain guarded/idempotent — cost: cleanup paths can race after commit and API waits up to the client timeout for REST.
- Final: Ruling: stale DriverReserved after lease stays W5 as explicitly scoped — cost: prolonged broker/Trip downtime still needs assignment recovery; do not present W3 as covering that fault scenario.
- Final: Ruling: full Driver/Gateway/Compose acceptance stays with owners — cost: the shared stack cannot yet be treated as working E2E; local M2 tests are not a substitute.
- Final: Ruling: outbox is a single-relay W3 design — cost: multi-instance ordering needs separate work before scaling.
- Final: Ruling: ignore late/contradictory payment outcomes once terminal, as planned — cost: later reconciliation/retry needs the W5 workflow rather than silently changing a completed result.
- Final: Ruling: ambiguous reserve timeout falls back to the authoritative Driver lease — cost: a driver can remain unavailable until the 30s lease cleanup; a complete recovery protocol is outside W3.
- Final: Ruling: no KafkaTemplate generic change — cached Boot wildcard-bean/fallback resolution and the new runtime test both support existing wiring — cost: none identified.
- Deferred minors: none reported by the reviewer.

Local component commits before the fix: d1e0ce6 (ID lifecycle), 13edc5b (Driver client), 3ee2eb9 (outbox ACK), d67db67 (Matching), 4bfe156 (Trip), 4b22dd3 (contracts/handoff). No push/merge performed.

Final verification after the one review-fix pass: Matching `test bootJar` BUILD SUCCESSFUL, 27 tests, 0 failures/errors/skips; Trip `test bootJar` BUILD SUCCESSFUL, 44 tests, 0 failures/errors/skips. XML reports and fresh final build logs were inspected. PostgreSQL guarded tests ran (none skipped); embedded Kafka and actual Boot Kafka wiring ran. No Kafka temp-directory cleanup error remains after explicit context closure.

Final branch handling: keep the user-requested feature branch in the current checkout. No merge, push or PR publication. Test container cleanup targets only the exact container created for this run, with no application volumes mounted. The tracked progress/handoff documents remain as the review record; build logs/reports are ignored artifacts.
