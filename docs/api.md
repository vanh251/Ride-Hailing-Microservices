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

All requests to backend services (except `/api/auth/register/passenger`, `/api/auth/register/driver` and `/api/auth/login`) require:

```
Authorization: Bearer <accessToken>
```

**Token format:** JWT (HS256), issued by Identity Service.  
**Token expiry:** 24 hours (configurable via `JWT_EXPIRATION_MS`).

### Verification at the API Gateway

The gateway verifies the token (signature, expiry, `sub` is a UUID, `role` is a known role) before it routes a request, and checks the role for the endpoints marked PASSENGER or DRIVER below. "Own resource" checks stay in the backend service.

| Case | Response |
|---|---|
| No `Authorization: Bearer` header on a protected route | `401 MISSING_TOKEN` |
| Bad signature, malformed token, missing `sub`/`role`, unknown role | `401 INVALID_TOKEN` |
| Expired token | `401 TOKEN_EXPIRED` |
| Valid token, role not allowed on the endpoint | `403 ACCESS_DENIED` |

Routes that need no token at the gateway: `POST /api/auth/register/passenger`, `POST /api/auth/register/driver`, `POST /api/auth/login`, `GET /api/drivers/available` and `/actuator/**`.

Endpoints marked "internal" that the gateway still routes (`/api/matching/**`, `POST /api/pricing/final`, `POST /api/payments/{tripId}/charge`, `POST /api/payments/{tripId}/refund`) need a valid token of any role at the gateway; no role rule is applied to them yet.

**`X-User-Id`:** for an authenticated request the gateway forwards the header `X-User-Id` = the token's `sub` (the user id) to the backend, together with the original `Authorization` header. An `X-User-Id` sent by the client is always dropped, so a backend behind the gateway can trust the header.

### Role-based Access

| Role | Permissions |
|---|---|
| `PASSENGER` | Create ride, cancel own ride, view own trip |
| `DRIVER` | Update own status, update own location, accept/reject/start/complete trip |

---

## 2. Identity Service

**Internal address:** `http://identity-service:8081`  
**Gateway prefix:** `/api/auth/**`, `/api/users/**`

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
| fullName | string | Yes | 1-100 chars |
| email | string | Yes | Valid email, unique (stored trimmed and lower-cased) |
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
- `409 EMAIL_ALREADY_REGISTERED` – Email already registered

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

---

### POST /api/auth/login

Authenticate and receive JWT.

**Auth required:** No

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "securePassword123"
}
```

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

**Errors:**
- `400` – Missing fields
- `401` – Invalid credentials

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

**Errors:**
- `401` – Missing or invalid JWT
- `404` – User not found

---

## 3. Driver Service

**Internal address:** `http://driver-service:8082`  
**Gateway prefix:** `/api/drivers/**`

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

List AVAILABLE drivers near a point: the `limit` nearest drivers within `radiusKm` (Haversine distance), nearest first. Same search as `/nearby`, plus the `version` and `status` Matching Service needs.

**Auth required:** No token checked for now. Called by Matching Service, which has no service credential yet; once it sends one, this becomes "PASSENGER or internal". Until then the gateway also exposes it publicly, so the required point and the `radiusKm` / `limit` caps keep a caller from listing the whole fleet in one request.

**Query Parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| latitude | double | Yes | Reference point (pickup) latitude, -90..90 |
| longitude | double | Yes | Reference point (pickup) longitude, -180..180 |
| radiusKm | double | No | Search radius in km (default: 5.0, max 50) |
| limit | int | No | Maximum number of drivers (default: 20, 1..50) |

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

**Errors:**
- `400 VALIDATION_ERROR` – Missing `latitude` / `longitude`, coordinate out of range, `radiusKm` ≤ 0 or > 50, `limit` outside 1..50
- `400 INVALID_PARAMETER` – Non-numeric parameter

---

### GET /api/drivers/nearby

Query nearby AVAILABLE drivers (spatial search): the `limit` nearest drivers within `radiusKm` (Haversine distance), nearest first.

**Auth required:** Yes (PASSENGER). Matching Service uses `GET /api/drivers/available` instead, so no internal caller needs this endpoint today.

**Query Parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| latitude | double | Yes | Pickup latitude, -90..90 |
| longitude | double | Yes | Pickup longitude, -180..180 |
| radiusKm | double | No | Radius in km (default: 5.0, max 50) |
| limit | int | No | Max results (default: 10, 1-50) |

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
- `400 VALIDATION_ERROR` – Missing coordinate, value out of range, `limit` outside 1-50
- `401` – Missing, invalid or expired JWT
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
| RESERVED | BUSY | Event `DriverAssigned` (docs/events.md §4.3) |
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

**Auth required:** Yes (internal service call)

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

**Errors:**
- `409` – Driver already reserved (CAS failed – affectedRows = 0)

---

### DELETE /api/drivers/{id}/reserve

Release driver reservation (compensating action). Returns driver to AVAILABLE.

**Auth required:** Yes (internal service call)

**Response 204 No Content**

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
**Gateway prefix:** `/api/matching/**`

> Note: Matching is primarily driven by Kafka events. REST endpoints exist for manual trigger and testing.

---

### POST /api/matching/{requestId}/match

Manually trigger matching for a ride request.

**Auth required:** Yes (internal or admin)

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

**Auth required:** Yes (internal)

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
**Gateway prefix:** `/api/pricing/**`

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

**Auth required:** Yes (internal)

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
**Gateway prefix:** `/api/payments/**`

> Note: In the business flow a payment is triggered **only** by the Kafka event `TripCompleted` (docs/events.md §4.5); Trip Service never calls `/charge`. The REST charge exists for manual tests and the status/refund endpoints for checks and compensation.
>
> Failures can be simulated with `PAYMENT_FAILURE_MODE` = `none` (default) / `always` / `rate` (with `PAYMENT_FAILURE_RATE` between 0 and 1). A declined charge is stored as `FAILED` and publishes `PaymentFailed`.

---

### POST /api/payments/{tripId}/charge

Charge a passenger for a trip (manual test path).

**Auth required:** Yes (internal)

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

**Auth required:** Yes (internal)

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
| `X-Idempotency-Key` | Required for payment charge (or `idempotencyKey` in the body) | Prevents duplicate operations |
| `X-Internal-Api-Key` | Required for `/internal/**` (service-to-service only) | Shared secret `INTERNAL_API_KEY`; never sent by clients |

### Pagination (for list endpoints)

```
GET /api/drivers/available?page=0&size=10&sort=distanceKm,asc
```

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
| `/api/matching/**` | matching-service | `http://matching-service:8084/api/matching/**` |
| `/api/pricing/**` | pricing-service | `http://pricing-service:8085/api/pricing/**` |
| `/api/payments/**` | payment-service | `http://payment-service:8086/api/payments/**` |
| `/api/notifications/**` | notification-service | `http://notification-service:8087/api/notifications/**` |
| `/actuator/health` | api-gateway | (local) |

> No `lb://` scheme is used. Service discovery is via Docker DNS only.
> `/internal/**` (service-to-service APIs) is intentionally **not** routed by the gateway.
> `POST`/`DELETE /api/drivers/{id}/reserve` is **not** routed either (gateway returns `404`): only Matching Service calls it, directly at `http://driver-service:8082`.
> The gateway verifies the JWT before routing (§1): any other path without a valid token gets `401`, including paths that have no route.
