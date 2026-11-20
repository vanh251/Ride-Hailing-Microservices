# API Reference

## Ride-Hailing Microservices System (DS03)

**Version:** 1.1.0  
**Date:** 18/10/2026  
**Base URL (via API Gateway):** `http://localhost:8080`  
**Authentication:** Bearer JWT (obtained from Identity Service login)

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Identity Service](#2-identity-service)
3. [Driver Service](#3-driver-service)
4. [Trip Service](#4-trip-service)
5. [Matching Service](#5-matching-service)
6. [Pricing Service](#6-pricing-service)
7. [Payment Service](#7-payment-service)
8. [Request / Response Conventions](#8-request--response-conventions)
9. [Error Codes](#9-error-codes)
10. [API Gateway Routing Table](#10-api-gateway-routing-table)

---

## 1. Authentication

All requests through the gateway, except the public routes listed below, require:

```
Authorization: Bearer <accessToken>
```

**Token format:** JWT (HS256), issued by Identity Service at `POST /api/auth/login`. Claims: `sub` = user id, `role`, `iat`, `exp`.
**Token expiry:** 24 hours (configurable via `JWT_EXPIRATION_MS`). There is no refresh token: after expiry the client logs in again.
**Signing key:** `JWT_SECRET` (Base64, at least 256 bits), the same value in identity-service, api-gateway and driver-service. A service does not start with a key that is not Base64 or is shorter than 256 bits.

The token is read only from the `Authorization` header with the scheme written `Bearer`. A token in the query string or a cookie, `Basic` credentials, or a token without the scheme count as no token.

How the whole system is protected, what is tested and the known limitations are described in [security.md](security.md).

### Verification at the API Gateway

The gateway verifies the token (signature, expiry, `sub` is a UUID, `role` is a known role) before it routes a request, and checks the role for the endpoints marked PASSENGER, DRIVER or admin below (the full list is in §10). "Own resource" checks stay in the backend service. identity-service and driver-service verify the token again themselves; trip, matching, pricing, payment and notification rely on the gateway.

| Case | Response |
|---|---|
| No `Authorization: Bearer` header on a protected route | `401 MISSING_TOKEN` |
| Bad signature, malformed token, missing `sub`/`role`, unknown role | `401 INVALID_TOKEN` |
| Expired token | `401 TOKEN_EXPIRED` |
| Valid token, role not allowed on the endpoint | `403 ACCESS_DENIED` |

The `401` and `403` answers use the standard error body of §8 with the `code` above; a `401` also carries `WWW-Authenticate: Bearer`.

Routes that need no token at the gateway: `POST /api/auth/register/passenger`, `POST /api/auth/register/driver`, `POST /api/auth/login`, `GET /api/drivers/available` and `/actuator/**`. They are public only for that method and exact path: `GET /api/auth/login` or `POST /api/auth/refresh`, for example, need a token. An invalid token on a public route is ignored, the request goes through as anonymous.

Endpoints marked "internal" are called service to service over Docker DNS, never through the gateway. The gateway does not route `POST /api/matching/{requestId}/rematch`, `POST /api/pricing/final`, `POST /api/payments/{tripId}/charge` or `POST /api/payments/{tripId}/refund` and answers `404` for them, with or without a token. `POST /api/matching/{requestId}/match` ("internal or admin") is routed for an `ADMIN` token only; any other role gets `403 ACCESS_DENIED` on every `/api/matching/**` path.

**`X-User-Id`:** for an authenticated request the gateway forwards the header `X-User-Id` = the token's `sub` (the user id) to the backend, together with the original `Authorization` header. An `X-User-Id` sent by the client is always dropped, on public routes too, so a backend behind the gateway can trust the header: it is either absent (anonymous request) or the verified user id.

**`X-User-Role`:** the gateway forwards the verified token's `role` for authenticated requests. A value sent by the client is always dropped, including on public routes.

### Role-based Access

| Role | Permissions |
|---|---|
| `PASSENGER` | Search nearby drivers, create ride, cancel own ride, view own trip |
| `DRIVER` | Update own status, update own location, accept/start/complete trip |
| `ADMIN` | Trigger matching manually (`POST /api/matching/{requestId}/match`). `ADMIN` cannot be self-registered. An `ADMIN` token passes the "any role" endpoints and gets `403` on the PASSENGER-only and DRIVER-only ones |

---

## 2. Identity Service

**Internal address:** `http://identity-service:8081`  
**Gateway prefix:** `/api/auth/**`, `/api/users/**`
**Swagger UI:** `http://localhost:8081/swagger-ui.html` (direct port only, not routed by the gateway)

Identity Service owns the accounts (`identity_db.users`) and is the only service that issues tokens. There are two public registration endpoints, one per role, and no way to register an `ADMIN`. Passwords are stored as BCrypt hashes and never returned or logged.

Demo accounts (10 drivers, 3 passengers) are created at startup when `SEED_DATA_ENABLED=true`: see `docs/test-data/identity/README.md`.

---

### POST /api/auth/register/passenger

Register a passenger account. The role is set by the endpoint (`PASSENGER`); a `role` field in the body is ignored.

**Auth required:** No

**Request Body:**
```json
{
  "fullName": "Nguyen Van A",
  "email": "user@example.com",
  "password": "securePassword123"
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| fullName | string | Yes | Not blank, at most 100 chars (stored trimmed) |
| email | string | Yes | Valid email, at most 255 chars, unique (stored trimmed and lower-cased) |
| password | string | Yes | 8-72 chars (BCrypt limit) |

**Response 201 Created:**
```json
{
  "id": "a1b2c3d4-...",
  "fullName": "Nguyen Van A",
  "email": "user@example.com",
  "role": "PASSENGER",
  "createdAt": "2026-09-30T10:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_ERROR` – Missing field, invalid email format, password length
- `400 MALFORMED_REQUEST` – Body is not valid JSON
- `409 EMAIL_ALREADY_REGISTERED` – Email already registered (compared after trimming and lower-casing)

---

### POST /api/auth/register/driver

Register a driver account together with the driver profile and vehicle. The account lives in `identity_db`, the profile and vehicle in `driver_db`, so this endpoint runs a saga with compensation (CODING_CONVENTION §30–31):

```
identity-service                                     driver-service
1. validate account fields (+ presence of driver fields)
2. INSERT user (role DRIVER) → COMMIT
3. POST /internal/drivers {userId, fullName, licenseNumber, vehicle}  ──►  validate formats, INSERT drivers + vehicles
   (timeouts: connect 1s, read 2s)
4a. 201                    → return 201
4b. 400 / 409              → driver-service created nothing → delete user → return the same 400 / 409 code
4c. timeout / 5xx / down   → outcome unknown → DELETE /internal/drivers?userId=… (idempotent)
                             → delete user → 503 DRIVER_SERVICE_UNAVAILABLE
                             (a failed compensation is logged at ERROR with the userId for manual cleanup)
```

> Known limitations (accepted for this project, no reconciliation job yet):
> - Between steps 2 and 4b/4c (a few seconds at most, bounded by the timeouts) the account exists, so a login with the same credentials during that window would get a token for an account that is then deleted.
> - In 4c, if driver-service is still processing the create when the compensating DELETE arrives, the DELETE finds nothing (204) and the profile is committed afterwards, leaving an orphan profile whose plate stays taken. identity-service cannot detect this; it shows up as `409 LICENSE_PLATE_ALREADY_REGISTERED` on a retry and must be removed by hand.

**Auth required:** No

**Request Body:**
```json
{
  "fullName": "Tran Van B",
  "email": "driver@example.com",
  "password": "securePassword123",
  "licenseNumber": "012345678901",
  "vehicle": {
    "licensePlate": "29A-123.45",
    "make": "Toyota",
    "model": "Vios",
    "color": "White",
    "year": 2022,
    "vehicleType": "SEDAN"
  }
}
```

| Field | Type | Required | Constraints (checked by) |
|---|---|---|---|
| fullName, email, password | | Yes | Same as passenger (identity) |
| licenseNumber | string | Yes | Exactly 12 digits (driver) |
| vehicle.licensePlate | string | Yes | `29A-12345`, `29A-123.45`, `29A-1234`, `29B1-12345`, `30LD-12345`; stored upper-case without dots; unique (driver) |
| vehicle.make / model | string | Yes | 1-50 chars (driver) |
| vehicle.color | string | Yes | 1-30 chars (driver) |
| vehicle.year | int | Yes | 1980 to next year (driver) |
| vehicle.vehicleType | enum | Yes | `SEDAN`, `SUV`, `MOTORBIKE`, `VAN` (driver) |

**Response 201 Created:**
```json
{
  "id": "a1b2c3d4-...",
  "fullName": "Tran Van B",
  "email": "driver@example.com",
  "role": "DRIVER",
  "createdAt": "2026-09-30T10:00:00Z",
  "driver": {
    "driverId": "a1b2c3d4-...",
    "status": "OFFLINE",
    "licenseNumber": "012345678901",
    "vehicle": {
      "vehicleId": "v001-...",
      "licensePlate": "29A-12345",
      "make": "Toyota",
      "model": "Vios",
      "color": "White",
      "year": 2022,
      "vehicleType": "SEDAN"
    }
  }
}
```

**Errors:**
- `400 VALIDATION_ERROR` – Missing field, or a format rejected by driver-service (plate, license number, year)
- `400 MALFORMED_REQUEST` – Unknown `vehicleType`
- `409 EMAIL_ALREADY_REGISTERED` – Email already registered
- `409 LICENSE_PLATE_ALREADY_REGISTERED` – Plate already registered
- `503 DRIVER_SERVICE_UNAVAILABLE` – driver-service did not answer in time; nothing was kept, the client may retry
- `500 DRIVER_REGISTRATION_FAILED` – driver-service refused the internal call (e.g. the two services have different `INTERNAL_API_KEY` values); the account was removed again. A deployment error, not a client error

---

### POST /api/auth/login

Authenticate with email and password and receive a JWT access token.

**Auth required:** No

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "securePassword123"
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| email | string | Yes | Valid email, at most 255 chars (trimmed and lower-cased before the lookup) |
| password | string | Yes | At most 72 chars |

**Response 200 OK:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "userId": "a1b2c3d4-...",
  "role": "PASSENGER"
}
```

`expiresIn` is the token lifetime in seconds (`JWT_EXPIRATION_MS` / 1000). The token is an HS256 JWT with these claims:

| Claim | Value |
|---|---|
| `sub` | The user id (same value as `userId`; for a driver also the `driverId`) |
| `role` | `PASSENGER`, `DRIVER` or `ADMIN` |
| `iat`, `exp` | Issued-at and expiry time (seconds since the epoch) |

There is no refresh token and no logout endpoint: a token is valid until `exp`, then the client logs in again.

**Errors:**
- `400 VALIDATION_ERROR` – Missing email or password, invalid email format, password longer than 72 chars
- `400 MALFORMED_REQUEST` – Body is not valid JSON
- `401 INVALID_CREDENTIALS` – Unknown email or wrong password. Both cases return the same response, so the endpoint does not reveal which emails are registered

---

### GET /api/users/{id}

Get user profile.

**Auth required:** Yes (any authenticated user)

**Path Parameters:**

| Param | Type | Description |
|---|---|---|
| id | UUID | User ID |

**Response 200 OK:**
```json
{
  "id": "a1b2c3d4-...",
  "fullName": "Nguyen Van A",
  "email": "user@example.com",
  "role": "PASSENGER",
  "createdAt": "2026-09-30T10:00:00Z"
}
```

Any authenticated user may read any profile, including the email address: there is no "own profile only" rule on this endpoint. The password hash is never part of the response.

**Errors:**
- `400 INVALID_PARAMETER` – `{id}` is not a UUID
- `401 MISSING_TOKEN` / `INVALID_TOKEN` / `TOKEN_EXPIRED` – No valid JWT (see §1)
- `404 USER_NOT_FOUND` – No user with this id

---

## 3. Driver Service

**Internal address:** `http://driver-service:8082`  
**Gateway prefix:** `/api/drivers/*`, `/api/drivers/*/status`, `/api/drivers/*/location` (the reserve endpoints and `/internal/**` are not routed, see §10)
**Swagger UI:** `http://localhost:8082/swagger-ui.html` (direct port only, not routed by the gateway)

Driver Service verifies the JWT itself as well (same `JWT_SECRET` as Identity Service), so the role and "own resource" rules below also hold for a call that does not come through the gateway. Service-to-service endpoints take the header `X-Internal-Api-Key` instead of a JWT.

> **Driver id = user id.** A driver's id is its identity `userId` (`drivers.driver_id = users.user_id`, see database_schema.md). The `{id}` in every `/api/drivers/{id}` URL, the JWT `sub` of a driver, the `X-User-Id` header and every `driverId` field (matching, trip, events) carry the same value.

---

### GET /api/drivers/{id}

Get driver details: profile, active vehicle and last known position.

**Auth required:** Yes (any role)

**Response 200 OK:**
```json
{
  "id": "d0000000-0000-4000-8000-000000000001",
  "fullName": "Tran Van Binh",
  "status": "AVAILABLE",
  "vehicle": {
    "vehicleId": "7c9e6679-...",
    "licensePlate": "30A-10001",
    "make": "Toyota",
    "model": "Vios",
    "color": "White",
    "year": 2021,
    "vehicleType": "SEDAN"
  },
  "location": {
    "latitude": 21.025,
    "longitude": 105.857,
    "updatedAt": "2026-09-30T10:05:00Z"
  }
}
```

`id` is the driver's user id (no separate `userId` field). `vehicle` is `null` when the driver has no active vehicle. `location` is `null` when the driver never reported a position, and also when the driver is `OFFLINE` and the caller is someone else: an off-duty driver's last position (often home) is shown only to that driver.

**Errors:**
- `400 INVALID_PARAMETER` – `{id}` is not a UUID
- `401` – Missing, invalid or expired JWT
- `404 DRIVER_NOT_FOUND` – No driver with this id

---

### GET /api/drivers/available

List AVAILABLE drivers near a point: the drivers within `radiusKm` (Haversine distance), nearest first, one page at a time. Same search as `/nearby`, plus the `version` and `status` Matching Service needs.

**Auth required:** No token or internal key is checked for this endpoint. Matching calls driver-service directly and supplies its internal key, but the gateway also exposes this endpoint publicly. The required point and the `radiusKm` / `size` caps keep a caller from listing the whole fleet in one request. With `page`, a caller can still walk through every available driver within 50 km of a point, 50 at a time.

**Query Parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| latitude | double | Yes | Reference point (pickup) latitude, -90..90 |
| longitude | double | Yes | Reference point (pickup) longitude, -180..180 |
| radiusKm | double | No | Search radius in km (default: 5.0, max 50) |
| page | int | No | Page number, from 0 (default: 0) |
| size | int | No | Drivers per page, 1..50. When absent, `limit` is used |
| sort | string | No | `distanceKm,asc` (nearest first, default) or `distanceKm,desc` |
| limit | int | No | Older name of `size` (default: 20, 1..50); ignored when `size` is sent |

**Response 200 OK:**
```json
[
  {
    "id": "d0000000-0000-4000-8000-000000000001",
    "fullName": "Tran Van Binh",
    "status": "AVAILABLE",
    "location": {
      "latitude": 21.025,
      "longitude": 105.857,
      "updatedAt": "2026-09-30T10:05:00Z"
    },
    "distanceKm": 0.49,
    "version": 3
  }
]
```

`version` is the value Matching Service sends as `expectedVersion` to `POST /api/drivers/{id}/reserve` (CAS). Drivers that never reported a position are not listed.

Paging follows §8: the response is the array of the requested page, and a page after the last one is an empty array. A call without `page`, `size` and `sort` returns the 20 nearest drivers, as before.

**Errors:**
- `400 VALIDATION_ERROR` – Missing `latitude` / `longitude`, coordinate out of range, `radiusKm` ≤ 0 or > 50, `limit` or `size` outside 1..50, `page` < 0, `sort` other than `distanceKm,asc` / `distanceKm,desc`
- `400 INVALID_PARAMETER` – Non-numeric parameter

---

### GET /api/drivers/nearby

Query nearby AVAILABLE drivers (spatial search): the drivers within `radiusKm` (Haversine distance), nearest first, one page at a time (§8).

**Auth required:** Yes (PASSENGER). Matching Service uses `GET /api/drivers/available` instead, so no internal caller needs this endpoint today. The role rule covers every HTTP method on this path, so `HEAD` (which is answered like `GET`) needs a PASSENGER token too.

**Query Parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| latitude | double | Yes | Pickup latitude, -90..90 |
| longitude | double | Yes | Pickup longitude, -180..180 |
| radiusKm | double | No | Radius in km (default: 5.0, max 50) |
| page | int | No | Page number, from 0 (default: 0) |
| size | int | No | Drivers per page, 1-50. When absent, `limit` is used |
| sort | string | No | `distanceKm,asc` (nearest first, default) or `distanceKm,desc` |
| limit | int | No | Older name of `size` (default: 10, 1-50); ignored when `size` is sent |

**Response 200 OK:**
```json
[
  {
    "id": "d0000000-0000-4000-8000-000000000001",
    "fullName": "Tran Van Binh",
    "distanceKm": 0.49,
    "location": {
      "latitude": 21.025,
      "longitude": 105.857,
      "updatedAt": "2026-09-30T10:05:00Z"
    }
  }
]
```

**Errors:**
- `400 VALIDATION_ERROR` – Missing coordinate, value out of range, `limit` or `size` outside 1-50, `page` < 0, unknown `sort`
- `400 INVALID_PARAMETER` – Non-numeric parameter
- `401 MISSING_TOKEN` / `INVALID_TOKEN` / `TOKEN_EXPIRED` – No valid JWT
- `403 ACCESS_DENIED` – Token role is not `PASSENGER`

---

### PATCH /api/drivers/{id}/status

Update driver availability status.

**Auth required:** Yes (DRIVER role, own resource)

**Request Body:**
```json
{
  "status": "AVAILABLE"
}
```

**Allowed transitions via this endpoint (driver):**

| From | To |
|---|---|
| OFFLINE | AVAILABLE |
| AVAILABLE | OFFLINE |

Requesting `RESERVED`/`BUSY`, or any transition from `RESERVED`/`BUSY` (e.g. going `OFFLINE` mid-trip) → `422`. A value that is not a `DriverStatus` at all → `400`.

**Driver state machine (full reference, `DriverStatus` — CODING_CONVENTION §12.2):**

| From | To | Triggered by |
|---|---|---|
| OFFLINE | AVAILABLE | Driver: `PATCH /api/drivers/{id}/status` |
| AVAILABLE | OFFLINE | Driver: `PATCH /api/drivers/{id}/status` |
| AVAILABLE | RESERVED | Matching Service: `POST /api/drivers/{id}/reserve` (CAS) |
| RESERVED | AVAILABLE | `DELETE /api/drivers/{id}/reserve` (compensation), lease expiry (`reserved_until`), or event `TripCancelled` |
| RESERVED | BUSY | Event `DriverAssigned` after exact reservation version and live lease are atomically checked (W5 Driver implementation pending; docs/events.md §4.3) |
| BUSY | AVAILABLE | Event `TripCompleted` (§4.5) or `TripCancelled` (§4.8) |

System transitions are never done through this endpoint. Event-driven transitions only apply when `drivers.reserved_for_trip` equals the event's `tripId`.

Requesting the current status (e.g. `AVAILABLE` while already `AVAILABLE`) is an idempotent no-op that returns `200`.

**Response 200 OK:**
```json
{
  "id": "d0000000-0000-4000-8000-000000000001",
  "status": "AVAILABLE",
  "updatedAt": "2026-09-30T10:10:00Z"
}
```

**Errors:**
- `400 VALIDATION_ERROR` / `400 MALFORMED_REQUEST` – Missing / unknown status value
- `401 MISSING_TOKEN` / `INVALID_TOKEN` / `TOKEN_EXPIRED` – No valid JWT
- `403 ACCESS_DENIED` – Token role is not `DRIVER`
- `403 NOT_OWN_RESOURCE` – `{id}` is not the caller's user id
- `404 DRIVER_NOT_FOUND` – The account has no driver profile
- `409 CONCURRENT_UPDATE` – The driver changed meanwhile (e.g. reserved by Matching Service); retry
- `422 INVALID_STATUS_TRANSITION` – Invalid status transition

---

### PATCH /api/drivers/{id}/location

Update driver GPS location (simulated).

**Auth required:** Yes (DRIVER role, own resource)

**Request Body:**
```json
{
  "latitude": 21.0300,
  "longitude": 105.8560
}
```

Stored with 6 decimals (`DECIMAL(9, 6)`) together with `last_heartbeat_at`. A position report does **not** change the driver's `version`, so the app can report every few seconds without making a pending reservation (CAS on `version`) fail.

**Response 200 OK:**
```json
{
  "id": "d0000000-0000-4000-8000-000000000001",
  "location": {
    "latitude": 21.0300,
    "longitude": 105.8560,
    "updatedAt": "2026-09-30T10:11:00Z"
  }
}
```

**Errors:**
- `400 VALIDATION_ERROR` – Missing coordinate, latitude outside -90..90 or longitude outside -180..180
- `401` – Missing, invalid or expired JWT
- `403 ACCESS_DENIED` / `NOT_OWN_RESOURCE` – Not a DRIVER token / not the caller's own profile
- `404 DRIVER_NOT_FOUND` – The account has no driver profile

---

### POST /api/drivers/{id}/reserve

Reserve a driver (CAS lock with 30s lease). Used internally by Matching Service.

**Auth required:** `X-Internal-Api-Key` (value of `INTERNAL_API_KEY`), no JWT. Matching calls this endpoint directly and also sends `X-Correlation-ID`. Missing or wrong key returns `401 INVALID_INTERNAL_API_KEY`.

**Request Body:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "expectedVersion": 5
}
```

**Response 200 OK:**
```json
{
  "driverId": "d0000000-0000-4000-8000-000000000001",
  "status": "RESERVED",
  "reservedForTrip": "550e8400-e29b-41d4-a716-446655440000",
  "reservedUntil": "2026-09-30T10:12:30Z",
  "version": 6
}
```

The reservation is one atomic `UPDATE ... WHERE status = 'AVAILABLE' AND version = expectedVersion`, so of several concurrent requests for the same driver exactly one succeeds. `expectedVersion` is the `version` returned by `GET /api/drivers/available`.

**Lease:** `reservedUntil` is 30 seconds after the reservation (`DRIVER_RESERVATION_LEASE_SECONDS`). A driver still `RESERVED` after that returns to `AVAILABLE` on its own (`reserved_for_trip` and `reserved_until` cleared, `version + 1`); expired leases are looked for every 5 seconds (`DRIVER_RESERVATION_EXPIRY_CHECK_MS`), so the release happens 30–35 seconds after the reservation.

**Errors:**
- `400 VALIDATION_ERROR` / `400 MALFORMED_REQUEST` – Missing `tripId` / `expectedVersion`, negative `expectedVersion`, or `tripId` not a UUID
- `400 INVALID_PARAMETER` – `{id}` is not a UUID
- `401 INVALID_INTERNAL_API_KEY` – Missing or wrong `X-Internal-Api-Key`
- `404 DRIVER_NOT_FOUND` – No driver with this id
- `409 DRIVER_ALREADY_RESERVED` – The driver is not `AVAILABLE` or its version is no longer `expectedVersion` (CAS failed – affectedRows = 0)

---

### DELETE /api/drivers/{id}/reserve

Release a RESERVED driver reservation. The `tripId` query parameter is required: `DELETE /api/drivers/{id}/reserve?tripId={tripId}`. Driver-service releases the reservation only if its `reserved_for_trip` matches `tripId`. A late request for an older trip cannot release a newer reservation.

**Auth required:** `X-Internal-Api-Key` (value of `INTERNAL_API_KEY`), no JWT. Called directly by Matching for compensation and by Trip for cancellation.

**Response 204 No Content**, including an already released or non-matching reservation. Driver owns the 30-second lease cleanup; Matching does not run another driver lease timer.

Only a `RESERVED` driver owned by `tripId` is released (`reserved_for_trip` and `reserved_until` cleared, `version + 1`). The ownership and status conditions are checked in the same atomic database update. The call is idempotent: a driver in any other status or reserved for another trip is left unchanged and the response is still `204`. A `BUSY` driver is released by the `TripCompleted` / `TripCancelled` events, not by this endpoint.

**Errors:**
- `400` – `tripId` is missing or not a UUID, or `{id}` is not a UUID
- `401 INVALID_INTERNAL_API_KEY` – Missing or wrong `X-Internal-Api-Key`
- `404 DRIVER_NOT_FOUND` – No driver with this id

---

### Internal APIs (identity-service → driver-service)

Service-to-service endpoints used by `POST /api/auth/register/driver`. They are **not routed by the API Gateway** and require the header `X-Internal-Api-Key` (value of `INTERNAL_API_KEY`, shared by both services) instead of a JWT. Missing or wrong key → `401 INVALID_INTERNAL_API_KEY`.

#### POST /internal/drivers

Create the driver profile (status `OFFLINE`) and its vehicle.

**Request Body:**
```json
{
  "userId": "a1b2c3d4-...",
  "fullName": "Tran Van B",
  "licenseNumber": "012345678901",
  "vehicle": { "licensePlate": "29A-123.45", "make": "Toyota", "model": "Vios", "color": "White", "year": 2022, "vehicleType": "SEDAN" }
}
```

`userId` becomes the `driverId`; `fullName` is stored as a copy so driver lists (`/available`, `/nearby`) need no call to identity-service.

**Response 201 Created:** `{ "driverId" (= userId), "fullName", "status": "OFFLINE", "licenseNumber", "vehicle": {...}, "createdAt" }`

**Errors:** `400` validation, `401` internal key, `409 DRIVER_PROFILE_EXISTS` (one profile per user), `409 LICENSE_PLATE_ALREADY_REGISTERED`

#### DELETE /internal/drivers?userId={userId}

Compensation step. Deletes the profile (`driver_id = userId`) and vehicle of the user. Idempotent: `204` also when no profile exists. Only a profile that is `OFFLINE` and not reserved can be deleted, otherwise `409 DRIVER_PROFILE_IN_USE`.

**Response 204 No Content**

---

### Events consumed (Kafka)

Driver Service has no REST endpoint for the system transitions `RESERVED → BUSY` and `BUSY → AVAILABLE`: it consumes `trip-events` (consumer group `driver-service-group`). The full contract is in docs/events.md §4.3, §4.5, §4.8.

| Event | Driver changed | Change |
|---|---|---|
| `DriverAssigned` | `payload.driverId`, `payload.tripId`, `payload.reservationVersion`, live `reserved_until` | If all predicates match atomically: `RESERVED → BUSY`, clear `reserved_until`, increment version, emit `DriverAssignmentConfirmed`; otherwise emit `DriverAssignmentRejected`. Member 1 rollout pending |
| `TripCompleted` | The driver whose `reserved_for_trip` is `payload.tripId`, if `BUSY` | `→ AVAILABLE`, `reserved_for_trip` and `reserved_until` cleared, `version + 1` |
| `TripCancelled` | The driver whose `reserved_for_trip` is `payload.tripId`, if `RESERVED` or `BUSY` | Same as `TripCompleted` |

- An event that matches no driver (no driver yet, lease already expired, driver already on another trip) is logged and skipped.
- Each `eventId` takes effect once: it is stored in `processed_events` in the same transaction as the status change, and a redelivery is skipped.
- A malformed event (not JSON, `eventId` or `tripId` not a UUID, `DriverAssigned` without `driverId`) goes straight to `trip-events.DLQ`. Any other failure is retried 3 times (1s, 2s, 4s) and then sent to the DLQ.
- Other event types on `trip-events` are ignored.

---

## 4. Trip Service

**Internal address:** `http://trip-service:8083`  
**Gateway prefix:** `/api/trips/**`

---

### POST /api/trips

Create a ride request. Triggers matching flow.

**Auth required:** Yes (PASSENGER role)

**Request Body:**
```json
{
  "pickup": {
    "latitude": 21.0285,
    "longitude": 105.8542,
    "address": "Hoan Kiem, Hanoi"
  },
  "destination": {
    "latitude": 21.0188,
    "longitude": 105.8520,
    "address": "Dong Da, Hanoi"
  }
}
```

**Response 201 Created:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "SEARCHING",
  "passengerId": "a0000000-0000-4000-8000-000000000001",
  "pickup": {
    "latitude": 21.0285,
    "longitude": 105.8542,
    "address": "Hoan Kiem, Hanoi"
  },
  "destination": {
    "latitude": 21.0188,
    "longitude": 105.8520,
    "address": "Dong Da, Hanoi"
  },
  "estimatedFare": 45000,
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "createdAt": "2026-09-30T10:00:00Z"
}
```

Side effects:
- Publishes `trip.requested` event to Kafka
- Saga step: `STARTED` written to `saga_log`

---

### GET /api/trips/{id}

Get trip details.

**Auth required:** Yes

**Response 200 OK:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "ASSIGNED",
  "passengerId": "a0000000-0000-4000-8000-000000000001",
  "driverId": "d0000000-0000-4000-8000-000000000001",
  "pickup": { "latitude": 21.0285, "longitude": 105.8542 },
  "destination": { "latitude": 21.0188, "longitude": 105.8520 },
  "estimatedFare": 45000,
  "finalFare": null,
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "createdAt": "2026-09-30T10:00:00Z",
  "updatedAt": "2026-09-30T10:02:00Z"
}
```

---

### POST /api/trips/{id}/accept

Driver accepts assigned trip.

**Auth required:** Yes (DRIVER role)

**Response 200 OK:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "ASSIGNED",
  "driverId": "d0000000-0000-4000-8000-000000000001",
  "updatedAt": "2026-09-30T10:03:00Z"
}
```

---

### POST /api/trips/{id}/start

Driver starts the trip (picks up passenger).

**Auth required:** Yes (DRIVER role)

**Response 200 OK:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "IN_PROGRESS",
  "startedAt": "2026-09-30T10:05:00Z"
}
```

---

### POST /api/trips/{id}/complete

Driver completes the trip (arrives at destination).

**Auth required:** Yes (DRIVER role)

**Response 200 OK:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "finalFare": 47500,
  "completedAt": "2026-09-30T10:25:00Z"
}
```

Side effects:
- Calls Pricing Service `POST /api/pricing/final` synchronously; its `totalFare` becomes `finalFare`
- Publishes `trip.completed` event to Kafka with the full payload of docs/events.md §4.5 (`passengerId`, `finalFare`, `currency`, …)
- Payment Service charges `finalFare` asynchronously when it consumes that event; it does not call Pricing again

---

### POST /api/trips/{id}/cancel

Cancel a trip. Valid when status is `SEARCHING` or `ASSIGNED`.

**Auth required:** Yes (PASSENGER role, own trip)

**Request Body (optional):**
```json
{
  "reason": "Changed plans"
}
```

**Response 200 OK:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "CANCELLED",
  "cancelledAt": "2026-09-30T10:01:30Z"
}
```

Side effects:
- Compensation: `DELETE /api/drivers/{driverId}/reserve` (if driver was RESERVED/ASSIGNED), as required by SRS §13.2
- Publishes `trip.cancelled` event to Kafka; driver-service also releases the driver when it consumes it

> Both compensation paths may run for the same cancellation. Each acts only while `drivers.reserved_for_trip` still equals this trip, so the second one finds nothing to release (see docs/events.md §4.8).

---

## 5. Matching Service

**Internal address:** `http://matching-service:8084`  
**Gateway route:** `/api/matching/*/match` only, `ADMIN` token (rematch is not routed, see §10)

> Note: Matching is primarily driven by Kafka events. REST endpoints exist for manual trigger and testing.

---

### POST /api/matching/{requestId}/match

Manually trigger matching for a ride request.

**Auth required:** Yes (internal or admin). Through the gateway only an `ADMIN` token is accepted, other roles get `403 ACCESS_DENIED`; internal callers call matching-service directly. matching-service itself also requires `X-Internal-Api-Key`.

**Response 202 Accepted:**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "MATCHING",
  "message": "Matching process initiated"
}
```

---

### POST /api/matching/{requestId}/rematch

Trigger rematch when initial driver rejected.

**Auth required:** Yes (internal). Not routed by the gateway (`404`).

**Response 202 Accepted:**
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "REMATCHING",
  "attempt": 2
}
```

---

## 6. Pricing Service

**Internal address:** `http://pricing-service:8085`  
**Gateway route:** `/api/pricing/quote` only (`/final` is not routed, see §10)

> Note: Pricing Service is stateless. No database. Called synchronously by Trip Service only: `/quote` when a trip is created, `/final` when it is completed. Payment Service never calls Pricing; it charges the `finalFare` carried by `TripCompleted`.
>
> Distance and duration of a quote are always estimated by Pricing Service from the coordinates (route distance = straight line × 1.25, 20 km/h city speed). The request has no field to override them.

---

### POST /api/pricing/quote

Calculate estimated fare for a trip.

**Auth required:** Yes

**Request Body:**
```json
{
  "pickup": {
    "latitude": 21.0285,
    "longitude": 105.8542
  },
  "destination": {
    "latitude": 21.0188,
    "longitude": 105.8520
  }
}
```

**Response 200 OK:**
```json
{
  "estimatedDistanceKm": 2.5,
  "estimatedDurationMin": 12,
  "baseFare": 15000,
  "distanceFare": 12500,
  "timeFare": 6000,
  "totalFare": 33500,
  "currency": "VND"
}
```

**Fare formula:**
```
total = baseFare + (distanceKm * distanceRate) + (durationMin * timeRate)
```

---

### POST /api/pricing/final

Calculate final fare after trip completion (actual distance/duration).

**Auth required:** Yes (internal). Not routed by the gateway (`404`): Trip Service calls `http://pricing-service:8085` directly.

**Request Body:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "actualDistanceKm": 2.8,
  "actualDurationMin": 15
}
```

**Response 200 OK:**
```json
{
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "baseFare": 15000,
  "distanceFare": 14000,
  "timeFare": 7500,
  "totalFare": 36500,
  "currency": "VND"
}
```

---

## 7. Payment Service

**Internal address:** `http://payment-service:8086`  
**Gateway route:** `/api/payments/*` only, i.e. `GET /api/payments/{tripId}` (`/charge` and `/refund` are not routed, see §10)

> Note: In the business flow a payment is triggered **only** by the Kafka event `TripCompleted` (docs/events.md §4.5); Trip Service never calls `/charge`. The REST charge exists for manual tests and the status/refund endpoints for checks and compensation.
>
> Failures can be simulated with `PAYMENT_FAILURE_MODE` = `none` (default) / `always` / `rate` (with `PAYMENT_FAILURE_RATE` between 0 and 1). A declined charge is stored as `FAILED` and publishes `PaymentFailed`.

---

### POST /api/payments/{tripId}/charge

Charge a passenger for a trip (manual test path).

**Auth required:** Yes (internal). Not routed by the gateway (`404`): manual tests call `http://localhost:8086` directly.

**Path Parameters:** `tripId` – UUID

**Headers:** `X-Idempotency-Key` – required (the body field `idempotencyKey` is accepted when the header is absent)

**Request Body:**
```json
{
  "passengerId": "a0000000-0000-4000-8000-000000000001",
  "amount": 36500,
  "currency": "VND",
  "idempotencyKey": "charge-550e8400-e29b-41d4-a716-446655440000-1"
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| passengerId | UUID | Yes | |
| amount | number | Yes | > 0, at most 8 integer digits and 2 decimals |
| currency | string | No | 3 upper-case letters (ISO 4217), default `VND` |
| idempotencyKey | string | No | max 255 chars; used only when the header is absent |

> Repeating the call with the same idempotency key returns the first result (the same `paymentId`, or `402` again if it was declined) and never charges twice. A trip has at most one `SUCCESS` payment.

**Response 201 Created:**
```json
{
  "paymentId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "SUCCESS",
  "amount": 36500,
  "currency": "VND",
  "processedAt": "2026-09-30T10:26:00Z"
}
```

**Errors:**
- `400 VALIDATION_ERROR` – Missing field, amount ≤ 0, invalid currency
- `400 MALFORMED_REQUEST` – Body is not valid JSON or `passengerId` is not a UUID
- `400 INVALID_PARAMETER` – `tripId` is not a UUID
- `400 IDEMPOTENCY_KEY_REQUIRED` – No idempotency key in header or body
- `402 PAYMENT_FAILED` – Simulated payment failure (the `FAILED` payment is kept)
- `409 IDEMPOTENCY_CONFLICT` – Key already used for a different trip or amount, or the same key is being processed concurrently
- `409 PAYMENT_ALREADY_CAPTURED` – The trip already has a `SUCCESS` payment

---

### POST /api/payments/{tripId}/refund

Refund the successful payment of a trip (compensating action for cancellation after charge). Writes a negative `REFUND` ledger entry. Refunding an already refunded payment returns it unchanged.

**Auth required:** Yes (internal). Not routed by the gateway (`404`).

**Response 200 OK:**
```json
{
  "paymentId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "REFUNDED",
  "refundedAt": "2026-09-30T10:30:00Z"
}
```

**Errors:**
- `400 INVALID_PARAMETER` – `tripId` is not a UUID
- `404 RESOURCE_NOT_FOUND` – No payment for this trip
- `409 INVALID_REFUND_STATE` – Latest payment is not `SUCCESS` (e.g. `FAILED`)
- `409 CONCURRENT_UPDATE` – The payment was changed by another request; retry

---

### GET /api/payments/{tripId}

Get payment status for a trip.

**Auth required:** Yes

**Response 200 OK:**
```json
{
  "paymentId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
  "tripId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "SUCCESS",
  "amount": 36500,
  "currency": "VND",
  "processedAt": "2026-09-30T10:26:00Z"
}
```

Returns the latest payment of the trip. **Errors:** `400 INVALID_PARAMETER` (not a UUID), `404 RESOURCE_NOT_FOUND`.

**Payment Status Values** (CODING_CONVENTION §12.4):

| Status | Description |
|---|---|
| `PENDING` | Payment initiated, not yet processed |
| `SUCCESS` | Payment captured successfully |
| `FAILED` | Payment failed (declined) |
| `REFUNDED` | Payment refunded |

---

## 8. Request / Response Conventions

### Request Headers

| Header | Required | Description |
|---|---|---|
| `Authorization` | Yes (for protected routes) | `Bearer <jwt_token>` |
| `Content-Type` | Yes (for POST/PATCH) | `application/json` |
| `X-Correlation-ID` | No (auto-generated if absent) | Trace ID for distributed tracing |
| `X-Request-ID` | No | Unique per-request ID |
| `X-User-Id` | Set by the gateway, never by clients | User id (`sub`) of the verified token; a value sent by the client is dropped (§1) |
| `X-User-Role` | Set by the gateway, never by clients | Role of the verified token; a value sent by the client is dropped (§1) |
| `X-Idempotency-Key` | Required for payment charge (or `idempotencyKey` in the body) | Prevents duplicate operations |
| `X-Internal-Api-Key` | Required for `/internal/**` and `POST`/`DELETE /api/drivers/{id}/reserve` (service-to-service only) | Shared secret `INTERNAL_API_KEY`; never sent by clients |

### Pagination (for list endpoints)

```
GET /api/drivers/available?latitude=21.0285&longitude=105.8542&page=0&size=10&sort=distanceKm,asc
```

| Param | Default | Description |
|---|---|---|
| `page` | `0` | Page number, starting at 0 |
| `size` | endpoint default | Items per page, 1..50 |
| `sort` | `distanceKm,asc` | `<field>,<asc or desc>`; the direction may be left out (`asc`) |

The response is the JSON array of that page, with no wrapper and no total count: a page with fewer than `size` items, or an empty array, is the last one. Implemented by `GET /api/drivers/available` and `GET /api/drivers/nearby` (§3), where `distanceKm` is the only sortable field. An out-of-range `page` or `size`, or an unknown `sort`, returns `400 VALIDATION_ERROR`.

### Identifiers and Money

- Resource ids (`tripId`, `passengerId`, `driverId`, `paymentId`) are plain UUIDs.
- Money is a JSON number without quotes, sent together with `currency`. VND amounts are whole numbers (e.g. `36500`); values read back from the database may carry two decimals (`36500.00`), which is the same amount. Services hold money as `BigDecimal`.

### Timestamps

All timestamps are ISO 8601 UTC:

```
"2026-09-30T10:00:00Z"
```

### Common Response Wrapper

Successful responses return the resource directly (no wrapper envelope).

Error responses follow:

```json
{
  "timestamp": "2026-09-30T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Email already registered",
  "path": "/api/auth/register/passenger",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "code": "EMAIL_ALREADY_REGISTERED"
}
```

---

## 9. Error Codes

| HTTP Status | Meaning | Common Causes |
|---|---|---|
| 200 | OK | Successful GET, PATCH, DELETE |
| 201 | Created | Successful POST (resource created) |
| 202 | Accepted | Async operation initiated |
| 204 | No Content | Successful DELETE with no body |
| 400 | Bad Request | Validation failure, missing fields, invalid enum |
| 401 | Unauthorized | Missing or expired JWT |
| 403 | Forbidden | Valid JWT but insufficient role |
| 404 | Not Found | Resource does not exist |
| 409 | Conflict | Duplicate email, duplicate idempotency key, CAS conflict |
| 402 | Payment Required | Simulated payment failure |
| 422 | Unprocessable Entity | Business rule violation (e.g., invalid state transition) |
| 500 | Internal Server Error | Unexpected server error |
| 503 | Service Unavailable | Downstream service unreachable |

---

## 10. API Gateway Routing Table

All client requests enter via `http://localhost:8080`.

| Gateway Path | Target Service | Target URL |
|---|---|---|
| `/api/auth/**` | identity-service | `http://identity-service:8081/api/auth/**` |
| `/api/users/**` | identity-service | `http://identity-service:8081/api/users/**` |
| `/api/drivers/*`, `/api/drivers/*/status`, `/api/drivers/*/location` | driver-service | `http://driver-service:8082/api/drivers/...` |
| `/api/trips/**` | trip-service | `http://trip-service:8083/api/trips/**` |
| `/api/matching/*/match` | matching-service | `http://matching-service:8084/api/matching/{requestId}/match` |
| `/api/pricing/quote` | pricing-service | `http://pricing-service:8085/api/pricing/quote` |
| `/api/payments/*` | payment-service | `http://payment-service:8086/api/payments/{tripId}` |
| `/api/notifications/**` | notification-service | `http://notification-service:8087/api/notifications/**` |
| `/actuator/health`, `/actuator/info`, `/actuator/prometheus` | api-gateway | (local) |

> No `lb://` scheme is used. Service discovery is via Docker DNS only.
> `/internal/**` (service-to-service APIs) is intentionally **not** routed by the gateway.
> `POST`/`DELETE /api/drivers/{id}/reserve` is **not** routed either (gateway returns `404`). Matching calls both directly at `http://driver-service:8082`; Trip also calls DELETE for cancellation compensation. Both clients use the same internal key.
> The other internal endpoints are **not** routed either (`404`): `POST /api/matching/{requestId}/rematch`, `POST /api/pricing/final` (Trip calls `http://pricing-service:8085` directly), `POST /api/payments/{tripId}/charge` and `POST /api/payments/{tripId}/refund`.
> The gateway verifies the JWT before routing (§1): any other path without a valid token gets `401`, including paths that have no route.
> Swagger UI and `/v3/api-docs` of the services are not routed: open them on the service's own port (e.g. `http://localhost:8081/swagger-ui.html`).

### Access rules at the gateway

Checked in this order; the first matching line applies.

| Method | Path | Access |
|---|---|---|
| any | `/actuator/**` | Public (only `health`, `info`, `prometheus` exist) |
| POST | `/api/auth/register/passenger`, `/api/auth/register/driver`, `/api/auth/login` | Public |
| GET | `/api/drivers/available` | Public (see §3) |
| any | `/internal/**`, `/api/drivers/*/reserve`, `/api/matching/*/rematch`, `/api/pricing/final`, `/api/payments/*/charge`, `/api/payments/*/refund` | Not routed: `404` |
| any | `/api/drivers/nearby` | `PASSENGER` |
| any | `/api/matching/**` | `ADMIN` (only `/api/matching/*/match` is routed) |
| PATCH | `/api/drivers/*/status`, `/api/drivers/*/location` | `DRIVER` |
| POST | `/api/trips`, `/api/trips/*/cancel` | `PASSENGER` |
| POST | `/api/trips/*/accept`, `/api/trips/*/start`, `/api/trips/*/complete` | `DRIVER` |
| any | everything else | Any valid token (`PASSENGER`, `DRIVER` or `ADMIN`) |

### What the gateway adds and answers itself

| Situation | Behaviour |
|---|---|
| Every request | `X-Correlation-ID` is generated when the client sent none, forwarded to the backend and returned in the response |
| Authenticated request | `X-User-Id` = token `sub` and `X-User-Role` = token `role` are forwarded; client-supplied values for both headers are dropped (§1). |
| Missing / invalid / expired token, wrong role | `401` / `403` with the standard error body and `code` (§1); the backend is not called |
| Valid token, path without a route | `404` |
| Path not in normal form (`..`, `//`, `;`, encoded `/` or `.`) | `400`, with or without a token; the backend is not called |
| Backend unreachable | `500` with Spring's default error body (`timestamp`, `status`, `error`, `path`; no `code`) |
| Cross-origin browser request | No CORS headers are sent, so a page on another origin cannot read the answer |

The gateway keeps no session and sets no cookie: every request is authenticated by its own token.

## 11. Member 2 W3 implementation notes (09/11/2026)

These notes record Member 2 integration behavior. The gateway, driver-service and shared environment configuration must be verified together for full-system acceptance.

- Gateway validates JWT/role and replaces client-supplied `X-User-Id` and `X-User-Role`. Trip requires these trusted headers on client POST requests. Direct service ports are for development, not an authenticated alternative client entry point.
- Create ignores legacy body passengerId/estimatedFare; identity comes from the gateway, price and route estimate from Pricing `/quote`. A supplied X-Correlation-ID must be a UUID. Pricing failure returns 503, never a fallback price.
- Accept requires ASSIGNED and the assigned driver. It never assigns SEARCHING or changes driverId. Start/complete require the same driver; cancellation requires the trip passenger. Invalid lifecycle state returns 409. Client cancellation supports SEARCHING/ASSIGNED and is idempotent once CANCELLED.
- Complete calls Pricing `/final` before committing COMPLETED and publishes all eight required fields. W3 uses the quote distance as simulated trip distance and elapsed minutes (minimum 1), not GPS mileage. Pricing failure leaves IN_PROGRESS and emits no completion event.
- Internal GET `/api/trips/{id}` and POST `/api/trips/{id}/cancel` use X-Internal-Api-Key. Internal cancel only accepts `{ "reason": "MATCHING_TIMEOUT" }`, only changes SEARCHING, and is idempotent for CANCELLED. This key cannot start or complete a trip.
- Matching match/rematch endpoints require the internal key. M2 creates requestId = tripId from RideRequested. Unknown manual requestId returns 404, never fabricated data. A manual attempt returns 202 with resulting MATCHED/NO_DRIVER_FOUND, correcting earlier illustrative MATCHING/REMATCHING labels. Background retry uses the scheduler.
- Defaults: radius 5 km, search budget 60s, maximum 3 rounds, retry scan 5s. CAS 409 moves to the next candidate in the same round. Search rounds are separate from the three Kafka consumer retries.
- Public rematch cannot replace a committed MATCHED reservation whose `DriverReserved` may be in flight. W5 adds internal `AssignmentRecoveryRequested`, fenced by the active `DriverReserved.eventId`, to reset only the rejected/expired attempt. The original three-attempt/60-second budget applies. Exhaustion requests cancellation from Trip; Trip alone publishes `TripCancelled`.
- Downstream transport/auth/contract errors propagate for bounded Kafka retry or HTTP 503, not NO_DRIVER_FOUND. Failed release is logged; TripCancelled and the authoritative lease provide fallback cleanup.
- Cancellation commits its state/outbox before the external DELETE. An after-commit callback attempts release before returning HTTP; rollback never frees an active trip's driver. Event and REST cleanup may race safely after commit.
- New HTTP errors include code where applicable: INVALID_INTERNAL_API_KEY, UNAUTHORIZED, FORBIDDEN, VALIDATION_ERROR, DOWNSTREAM_UNAVAILABLE, CONCURRENT_UPDATE. No new public URL/event is introduced.
