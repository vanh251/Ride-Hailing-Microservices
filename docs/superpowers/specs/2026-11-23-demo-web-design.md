# Local ride demo web

The user selected the proposed Passenger + Driver + demo dashboard, with Docker Desktop and Grafana for service operations. This document records that authorized scope; implementation choices below are builder decisions.

## Approach

Serve buildless HTML/CSS/ES modules from the existing Gateway at `/demo/index.html`. This reuses same-origin API routing, JWT authorization and the existing Docker build. A separate frontend server would need proxy/CORS configuration; a dashboard controlling Docker would require another backend and is outside the selected approach. Local-only: no hosted deployment.

## Behavior

- Passenger view: independent Passenger A/B sessions, login, quick creation of two passengers and one driver for a fresh demo area, editable pickup/destination, quote, book, cancel, trip/payment/notification views.
- Driver view: login, report location, AVAILABLE/OFFLINE, select an assigned demo trip, accept/start/complete. Actions reflect current Trip state; acceptance is an API acknowledgement, not a new server state.
- Demo view: send two ride requests concurrently for one verified available candidate; compare observed trip ownership, show a client observation timeline, trip/correlation IDs and a nine-scenario runbook.
- Poll actual trips and driver every two seconds without overlapping cycles. Retain last good state and show errors/staleness during downtime. Never equate an old response with a healthy service.
- Copy Docker commands; link to configurable Grafana/Prometheus addresses. No Docker execution, internal API key, DB, Saga log or Kafka event reader is exposed to the browser. HTTP observations are labelled as such.
- Sessions are isolated per slot and stored in sessionStorage only; no password persistence. Clear sessions locally without deleting backend records. Setup refuses to overwrite active trips or an existing driver reservation.
- Responsive Vietnamese UI, keyboard-accessible controls, visible async/error feedback and no external assets/dependencies.

## Validation

Node built-in tests exercise request authentication, wrong-role/expired sessions, partial concurrent failures and real simultaneous dispatch. Gateway tests prove anonymous GET access to demo assets while protected API access remains protected. Browser QA against the isolated acceptance stack covers booking, driver actions, concurrency, cancellation and Payment stop/restart. Existing Gateway security/routing suite must pass.

## Workspace

Implement and verify in the native isolated worktree; preserve existing acceptance artifacts. Copy reviewed files back to the original checkout only if their baseline has not changed. No commit, merge, push or hosted publication is required.
