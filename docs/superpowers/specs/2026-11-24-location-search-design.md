# Location search and map visibility

Approved in chat on 2026-11-24. Keep automatic matching. Updated during implementation: the user explicitly excluded real-time driver location display.

- Both pages search departure and destination by address/name and let users choose a map pin. Reuse Leaflet; no additional frontend dependencies.
- An explicit current-location button requests browser GPS. Passengers see a separate current-position marker and can use it as pickup before booking. Drivers report GPS through the existing own-location API.
- Passengers see available drivers within 3 km of pickup, nearest highlighted with straight-line distance. Refresh only on selecting pickup or explicit refresh; no assigned-driver tracking or background position refresh. Existing 3-second trip-status polling remains.
- Drivers see their location, the assigned customer's pickup, and destination. Search pins are previews and never modify an assigned trip or driver location.
- Use Photon public geocoding for this demo, only on explicit search, with timeout and visible errors. Keep map click and suggested places usable on service failure.
- Customer pickup is not claimed to be live customer GPS. Do not expose other passengers or add manual matching.

## Approaches
Reuse existing Leaflet, driver APIs and polling (chosen), rather than a new map SDK or WebSocket/backend location subsystem. Photon requires no key; a self-hosted or contracted geocoder is appropriate for production volume. No server changes needed for this scope.

## Failure behavior
Editing an address invalidates its selected coordinates and quote until a result/map point is selected. Ignore stale search, quote and driver responses. Handle denied/unavailable GPS and empty/failed searches without losing existing valid selections.
