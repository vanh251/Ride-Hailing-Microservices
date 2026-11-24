# Location Search Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Search both route endpoints and show current/nearest locations and assigned pickup while retaining automatic matching.

**Architecture:** A small shared geocoding module handles Photon results and distance ordering. Shared DOM helpers wire explicit search and GPS to existing Leaflet pages. Existing APIs supply driver positions on pickup selection or explicit refresh. No real-time driver tracking; the 3-second poll only maintains trip state and the driver's existing own profile.

**Tech Stack:** Native browser modules, Leaflet 1.9.4, Photon, Node test runner.

**Spec:** `docs/superpowers/specs/2026-11-24-location-search-design.md`

## Global Constraints
- Keep automatic matching and existing access controls; never edit assigned trip coordinates from search.
- No additional frontend runtime dependencies or backend changes.
- Preserve unrelated uncommitted monitoring work in this checkout.

## Review Focus
- Stale search/quote/nearby responses after changing endpoints or booking.
- Denied GPS, HTTP failure, malformed coordinates and zero coordinates.
- Search text must not be submitted with coordinates of an old selection.
- Driver preview must never change driver location or assigned trip.
- Distinguish current GPS from pickup and handle missing driver location.

### Task 1: Shared search and location utilities
Files: create `src/api-gateway/src/main/resources/static/demo/geo.mjs`; modify `ride.mjs`, `ride.css`; test `scripts/tests/geo-test.mjs`.
Interfaces: `searchPlaces(query, center, {signal, fetcher})` returns address/latitude/longitude results; `nearbyByDistance(drivers, point)` returns located drivers ordered by computed `distanceKm`; `distanceKm(a,b)` returns Haversine distance. `placeSearch(kind,map,onSelect,onEdit)` binds existing address field and returns `clear()`; `locateMe(button,onPosition)` requests GPS with clear errors.
- [x] Write and run failing tests for GeoJSON coordinate order, invalid/empty results, no credential forwarding, failed requests, nearest ordering and zero coordinates.
- [x] Implement geocoding with explicit requests and 10-second timeout, result validation and shared search/GPS UI helpers.
- [x] Run `node --test scripts/tests/geo-test.mjs` and confirm green.

### Task 2: Passenger and driver integration
Files: modify `rider.html`, `driver.html`; add browser regression check `scripts/tests/location-ui-test.cjs`.
Consumes Task 1 interfaces. Produces both pages with explicit search, GPS and accurately labelled map markers.
- [x] Add browser assertions for selected search coordinates in booking, invalidation while editing, GPS success/denial, closest driver markers, no real-time tracking and driver preview isolation.
- [x] Run against existing pages and observe missing search controls.
- [x] Add passenger endpoint search, GPS marker, nearest-driver summary and stale-response guards. User excluded real-time tracking during implementation.
- [x] Add driver endpoint preview search, GPS reporting and assigned-customer pickup labels/distance.
- [x] Verify browser checks at desktop/mobile/tablet sizes and run all `scripts/tests/*test.mjs` Node tests; inspect diff.

## Execution notes
Proceed inline under user's implementation approval. Work in the existing develop checkout to preserve their local workspace; do not change branch, commit, publish or modify unrelated files. Browser checks stub only external/API boundaries; use real Leaflet and page code.

## Verification and review record
- Node suite: 20 tests passed (`node --test scripts/tests/*test.mjs`).
- Browser workflow: `scripts/tests/location-ui-test.cjs` passed; real Leaflet with HTTP boundary fixtures, desktop 1440px and mobile 390px, both roles.
- Browser races: `scripts/tests/location-races-test.cjs` reproduced stale nearest details/pins, late GPS overwrite, booking drag and hidden mobile/tablet pins before fixes, then passed after fixes (390px and 768px).
- Independent source review requested through `requesting-code-review`; findings addressed with the above regression checks.
- Photon live lookup from the shared module returned Văn Miếu coordinates without credentials. Public API reference: https://github.com/komoot/photon/blob/master/docs/api-v1.md.
- Gateway Docker build: successful; recreated only api-gateway via `docker compose up -d --no-deps api-gateway`. Health UP. All five deployed assets (`rider.html`, `driver.html`, `ride.mjs`, `ride.css`, `geo.mjs`) fetched from localhost:8080 and byte-matched against source.
- Changes are limited to static assets; no database/schema/backend changes. Browser screenshots/logs remain in `.playwright-mcp/` because automatic policy review rejected recursive artifact cleanup.
