# Event fixtures

One file per event of `docs/events.md` §4, copied from its "Full Event" example (contract v1.1).
These files are the shared contract between the service that publishes an event and the services that consume it.

| File | Producer | Consumers |
|---|---|---|
| `RideRequested.json` | trip-service | matching-service, notification-service |
| `DriverReserved.json` | matching-service | trip-service, notification-service |
| `DriverAssigned.json` | trip-service | driver-service, notification-service |
| `TripStarted.json` | trip-service | notification-service |
| `TripCompleted.json` | trip-service | payment-service, driver-service, notification-service |
| `PaymentSucceeded.json` | payment-service | trip-service, notification-service |
| `PaymentFailed.json` | payment-service | trip-service, notification-service |
| `TripCancelled.json` | trip-service | driver-service, matching-service, notification-service |
| `DriverAssignmentConfirmed.json` | driver-service | trip-service, notification-service |
| `DriverAssignmentRejected.json` | driver-service | trip-service, notification-service |

## How to use them

- **Producer tests** check that the event they build has the same fields as the fixture.
- **Consumer tests** read the fixture as the incoming message (path from a service folder: `../../docs/test-data/events/<EventType>.json`).
- **Manual tests**: `scripts/kafka/send-event.sh <EventType>` publishes a fixture to Kafka with a fresh `eventId` (see `scripts/kafka/README.md`).

## Changing a fixture

A fixture changes only together with `docs/events.md`, in a `docs(...)` pull request reviewed by the producer and the consumers (CODING_CONVENTION §48–49). The tests of every service that uses the fixture must still pass.

Ids used in the fixtures: trip `550e8400-e29b-41d4-a716-446655440000`, passenger `a0000000-0000-4000-8000-000000000001` (seed passenger01), driver `d0000000-0000-4000-8000-000000000001` (seed driver01), payment `9b2f6a1e-3c4d-4e5f-8a6b-7c8d9e0f1a2b`, correlation `f47ac10b-58cc-4372-a567-0e02b2c3d479`.
