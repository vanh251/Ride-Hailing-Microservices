# Member 2 W4 concurrency demo — design

## Intent

Demonstrate through the API Gateway that two passengers competing for one available driver cannot both acquire that driver. The demo ends as soon as one trip owns the driver and the other does not; it does not wait for the losing search to expire.

## Goals and acceptance

- Run one repeatable Bash command against a healthy Docker Compose stack, using only public client requests through the Gateway for setup and trip creation.
- Register two distinct passengers and one driver. Place the driver and both pickups in an isolated search area. Verify that this driver is the only available candidate before starting the race.
- Submit the two `POST /api/trips` calls concurrently with a shared start barrier. Each call must return `201`, a distinct trip ID, and the matching passenger ID. A `201` means creation, not successful matching.
- Poll both trips through the Gateway with their respective passenger tokens until exactly one is `ASSIGNED` to the new driver and the other has no `driverId`. Poll the driver until it is `BUSY`, then read the driver database row and require `reserved_for_trip` to equal the winning trip ID. The row must have advanced its version from the pre-race value.
- Print the two request IDs, their final statuses, the driver ID, database ownership and version, and a clear PASS/FAIL result. Use a bounded deadline; on timeout, print the last observed state and fail.

## Non-goals and failure conditions

- No requirement for the loser to reach `NO_DRIVER_FOUND`; it may still be `SEARCHING` when the demo ends.
- No driver accept, payment, notification, or Saga completion.
- Do not call the internal reservation API through the Gateway, mock Driver Service, or change production matching behavior for the demo.
- Fail if another candidate is available in the test area, either trip is assigned a different driver, both trips gain a driver, the database ownership differs, or a required service cannot be observed.

## Constraints and design

The existing `scripts/e2e-happy-path.sh` supplies the Gateway registration/login/setup pattern and isolated geographic cells. Create a separate `scripts/e2e-concurrency.sh` so the existing happy path remains usable. Reuse its Bash, curl and Docker Compose toolchain and avoid a new dependency. Generate a unique run ID and UUID correlation IDs. Search for an empty cell before placing the new driver; verify the available-driver list contains exactly that driver afterward.

Use background curl processes released by a common start signal and capture each status/body separately. Never infer a race from two sequential POSTs. After the requests, poll through the Gateway at a short interval. Read `driver_db.drivers` via `docker compose exec -T driver-db psql` for the final ownership and version assertion. Database access is read-only and is evidence, while passenger actions remain Gateway requests. The driver may move from `RESERVED` to `BUSY` before the final observation; `reserved_for_trip` must persist for the winning trip.

Handle the asynchronous boundary explicitly: the winning Trip may become `ASSIGNED` before Driver consumes `DriverAssigned`, so keep polling until both Trip and Driver observations agree. No cleanup before printing PASS, because cleanup would erase the state being demonstrated. A later run uses a fresh area and accounts.

## Approaches considered

1. **Automated Bash script through Gateway (selected):** repeatable concurrent requests, polling and evidence in one command; depends on a healthy Compose stack and read-only `psql` for the final database assertion.
2. Manual Postman/curl demonstration: fewer files, but timing is hard to reproduce and the evidence is easy to misread.

## Builder decisions left

Choose exact shell helper names, response parsing, polling interval and timeout defaults. Keep any parsing failures visible instead of treating a missing field as a successful empty value. Document a short invocation and sample expected output in `scripts/README.md`.
