# Event Catalogue

## Ride-Hailing Microservices System (DS03)

**Version:** 1.2.0 (W5 assignment confirmation contract; Driver/M3 rollout pending)
**Date:** 19/11/2026
**Message Broker:** Apache Kafka 3.8.0 (KRaft mode)  
**Bootstrap Server:** `kafka:9092` (Docker internal)

> **Changes in 1.1.0 (lead decisions, 18/10/2026):** PascalCase `eventType`, plain-UUID ids and money as JSON numbers (§2 Value Conventions); Trip calls Pricing `/final` and Payment charges `finalFare` from `TripCompleted` without calling Pricing (§4.5); required `TripCompleted` payload fields (§4.5); `passengerId` added to `DriverReserved` (§4.2); payment events keep their payload, `causationId` = `TripCompleted.eventId` (§4.6, §4.7); `TripCompleted` is the only trigger of a charge (§4.5); cancellation releases the driver through both `DELETE /reserve` and `TripCancelled`, both idempotent (§4.8); `trip-events` consumer list completed (§3).

> **W5 contract extension:** `DriverReserved` and `DriverAssigned` carry `reservationVersion`. Trip waits in `ASSIGNING` for a Driver outcome. New events are `AssignmentRecoveryRequested`, `DriverAssignmentConfirmed`, and `DriverAssignmentRejected`. Member 1 must implement the Driver outcome producer; M3 provisions `driver-events` (`scripts/kafka/create-topics.sh`) and Notification consumes it. The envelope `schemaVersion` stays `1.0` for these additive payload changes.

> **Upgrade gate:** A queued W3 `DriverReserved` has no `reservationVersion` and W5 Trip sends it to DLQ. Drain old outboxes and Kafka lag, then resolve every `MATCHED` request whose Trip remains `SEARCHING` before cutover; see `docs/operations.md` §4.1. Do not replay old payloads into the new consumer.

---

## Table of Contents

1. [Event Design Principles](#1-event-design-principles)
2. [Event Envelope Schema](#2-event-envelope-schema)
3. [Kafka Topics](#3-kafka-topics)
4. [Event Catalogue](#4-event-catalogue)
   - [trip.requested](#41-triprequested)
   - [driver.reserved](#42-driverreserved)
   - [driver.assigned](#43-driverassigned)
   - [trip.started](#44-tripstarted)
   - [trip.completed](#45-tripcompleted)
   - [payment.success](#46-paymentsuccess)
   - [payment.failed](#47-paymentfailed)
   - [trip.cancelled](#48-tripcancelled)
   - [assignment.recovery.requested](#49-assignmentrecoveryrequested)
   - [driver.assignment.confirmed](#410-driverassignmentconfirmed)
   - [driver.assignment.rejected](#411-driverassignmentrejected)
5. [Consumer Group Assignments](#5-consumer-group-assignments)
6. [Dead Letter Queue](#6-dead-letter-queue)
7. [Deduplication](#7-deduplication)
8. [Retry Strategy](#8-retry-strategy)
9. [Saga Event Flow](#9-saga-event-flow)
10. [Correlation ID Propagation](#10-correlation-id-propagation)

---

## 1. Event Design Principles

### At-Least-Once Delivery
Kafka guarantees at-least-once delivery. Consumers **must** be idempotent using the `eventId` deduplication mechanism.

### Immutability
Events are immutable facts. Never modify a published event. Corrections are new events.

### Event Envelope
Every event must include the standard envelope fields (`eventId`, `eventType`, `correlationId`, etc.) before the domain-specific `payload`.

### Consumer Responsibility
- Each consumer checks `processed_events` table before processing
- If `eventId` found → skip (idempotent)
- If not found → process → insert `eventId`

### Outbox Pattern
Producers use the Outbox Pattern to guarantee event publication:
1. Write business entity + event to `outbox_events` in a single transaction
2. Outbox poller publishes to Kafka
3. Mark `outbox_events` record as published

---

## 2. Event Envelope Schema

All events share a common envelope:

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000001",
  "eventType": "RideRequested",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": "e0000000-0000-4000-8000-000000000000",
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:00:00.000Z",
  "payload": {}
}
```

> **Identifiers:** every `driverId` in an event payload is the driver's identity user id (`drivers.driver_id = users.user_id`), the same value as the JWT `sub` and the `X-User-Id` header of that driver.

### Value Conventions (contract v1.1)

| Rule | Applies to |
|---|---|
| **`eventType` is always the PascalCase name** (`RideRequested`, `DriverReserved`, …). The dotted names in the section titles (`trip.requested`, …) are only labels for this document and never appear in a message. | every event |
| **`eventId` and `causationId` are plain UUIDs** (RFC 4122, no `evt-` prefix). Consumers parse them as UUIDs. All ids in payloads (`tripId`, `passengerId`, `driverId`, `paymentId`) are plain UUIDs too. | every event |
| **Money is a JSON number** with no quotes; VND amounts are whole numbers (e.g. `47500`). Every amount is sent together with `currency` (`"VND"`). Services hold money as `BigDecimal`, never `double`. | `estimatedFare`, `finalFare`, `amount` |
| **`causationId` = `eventId` of the event that triggered this one** (e.g. `PaymentSucceeded.causationId` = the `TripCompleted.eventId`). `null` only for events started by an HTTP request. | every event |

### Envelope Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `eventId` | UUID | Yes | Globally unique event ID. Used for deduplication |
| `eventType` | string | Yes | Event name (PascalCase, see Value Conventions) |
| `aggregateId` | string | Yes | ID of the primary aggregate (e.g., tripId) |
| `aggregateType` | string | Yes | Type of aggregate (e.g., `Trip`, `Driver`, `Payment`) |
| `producer` | string | Yes | Source service name |
| `correlationId` | UUID | Yes | Business flow correlation ID (spans all services) |
| `causationId` | UUID | No | ID of the event that caused this event |
| `schemaVersion` | string | Yes | Payload schema version for forward compatibility |
| `occurredAt` | ISO 8601 | Yes | UTC timestamp when the event occurred |
| `payload` | object | Yes | Domain-specific event data |

---

## 3. Kafka Topics

| Topic | Partitions | Producers | Consumers | Description |
|---|---|---|---|---|
| `trip-events` | 3 | trip-service | matching-service, payment-service, driver-service, notification-service | Trip lifecycle events |
| `matching-events` | 3 | matching-service | trip-service, notification-service | Driver matching results |
| `payment-events` | 3 | payment-service | trip-service, notification-service | Payment results |
| `driver-events` | 3 | driver-service | trip-service, notification-service | Driver assignment outcomes |
| `trip-events.DLQ` | 1 | Kafka (auto) | Manual / monitoring | Dead letters from trip-events |
| `matching-events.DLQ` | 1 | Kafka (auto) | Manual / monitoring | Dead letters from matching-events |
| `payment-events.DLQ` | 1 | Kafka (auto) | Manual / monitoring | Dead letters from payment-events |
| `driver-events.DLQ` | 1 | Kafka (auto) | Manual / monitoring | Dead letters from driver-events |

### Topic Configuration (recommended for demo)

```
replication.factor=1
min.insync.replicas=1
retention.ms=604800000   # 7 days
```

---

## 4. Event Catalogue

---

### 4.1. `trip.requested`

A passenger has created a ride request. Initiates the driver matching process.

**Topic:** `trip-events`  
**Producer:** `trip-service`  
**Consumers:** `matching-service`, `notification-service`

**Trigger:** Passenger calls `POST /api/trips`

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000001",
  "eventType": "RideRequested",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": null,
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:00:00Z",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
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
    "requestedAt": "2026-09-30T10:00:00Z"
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| matching-service | Start driver search: query nearby AVAILABLE drivers, apply CAS reservation |
| notification-service | Notify passenger: "Searching for a driver..." |

---

### 4.2. `driver.reserved`

A driver has been successfully reserved by the Matching Service (CAS lock acquired).

**Topic:** `matching-events`  
**Producer:** `matching-service`  
**Consumers:** `trip-service`, `notification-service`

**Trigger:** Matching Service successfully reserves a driver via `POST /api/drivers/{id}/reserve`

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000002",
  "eventType": "DriverReserved",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "matching-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": "e0000000-0000-4000-8000-000000000001",
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:00:05Z",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "driverId": "d0000000-0000-4000-8000-000000000001",
    "driverName": "Tran Van B",
    "driverLocation": {
      "latitude": 21.0290,
      "longitude": 105.8545
    },
    "estimatedArrivalMin": 3,
    "reservedUntil": "2026-09-30T10:00:35Z",
    "reservationVersion": 6
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| trip-service | If lease expired, request bounded recovery. Otherwise commit `SEARCHING -> ASSIGNING` and a `DriverAssigned` outbox row; do not expose `ASSIGNED` yet |
| notification-service | No notification: the reservation is tentative until `DriverAssignmentConfirmed` (§4.10) |

> `passengerId` (contract v1.1) is copied by Matching Service from the `RideRequested` that started the match, so Notification Service can address the passenger without another lookup.

---

### 4.3. `driver.assigned`

A driver has been formally assigned to the trip by Trip Service.

**Topic:** `trip-events`  
**Producer:** `trip-service`  
**Consumers:** `driver-service`, `notification-service`

**Trigger:** Trip Service processes a live `DriverReserved` and enters `ASSIGNING`

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000003",
  "eventType": "DriverAssigned",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": "e0000000-0000-4000-8000-000000000002",
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:00:06Z",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "driverId": "d0000000-0000-4000-8000-000000000001",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "assignedAt": "2026-09-30T10:00:06Z",
    "reservationVersion": 6
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| driver-service | Dedup via `eventId`; atomically require matching trip, exact reservation version, `RESERVED`, and live lease before `RESERVED -> BUSY`. Publish one confirmed/rejected outcome from outbox. Member 1 implementation pending |
| notification-service | No notification: the assignment is tentative until `DriverAssignmentConfirmed` (§4.10) |

---

### 4.4. `trip.started`

The driver has started the trip (picked up the passenger).

**Topic:** `trip-events`  
**Producer:** `trip-service`  
**Consumers:** `notification-service`

> driver-service does **not** consume this event: the driver is already `BUSY` since `driver.assigned`.

**Trigger:** Driver calls `POST /api/trips/{id}/start`

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000004",
  "eventType": "TripStarted",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": null,
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:05:00Z",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "driverId": "d0000000-0000-4000-8000-000000000001",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "startedAt": "2026-09-30T10:05:00Z",
    "driverLocation": {
      "latitude": 21.0285,
      "longitude": 105.8542
    }
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| notification-service | Notify passenger: "Your ride has started" |

---

### 4.5. `trip.completed`

The driver has completed the trip (arrived at destination). Triggers payment processing.

**Topic:** `trip-events`  
**Producer:** `trip-service`  
**Consumers:** `payment-service`, `driver-service`, `notification-service`

**Trigger:** Driver calls `POST /api/trips/{id}/complete`. Before publishing, Trip Service calls Pricing Service `POST /api/pricing/final` synchronously and stores the result as `finalFare` (contract v1.1). `finalFare` in this event is the amount to charge: Payment Service does **not** call Pricing again.

**Payload fields (all required, contract v1.1):**

| Field | Type | Description |
|---|---|---|
| `tripId` | UUID | Trip being completed |
| `driverId` | UUID | Driver who completed the trip |
| `passengerId` | UUID | Passenger to charge. An event without it is rejected by Payment Service and sent to `trip-events.DLQ` |
| `actualDistanceKm` | number | Distance sent to Pricing `/final` |
| `actualDurationMin` | integer | Duration sent to Pricing `/final` |
| `finalFare` | number | Amount to charge, as returned by Pricing `/final` (`totalFare`); must be > 0 |
| `currency` | string | ISO 4217 code, `"VND"` |
| `completedAt` | ISO 8601 | UTC time the trip was completed |

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000005",
  "eventType": "TripCompleted",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": null,
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:25:00Z",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "driverId": "d0000000-0000-4000-8000-000000000001",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "actualDistanceKm": 2.8,
    "actualDurationMin": 20,
    "finalFare": 47500,
    "currency": "VND",
    "completedAt": "2026-09-30T10:25:00Z"
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| payment-service | Deduplicate via `eventId` → charge `finalFare` once per trip (idempotency key `trip-{tripId}-completed`) → publish `payment.success` or `payment.failed` with `causationId` = this `eventId`. This event is the **only** trigger of a charge in the business flow (contract v1.1); `POST /api/payments/{tripId}/charge` is for manual tests only |
| driver-service | Dedup via `eventId` → release driver `BUSY -> AVAILABLE` only if `drivers.reserved_for_trip = tripId`; clear `reserved_for_trip`, `version + 1`. No match → log and skip |
| notification-service | Notify passenger: "Trip completed. Processing payment..." |

> The driver is released as soon as the trip is completed, **independent of the payment result**. A later `payment.failed` never moves the driver back (see §9 and SRS §13.2).

---

### 4.6. `payment.success`

Payment has been captured successfully.

**Topic:** `payment-events`  
**Producer:** `payment-service`  
**Consumers:** `trip-service`, `notification-service`

**Trigger:** Payment Service successfully processes charge

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000006",
  "eventType": "PaymentSucceeded",
  "aggregateId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
  "aggregateType": "Payment",
  "producer": "payment-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": "e0000000-0000-4000-8000-000000000005",
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:25:05Z",
  "payload": {
    "paymentId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "amount": 47500,
    "currency": "VND",
    "processedAt": "2026-09-30T10:25:05Z"
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| trip-service | Update trip status: `COMPLETED -> PAID`; record `SAGA_COMPLETED` in `saga_log` |
| notification-service | Notify passenger: "Payment successful. Thank you for riding!" |
| notification-service | Notify driver: "Payment received for trip [id]". The payload has no `driverId`: Notification Service takes the driver from the earlier `DriverAssigned` / `TripCompleted` of the same trip |

> **Payload (contract v1.1, unchanged):** `paymentId`, `tripId`, `passengerId`, `amount`, `currency`, `processedAt`. `causationId` is the `eventId` of the `TripCompleted` that was charged; it is `null` for a manual charge through REST.

---

### 4.7. `payment.failed`

Payment charge has failed.

**Topic:** `payment-events`  
**Producer:** `payment-service`  
**Consumers:** `trip-service`, `notification-service`

**Trigger:** Payment Service fails to process charge (simulated failure or retry exhausted)

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000007",
  "eventType": "PaymentFailed",
  "aggregateId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
  "aggregateType": "Payment",
  "producer": "payment-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": "e0000000-0000-4000-8000-000000000005",
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:25:15Z",
  "payload": {
    "paymentId": "9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b",
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "amount": 47500,
    "currency": "VND",
    "reason": "SIMULATED_FAILURE",
    "retryCount": 3,
    "failedAt": "2026-09-30T10:25:15Z"
  }
}
```

**Consumer Actions:**

| Consumer | Action |
|---|---|
| trip-service | Update trip status: `COMPLETED -> PAYMENT_FAILED`; record `COMPENSATED` in `saga_log`; log debt |
| notification-service | Notify passenger: "Payment failed. Please retry or contact support." |

> **Note:** Driver state is NOT rolled back. The driver has completed their service. Only the payment record is marked failed for retry or manual resolution.

> **Payload (contract v1.1, unchanged):** `paymentId`, `tripId`, `passengerId`, `amount`, `currency`, `reason`, `retryCount`, `failedAt`. `causationId` follows the same rule as `payment.success`.

---

### 4.8. `trip.cancelled`

A trip has been cancelled by the passenger (or timeout).

**Topic:** `trip-events`  
**Producer:** `trip-service`  
**Consumers:** `driver-service`, `matching-service`, `notification-service`

**Trigger:** Passenger calls `POST /api/trips/{id}/cancel` OR matching timeout

**Full Event:**

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000008",
  "eventType": "TripCancelled",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": null,
  "schemaVersion": "1.0",
  "occurredAt": "2026-09-30T10:01:30Z",
  "payload": {
    "tripId": "550e8400-e29b-41d4-a716-446655440000",
    "passengerId": "a0000000-0000-4000-8000-000000000001",
    "driverId": "d0000000-0000-4000-8000-000000000001",
    "reason": "PASSENGER_CANCELLED",
    "previousStatus": "ASSIGNED",
    "cancelledAt": "2026-09-30T10:01:30Z"
  }
}
```

**Cancellation Reasons:**

| Reason | Description |
|---|---|
| `PASSENGER_CANCELLED` | Passenger explicitly cancelled |
| `MATCHING_TIMEOUT` | No driver found within timeout window |
| `DRIVER_REJECTED` | Driver rejected and no rematch available |

**Consumer Actions:**

| Consumer | Action |
|---|---|
| driver-service | Dedup via `eventId` → find driver by `driverId`; if `driverId` is missing, by `reserved_for_trip = tripId` → release `RESERVED/BUSY -> AVAILABLE` only if `drivers.reserved_for_trip = tripId`; clear `reserved_for_trip` and `reserved_until`, `version + 1`. No match (no driver assigned yet, or driver already on another trip) → log and skip |
| matching-service | Cancel any in-progress matching job |
| notification-service | Notify passenger: "Trip cancelled" |
| notification-service | Notify driver only when `previousStatus = ASSIGNED` (confirmed driver): "Trip has been cancelled". The pending driver of an `ASSIGNING` trip was never told about it |

> **Two compensation paths (contract v1.1; safe commit ordering clarified in M2 W3):** Trip Service commits cancellation and its outbox together, then attempts `DELETE /api/drivers/{driverId}/reserve?tripId={tripId}` in an after-commit callback before the HTTP request returns. It never releases externally for a cancellation that rolls back. The outbox relay and REST compensation may race after commit; both paths are safe because each acts only while `drivers.reserved_for_trip = tripId`, so the second finds nothing to release. REST failure is logged and the event/lease remain fallback cleanup paths.

---

### 4.9. `AssignmentRecoveryRequested`

**Topic:** `trip-events` · **Producer:** trip-service · **Consumer:** matching-service.
Trip commits this event in its outbox when a `DriverReserved` lease is already expired or Driver explicitly rejects the exact attempt. The envelope has `aggregateType=Trip`, `aggregateId=tripId`, `causationId=DriverReserved.eventId`, and Kafka key `tripId`.

```json
{"tripId":"550e8400-e29b-41d4-a716-446655440000","sourceDriverReservedEventId":"e0000000-0000-4000-8000-000000000002","reason":"LEASE_EXPIRED"}
```

`reason` is `LEASE_EXPIRED` or `DRIVER_REJECTED`. Matching resets only a `MATCHED` row whose active reservation event ID equals `sourceDriverReservedEventId`, then checks the original three-attempt/60-second budget. Duplicate or old requests do not start another attempt.

### 4.10. `DriverAssignmentConfirmed`

**Topic:** `driver-events` · **Producer:** driver-service · **Consumers:** trip-service, notification-service. The envelope has `aggregateType=Trip`, `aggregateId=tripId`, `causationId=DriverAssigned.eventId`, and Kafka key `tripId`.

```json
{"tripId":"550e8400-e29b-41d4-a716-446655440000","driverId":"d0000000-0000-4000-8000-000000000001","passengerId":"a0000000-0000-4000-8000-000000000001","reservationVersion":6,"driverAssignedEventId":"e0000000-0000-4000-8000-000000000003"}
```

`passengerId` is copied by Driver Service from `DriverAssigned`, so Notification can address the passenger after a restart (agreed M1/M2/M3; trip-service ignores it). Fixtures: `docs/test-data/events/DriverAssignmentConfirmed.json`, `DriverAssignmentRejected.json`.

| Consumer | Action |
|---|---|
| trip-service | See below |
| notification-service | Notify passenger: "Driver found! Your driver is heading to your pickup location." Notify driver: "You have been assigned trip [id]". From now on this is the trip's driver for later notifications |

Driver publishes this only after its atomic, live-lease `RESERVED -> BUSY` commit. Trip accepts it only for the currently pending command and before the original search deadline, then commits `ASSIGNING -> ASSIGNED` and `saga_log.DRIVER_RESERVED`.

### 4.11. `DriverAssignmentRejected`

Same topic, envelope and payload fields as `DriverAssignmentConfirmed`. notification-service sends nothing and never uses this `driverId` for the trip. Published when the driver, trip, reservation version, or lease precondition fails. Trip returns `ASSIGNING -> SEARCHING` and emits `AssignmentRecoveryRequested` while time remains; otherwise it cancels. An unknown result at the deadline is cancelled without rematching, because Driver may have confirmed while the result was delayed.

## 5. Consumer Group Assignments

| Consumer Service | Consumer Group ID | Topics Subscribed |
|---|---|---|
| matching-service | `matching-service-group` | `trip-events` |
| trip-service | `trip-service-group` | `matching-events`, `payment-events`, `driver-events` |
| payment-service | `payment-service-group` | `trip-events` (for `trip.completed` only) |
| driver-service | `driver-service-group` | `trip-events` |
| notification-service | `notification-service-group` | `trip-events`, `matching-events`, `payment-events`, `driver-events` |

### Event Type Filtering per Consumer

Each consumer filters by `eventType` within its subscribed topics:

| Consumer | Topic | EventTypes Consumed |
|---|---|---|
| matching-service | `trip-events` | `RideRequested`, `TripCancelled`, `AssignmentRecoveryRequested` |
| trip-service | `matching-events` | `DriverReserved` |
| trip-service | `payment-events` | `PaymentSucceeded`, `PaymentFailed` |
| trip-service | `driver-events` | `DriverAssignmentConfirmed`, `DriverAssignmentRejected` |
| payment-service | `trip-events` | `TripCompleted` |
| driver-service | `trip-events` | `DriverAssigned`, `TripCompleted`, `TripCancelled` |
| notification-service | `trip-events` | `RideRequested`, `TripStarted`, `TripCompleted`, `TripCancelled` |
| notification-service | `payment-events` | `PaymentSucceeded`, `PaymentFailed` |
| notification-service | `driver-events` | `DriverAssignmentConfirmed` |

> driver-service follows the same consumer rules as the other services: dedup via `processed_events` (§7), bounded retry (§8), then `trip-events.DLQ` (§6). Other event types on `trip-events` are ignored.

---

## 6. Dead Letter Queue

Messages that cannot be processed after maximum retries are moved to the DLQ topic.

### DLQ Topic Naming Convention

```
<original-topic>.DLQ
```

| Original Topic | DLQ Topic |
|---|---|
| `trip-events` | `trip-events.DLQ` |
| `matching-events` | `matching-events.DLQ` |
| `payment-events` | `payment-events.DLQ` |

### DLQ Message Format

```json
{
  "originalTopic": "trip-events",
  "originalPartition": 0,
  "originalOffset": 123,
  "originalMessage": {
    "eventId": "e0000000-0000-4000-8000-000000000005",
    "eventType": "TripCompleted",
    "...": "..."
  },
  "error": "NullPointerException: paymentId is null",
  "retryCount": 3,
  "firstFailedAt": "2026-09-30T10:25:01Z",
  "lastFailedAt": "2026-09-30T10:25:15Z",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

### DLQ Monitoring

- DLQ message count metric: `kafka_dlq_messages_total{topic="payment-events.DLQ"}`
- Monitor in Grafana dashboard
- Manual replay can be triggered by re-publishing to original topic with same `eventId` (consumer deduplication handles already-processed events)

---

## 7. Deduplication

### Goal

Prevent duplicate side effects when Kafka delivers the same message more than once (at-least-once semantics).

### Mechanism

Each consumer service maintains a `processed_events` table:

```sql
CREATE TABLE processed_events (
    event_id   VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP DEFAULT NOW()
);
```

### Processing Flow

```
Receive Kafka message
       v
SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId
       v
eventId found? -> LOG "Duplicate detected, skipping" -> ACK message -> return
eventId NOT found? -> BEGIN TRANSACTION
       v
Execute business logic
       v
INSERT INTO processed_events (event_id, event_type) VALUES (:eventId, :eventType)
       v
COMMIT
       v
ACK Kafka message
```

### Services with Deduplication Tables

| Service | Database | Table |
|---|---|---|
| trip-service | `trip_db` | `processed_events` |
| matching-service | `matching_db` | `processed_events` |
| payment-service | `payment_db` | `processed_events` |
| driver-service | `driver_db` | `processed_events` |

> **Note:** Notification service is best-effort (no deduplication). Duplicate notifications are acceptable in demo scope.

---

## 8. Retry Strategy

### Consumer Retry (Application-level)

```
Message received -> processing fails
       v
Retry 1 (wait: 1 second)
       v
Retry 2 (wait: 2 seconds)
       v
Retry 3 (wait: 4 seconds)
       v
Max retries exceeded -> send to DLQ topic
```

| Parameter | Value |
|---|---|
| Max retries | 3 |
| Backoff type | Exponential |
| Initial delay | 1 second |
| Multiplier | 2.0 |
| Max delay | 30 seconds |

### Spring Kafka Configuration

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
    listener:
      ack-mode: MANUAL_IMMEDIATE
```

### Non-Retryable Exceptions

The following exceptions skip retry and go directly to DLQ:
- `DuplicateEventException` – already processed (idempotency hit)
- `ValidationException` – malformed event, retry won't help

---

## 9. Saga Event Flow

Complete event flow for a successful ride:

```
[1] Passenger: POST /api/trips
        |
        v  (saga_log: STARTED)
[2] trip-service publishes: trip.requested -> trip-events
        |
        v
[3] matching-service consumes: trip.requested
        |  -> GET /api/drivers/available
        |  -> POST /api/drivers/{id}/reserve (CAS)
        v  (saga_log: not applicable for matching-service)
[4] matching-service publishes: driver.reserved -> matching-events
        |
        v  (saga_log: DRIVER_RESERVED)
[5] trip-service consumes: driver.reserved
        |  -> If expired, publish AssignmentRecoveryRequested
        |  -> If live, Trip status: SEARCHING -> ASSIGNING
        v
[6] trip-service publishes: driver.assigned -> trip-events
        |
        v
[6a] driver-service consumes: driver.assigned
        |  -> Deduplication and exact version/live lease check
        |  -> Driver status: RESERVED -> BUSY, or reject
        |  -> Publish DriverAssignmentConfirmed/Rejected -> driver-events
        v
[6b] trip-service consumes: DriverAssignmentConfirmed/Rejected
        |  -> Confirmed: ASSIGNING -> ASSIGNED
        |  -> Rejected: ASSIGNING -> SEARCHING and bounded recovery
        v
[7] notification-service consumes all above events (fan-out)
        |
        v
[8] Driver: POST /api/trips/{id}/start
        |  -> Trip status: ASSIGNED -> IN_PROGRESS
        v
[9] Driver: POST /api/trips/{id}/complete
        |  -> POST /api/pricing/final (sync REST) -> finalFare
        |  -> Trip status: IN_PROGRESS -> COMPLETED  (saga_log: TRIP_COMPLETED)
        v
[10] trip-service publishes: trip.completed -> trip-events
        |
        +--> [10a] driver-service consumes: trip.completed  (in parallel with [11])
        |          -> Deduplication check
        |          -> Driver status: BUSY -> AVAILABLE (clear reserved_for_trip)
        |          -> Not affected by the payment result
        v
[11] payment-service consumes: trip.completed
        |  -> Deduplication check
        |  -> Charge finalFare from the event (no call to Pricing)
        v
[12] payment-service publishes: payment.success -> payment-events
        |
        v  (saga_log: SAGA_COMPLETED)
[13] trip-service consumes: payment.success
        |  -> Trip status: COMPLETED -> PAID
        v
[14] notification-service consumes: payment.success
        |  -> Notify passenger + driver
```

---

## 10. Correlation ID Propagation

The `correlationId` must be included in every event payload and HTTP header throughout the business flow.

### Origin

The `correlationId` is generated by Trip Service when the passenger creates a ride request:

```
correlationId = UUID.randomUUID()  // generated once per business flow
```

### Propagation Rules

| Layer | How to propagate |
|---|---|
| HTTP Request (inbound) | Read from `X-Correlation-ID` header; generate if absent |
| HTTP Response | Echo in `X-Correlation-ID` header |
| Kafka Event | Embed in envelope `correlationId` field |
| Log entries | Include in every log line as `correlationId` field |

### Example Trace

```
Request: POST /api/trips  [X-Correlation-ID: f47ac10b-58cc-4372-a567-0e02b2c3d479]
  -> trip.requested       [correlationId: f47ac10b-58cc-4372-a567-0e02b2c3d479]
    -> driver.reserved    [correlationId: f47ac10b-58cc-4372-a567-0e02b2c3d479]
      -> driver.assigned  [correlationId: f47ac10b-58cc-4372-a567-0e02b2c3d479]
        -> trip.completed [correlationId: f47ac10b-58cc-4372-a567-0e02b2c3d479]
          -> payment.success [correlationId: f47ac10b-58cc-4372-a567-0e02b2c3d479]

# Query all logs for a single ride:
grep correlationId=f47ac10b-58cc-4372-a567-0e02b2c3d479 *.log
```

### Prometheus Label

Include `correlationId` in trace-level metrics for distributed tracing integration:

```
http_request_duration_seconds{service="trip-service", correlation_id="f47ac10b-58cc-4372-a567-0e02b2c3d479"}
```
