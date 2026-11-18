# Member 2 Week 4 Concurrency Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a repeatable two-passenger, one-driver Gateway demo that stops when exactly one trip owns the driver.

**Architecture:** A standalone Bash script registers accounts and positions one driver in an empty geographic cell, then releases two background Gateway POST requests together. It polls both trips and the driver, and confirms the winning reservation in the driver database with a read-only query.

**Tech Stack:** Bash, curl, Docker Compose, PostgreSQL `psql`.

**Spec:** `docs/superpowers/specs/2026-11-18-member2-week4-concurrency-demo-design.md`

## Global Constraints

- All passenger and driver client requests go through `GATEWAY_URL`, default `http://localhost:8080`.
- No new runtime dependency, mock Driver Service, production matching change, or cleanup before PASS.
- A losing trip may still be `SEARCHING`; the script must not await `NO_DRIVER_FOUND`.
- The database query is read-only and used only for the final assertion.

## Review Focus

- Empty or malformed HTTP body: fail with the step and response rather than treating a missing `driverId` as evidence.
- A second available driver in the selected area: skip that area before the race, or fail if discovered after setup.
- One POST exits unsuccessfully: collect both subprocess statuses and responses, then fail without claiming a race.
- Trip assignment observed before Driver becomes `BUSY`: continue polling to the bounded deadline.
- Another trip obtains the demo driver: fail if database `reserved_for_trip` differs from the winning trip.

---

### Task 1: Gateway race script and demo instructions

**Files:**
- Create: `scripts/e2e-concurrency.sh`
- Modify: `scripts/README.md`

**Interfaces:**
- Consumes: `GATEWAY_URL`, `WAIT_SECONDS`, Compose services, Gateway auth/trip/driver endpoints.
- Produces: executable Bash script with exit 0 only when one Trip is `ASSIGNED` to the demo driver, the other has no driver, Driver is `BUSY`, and PostgreSQL ownership/version agree.

- [x] **Step 1: Add a small local contract check**

Create a shell harness or equivalent checks for malformed JSON, a failed POST, two assigned trips, and mismatched database ownership. Each must produce nonzero exit and no PASS line. Use stubs only at the external command boundary; do not mock application internals.

- [x] **Step 2: Run the contract check and observe failure**

Run the checks against the absent script and confirm they fail because `scripts/e2e-concurrency.sh` is missing.

- [x] **Step 3: Implement `scripts/e2e-concurrency.sh`**

Use the happy-path script's registration, login, location and status request shapes. Probe an empty cell, position a new driver, verify it is the sole candidate, capture the pre-race version via read-only SQL, release two background curl jobs together, then poll the two trips and driver. Re-query SQL for `status`, `reserved_for_trip`, and `version`. Print evidence and PASS; print the last observation and fail on deadline.

- [x] **Step 4: Verify script behavior**

Run `bash -n scripts/e2e-concurrency.sh` and the contract check. Run against the full Compose stack if available; require an actual PASS with two distinct trip IDs and one database owner. If the stack is unavailable, report that limit without claiming full integration success.

- [x] **Step 5: Document and commit**

Add prerequisites, invocation, expected evidence and the meaning of two `201` responses to `scripts/README.md`. Run `git diff --check`; commit only the script, its check if retained, and documentation.
