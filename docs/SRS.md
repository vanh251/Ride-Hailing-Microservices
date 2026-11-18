# SOFTWARE REQUIREMENTS SPECIFICATION (SRS)

## Ride-Hailing Microservices System

### Simulating Grab/Uber

**Course:** Distributed Systems
**Project Code:** DS03
**Document Version:** 1.1.0
**Date:** 30/09/2026
**Status:** Development Baseline

---

# 1. Introduction

## 1.1. Purpose

Tài liệu này đặc tả các yêu cầu phần mềm cho hệ thống **Ride-Hailing Microservices System**, mô phỏng các chức năng cốt lõi của một nền tảng đặt xe như Grab/Uber.

Mục tiêu của hệ thống là áp dụng các kiến thức về **Distributed Systems** thông qua kiến trúc microservices, trong đó các service được triển khai độc lập và giao tiếp với nhau bằng **RESTful APIs** và **message queues**.

Theo yêu cầu DS03, hệ thống phải thể hiện các vấn đề phân tán như:

* REST communication
* Asynchronous message queue
* Distributed transactions
* Eventual consistency
* Fault tolerance
* Concurrency control
* Retry và recovery
* Idempotency và deduplication

Đề bài DS03 yêu cầu hệ thống được xây dựng bằng microservices, các service triển khai độc lập và giao tiếp qua RESTful API cùng message queue. Toàn bộ hệ thống được xây dựng trên nền tảng **Java 17 (LTS)**, framework **Spring Boot 3.x** và sử dụng **Gradle** làm build tool.

---

## 1.2. Project Objectives

Hệ thống hướng tới các mục tiêu:

1. Xây dựng một hệ thống đặt xe dựa trên kiến trúc microservices.
2. Cho phép Passenger tạo yêu cầu đặt xe.
3. Cho phép Driver cập nhật trạng thái và vị trí.
4. Tìm kiếm và lựa chọn Driver phù hợp.
5. Quản lý vòng đời của chuyến đi.
6. Tính giá chuyến đi.
7. Mô phỏng thanh toán.
8. Gửi notification cho Passenger và Driver.
9. Xử lý các request đồng thời.
10. Xử lý lỗi khi một service hoặc message processing bị lỗi.
11. Demonstrate distributed transaction bằng Saga hoặc compensating transaction.
12. Đánh giá performance và khả năng chịu lỗi của hệ thống.

---

# 2. Scope

## 2.1. In Scope

Phiên bản đầu tiên của hệ thống bao gồm:

* User registration
* User login
* JWT authentication
* Passenger/Driver role
* Driver availability management
* Driver simulated location
* Ride request
* Driver matching
* Driver acceptance/rejection
* Trip lifecycle management
* Fare calculation
* Simulated payment
* Notification
* Message queue
* Retry
* Deduplication
* Dead Letter Queue
* Correlation ID
* Saga transaction
* Concurrency control
* Fault recovery
* Logging
* Monitoring

## 2.2. Out of Scope

Các chức năng sau không thuộc phạm vi phiên bản đầu:

* GPS thật
* Google Maps integration
* Real-world route optimization
* Real banking/payment gateway
* Real-time traffic data
* Production-grade surge pricing
* Real mobile application
* Real driver verification
* Real identity verification

---

# 3. System Overview

## 3.1. System Description

Hệ thống mô phỏng quy trình đặt xe:

```text
Passenger
    |
    v
API Gateway
    |
    v
Trip Service
    |
    | RideRequested
    v
Message Broker
    |
    v
Matching Service
    |
    v
Driver Service
```

Sau khi Driver được chọn:

```text
Passenger
    |
    v
Trip Service
    |
    +----> Pricing Service
    |
    +----> Payment Service
    |
    +----> Notification Service
```

Hệ thống sử dụng hai loại communication:

### Synchronous communication

```text
Client
  |
  v
API Gateway
  |
  v
Service
```

Thông qua REST API.

### Asynchronous communication

```text
Service A
    |
    | Event
    v
Message Broker
    |
    v
Service B
```

Thông qua message queue.

### Service-to-service discovery

Hệ thống **không sử dụng Eureka Server, Eureka Client hoặc service discovery server riêng**. Khi triển khai bằng Docker Compose, các service được đặt trong cùng Docker network và gọi nhau bằng **Docker Compose service name** thông qua Docker DNS.

Ví dụ:

```text
http://identity-service:8081
http://driver-service:8082
http://trip-service:8083
http://matching-service:8084
http://pricing-service:8085
http://payment-service:8086
http://notification-service:8087
```

Trong container, không sử dụng `localhost` để gọi một service khác. `localhost` chỉ được sử dụng cho local development khi service chạy trực tiếp trên máy host.

---

# 4. System Architecture

## 4.1. Architecture Components

Hệ thống gồm các thành phần chính:

| Component            | Responsibility                   |
| -------------------- | -------------------------------- |
| Passenger Client     | Gửi yêu cầu đặt xe               |
| Driver Client        | Nhận và xử lý trip               |
| API Gateway          | Routing và authentication        |
| Identity Service     | User và authentication           |
| Driver Service       | Driver, vehicle, location        |
| Trip Service         | Ride request và trip lifecycle   |
| Matching Service     | Driver matching                  |
| Pricing Service      | Fare calculation                 |
| Payment Service      | Simulated payment                |
| Notification Service | Notification                     |
| Message Broker       | Asynchronous communication       |
| Docker Network / DNS | Service-to-service naming/discovery trong Docker |
| Database             | Persistent data của từng service |

Theo đề bài, hệ thống phải có **ít nhất 4 business microservices** được lựa chọn từ Identity/User, Driver/Vehicle/Location, Trip/Booking, Matching, Pricing, Payment và Notification. API Gateway không được tính là business microservice.

---

# 5. Microservices

## 5.1. Identity/User Service

### Responsibilities

* User registration
* User login
* Password hashing
* JWT generation
* Role management
* User information

### Roles

```text
PASSENGER
DRIVER
```

### Database

```text
users
roles
refresh_tokens
```

---

## 5.2. Driver/Vehicle/Location Service

### Responsibilities

* Driver management
* Vehicle management
* Driver availability
* Driver location
* Driver reservation

### Driver Status

```text
OFFLINE
AVAILABLE
RESERVED
BUSY
```

### Database

```text
drivers
vehicles
driver_locations
driver_status
```

---

## 5.3. Trip/Booking Service

### Responsibilities

* Create ride request
* Manage trip lifecycle
* Cancel trip
* Track trip status
* Coordinate payment flow

### Database

```text
ride_requests
trips
trip_status_history
```

---

## 5.4. Matching Service

### Responsibilities

* Receive ride request
* Find suitable Driver
* Check Driver availability
* Reserve Driver
* Handle matching timeout
* Rematch when necessary

### Matching criteria

Phiên bản đầu sử dụng:

```text
Driver status = AVAILABLE
+
Driver location available
+
Distance <= configured radius
```

---

## 5.5. Pricing Service

### Responsibilities

* Calculate estimated fare
* Calculate final fare
* Return fare breakdown

Ví dụ:

```text
fare =
    baseFare
    + distance * distanceRate
    + duration * timeRate
```

---

## 5.6. Payment Service

### Responsibilities

* Simulate payment
* Charge passenger
* Return payment result
* Simulate payment failure
* Support compensation/refund

Payment states:

```text
PENDING
PROCESSING
SUCCESS
FAILED
REFUNDED
```

---

## 5.7. Notification Service

### Responsibilities

* Passenger notification
* Driver notification
* Trip status notification
* Payment notification

Ví dụ:

```text
DriverMatched
TripAccepted
TripStarted
TripCompleted
PaymentSucceeded
PaymentFailed
```

---

# 6. Functional Requirements

## FR-01 – User Registration

The system shall allow users to register.

### Input

```json
{
  "fullName": "Nguyen Van A",
  "email": "user@example.com",
  "password": "password",
  "role": "PASSENGER"
}
```

### Requirements

* Email must be unique.
* Password must not be stored as plaintext.
* Role must be validated.

---

## FR-02 – User Login

The system shall allow users to authenticate.

### Output

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

---

## FR-03 – Driver Availability

Driver shall be able to update availability.

```text
OFFLINE
AVAILABLE
BUSY
```

Only `AVAILABLE` drivers may be selected by Matching Service.

---

## FR-04 – Driver Location

Driver shall be able to update simulated location.

Example:

```json
{
  "latitude": 21.0285,
  "longitude": 105.8542
}
```

---

## FR-05 – Create Ride Request

Passenger shall be able to create a ride request.

### Input

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

Initial state:

```text
SEARCHING
```

---

## FR-06 – Driver Matching

Matching Service shall find a suitable Driver.

Process:

```text
RideRequested
       |
       v
Find AVAILABLE drivers
       |
       v
Calculate distance
       |
       v
Select candidate
       |
       v
Reserve Driver
```

---

## FR-07 – Driver Accept/Reject

Driver shall be able to:

```text
ACCEPT
REJECT
```

If rejected:

```text
DriverRejected
       |
       v
Matching Service
       |
       v
Find another Driver
```

---

## FR-08 – Trip Lifecycle

Trip shall follow a predefined state machine conforming to the database schema.

```text
REQUESTED
    |
    v
SEARCHING
    |
    v
ASSIGNED
    |
    v
IN_PROGRESS
    |
    v
COMPLETED
    |
    v
PAID
```

Alternative / Failure states:

```text
CANCELLED        (Passenger cancelled or search timeout)
PAYMENT_FAILED   (Payment failed after trip completed, pending retry)
```

---

## FR-09 – Fare Calculation

Pricing Service shall calculate:

* Base fare
* Distance fare
* Time fare
* Total fare

---

## FR-10 – Payment

Payment Service shall simulate payment.

Possible results:

```text
SUCCESS
FAILED
```

Payment failure must not result in duplicate charging when the same event is retried.

---

## FR-11 – Notification

Notification Service shall consume events and send notifications.

---

## FR-12 – Retry

The system shall retry failed operations using bounded retry.

Example:

```text
Attempt 1
   |
failure
   |
Attempt 2
   |
failure
   |
Attempt 3
   |
failure
   |
DLQ
```

---

## FR-13 – Deduplication

Every event shall have a unique:

```text
eventId
```

Processed event IDs shall be stored.

If the same event arrives again:

```text
eventId already processed
        |
        v
Ignore duplicate
```

---

## FR-14 – Dead Letter Queue

Messages that cannot be processed after maximum retries shall be moved to DLQ.

---

## FR-15 – Correlation ID

Every business flow shall have a correlation ID.

Example:

```text
Passenger Request
       |
       | correlationId = ABC-123
       v
Trip Service
       |
       v
Matching Service
       |
       v
Payment Service
       |
       v
Notification Service
```

This allows the complete transaction to be traced across services.

---

# 7. Business Rules

| ID    | Rule                                                                    |
| ----- | ----------------------------------------------------------------------- |
| BR-01 | A Driver cannot be assigned to two active trips simultaneously.         |
| BR-02 | Only AVAILABLE Driver can be matched.                                   |
| BR-03 | Driver reservation must have timeout/lease.                             |
| BR-04 | One ride request can have only one active Driver assignment.            |
| BR-05 | Duplicate idempotency key must not create duplicate business operation. |
| BR-06 | Duplicate events must not cause duplicate side effects.                 |
| BR-07 | Trip state transitions must be valid.                                   |
| BR-08 | Payment can only be charged when trip reaches the required state.       |
| BR-09 | Failed distributed operations require retry or compensation.            |
| BR-10 | Business operations must contain correlation ID.                        |

---

# 8. Trip State Machine

```text
                  +-------------+
                  |  REQUESTED  |  (Price estimated, trip created)
                  +-------------+
                         |
                         v
                  +-------------+
                  |  SEARCHING  | ----+ timeout / no driver
                  +-------------+     |
                         |            v
              CAS reserve|       +-----------+
                         v       | CANCELLED |
                  +-------------+ <-----------+ (Passenger cancel before start)
                  |  ASSIGNED   |
                  +-------------+
                         |
                         | driver arrives & starts ride
                         v
                  +-------------+
                  | IN_PROGRESS |
                  +-------------+
                         |
                         | driver completes ride
                         v
                  +-------------+
                  |  COMPLETED  |
                  +-------------+
                         |
                         v
                    [ Payment ]
                      /     \
               success       failure
                  /             \
                 v               v
             +------+     +----------------+
             | PAID |     | PAYMENT_FAILED | (Debt log / retry)
             +------+     +----------------+
```

---

# 9. API Specification

## 9.1. Identity Service

| Method | Endpoint                       | Description                              |
| ------ | ------------------------------ | ---------------------------------------- |
| POST   | `/api/auth/register/passenger` | Register a passenger                     |
| POST   | `/api/auth/register/driver`    | Register a driver with vehicle (saga)    |
| POST   | `/api/auth/login`              | Login                                    |
| GET    | `/api/users/{id}`              | Get user                                 |

---

## 9.2. Driver Service

| Method | Endpoint                     | Description                                          |
| ------ | ---------------------------- | ---------------------------------------------------- |
| GET    | `/api/drivers/available`     | Get available drivers                                |
| GET    | `/api/drivers/nearby`        | Query nearby available drivers (Redis GEO & spatial) |
| POST   | `/api/drivers/{id}/reserve`  | CAS lock driver reservation with 30s lease           |
| DELETE | `/api/drivers/{id}/reserve`  | Compensating action: release driver lock to AVAILABLE|
| PATCH  | `/api/drivers/{id}/status`   | Update status (OFFLINE/AVAILABLE only; RESERVED/BUSY set by system) |
| PATCH  | `/api/drivers/{id}/location` | Update real-time GPS location                        |
| GET    | `/api/drivers/{id}`          | Get driver details                                   |

---

## 9.3. Trip Service

| Method | Endpoint                   | Description   |
| ------ | -------------------------- | ------------- |
| POST   | `/api/trips`               | Create ride   |
| GET    | `/api/trips/{id}`          | Get trip      |
| POST   | `/api/trips/{id}/accept`   | Accept trip   |
| POST   | `/api/trips/{id}/start`    | Start trip    |
| POST   | `/api/trips/{id}/complete` | Complete trip |
| POST   | `/api/trips/{id}/cancel`   | Cancel trip   |

---

## 9.4. Matching Service

| Method | Endpoint                            | Description    |
| ------ | ----------------------------------- | -------------- |
| POST   | `/api/matching/{requestId}/match`   | Start matching |
| POST   | `/api/matching/{requestId}/rematch` | Rematch        |

---

## 9.5. Pricing Service

| Method | Endpoint             | Description              |
| ------ | -------------------- | ------------------------ |
| POST   | `/api/pricing/quote` | Calculate estimated fare |
| POST   | `/api/pricing/final` | Calculate final fare     |

---

## 9.6. Payment Service

| Method | Endpoint                        | Description    |
| ------ | ------------------------------- | -------------- |
| POST   | `/api/payments/{tripId}/charge` | Charge payment |
| POST   | `/api/payments/{tripId}/refund` | Refund payment |
| GET    | `/api/payments/{tripId}`        | Get payment    |

---

# 10. Message Queue Design

## 10.1. Event Structure

```json
{
  "eventId": "evt-123",
  "eventType": "RideRequested",
  "aggregateId": "ride-001",
  "producer": "trip-service",
  "correlationId": "corr-001",
  "causationId": "evt-000",
  "occurredAt": "2026-09-30T10:00:00Z",
  "payload": {}
}
```

---

## 10.2. Events

| Event Name / Kafka Type | Topic            | Producer | Consumer                        | Description                     |
| ----------------------- | ---------------- | -------- | ------------------------------- | ------------------------------- |
| `trip.requested`        | `trip-events`    | Trip     | Matching, Notification          | Passenger creates ride request  |
| `driver.reserved`       | `matching-events`| Matching | Trip, Notification              | Driver reserved via CAS lock    |
| `driver.assigned`       | `trip-events`    | Trip     | Driver, Notification            | Driver assigned to trip         |
| `trip.started`          | `trip-events`    | Trip     | Driver, Notification            | Trip is in progress             |
| `trip.completed`        | `trip-events`    | Trip     | Payment, Notification           | Driver finished trip            |
| `payment.success`       | `payment-events` | Payment  | Trip, Notification              | Payment captured successfully   |
| `payment.failed`        | `payment-events` | Payment  | Trip, Notification              | Payment charge failed           |
| `trip.cancelled`        | `trip-events`    | Trip     | Driver, Matching, Notification  | Trip cancelled & compensation   |

---

# 11. Concurrency Control

## 11.1. Problem

Giả sử Driver `D001` đang ở trạng thái:

```text
AVAILABLE
```

Hai Passenger đồng thời gửi:

```text
Passenger A → request R1
Passenger B → request R2
```

Nếu không có concurrency control:

```text
R1 → D001
R2 → D001
```

Điều này vi phạm:

> A Driver must not be assigned to two trips simultaneously.

Đây là một trong những yêu cầu chức năng bắt buộc của DS03.

---

## 11.2. Proposed Solution

Sử dụng **Optimistic Concurrency Control**.

Driver record:

```text
driver_id
status
version
```

Atomic update:

```sql
UPDATE drivers
SET status            = 'RESERVED',
    reserved_for_trip = $trip_id,
    reserved_until    = NOW() + INTERVAL '30 seconds',
    version           = version + 1
WHERE driver_id = $driver_id
  AND status    = 'AVAILABLE'
  AND version   = $expected_version;
```

Nếu:

```text
affectedRows = 1
```

→ Reservation thành công.

Nếu:

```text
affectedRows = 0
```

→ Driver đã bị process khác reserve.

Request còn lại phải:

```text
Find another driver
```

hoặc retry.

---

# 12. Consistency Model

| Component            | Consistency                            |
| -------------------- | -------------------------------------- |
| Driver reservation   | Strong consistency                     |
| Trip state           | Strong consistency inside Trip Service |
| Matching → Trip      | Eventual consistency                   |
| Trip → Payment       | Eventual consistency                   |
| Notification         | Eventual consistency                   |
| Monitoring dashboard | Eventual consistency                   |

Hệ thống không cố gắng duy trì ACID transaction trên toàn bộ microservices.

Thay vào đó, distributed transaction được xử lý bằng Saga.

---

# 13. Distributed Transaction – Saga

Trip Service acts as the central **Saga Orchestrator**, tracking every state change in the `saga_log` table within `db_trip`.

## 13.1. Primary Saga Flow (Trip Lifecycle & Settlement)

```text
[Trip Service] Save Trip(REQUESTED) + saga_log(STARTED)
      |
      v (trip.requested -> Kafka)
[Matching Service] Find nearby & CAS lock driver -> (driver.reserved -> Kafka)
      |
      v
[Trip Service] Update Trip(ASSIGNING) + publish DriverAssigned
      | (Driver atomically confirms exact live reservation and publishes DriverAssignmentConfirmed)
      v
[Trip Service] Update Trip(ASSIGNED) + saga_log(DRIVER_RESERVED)
      |
      v ... Trip IN_PROGRESS -> Driver completes ride ...
[Trip Service] POST /api/trips/{id}/complete -> Trip(COMPLETED) + saga_log(TRIP_COMPLETED)
      |
      v (trip.completed -> Kafka)
[Payment Service] Deduplicate -> Process Charge -> (payment.success / failed -> Kafka)
      |
      +---> If Success: [Trip Service] Update Trip(PAID) + saga_log(SAGA_COMPLETED)
      |
      +---> If Failed:  [Trip Service] Update Trip(PAYMENT_FAILED) + saga_log(COMPENSATED)
             with paymentId, finalFare, currency, and reason as the durable debt record
```

## 13.2. Compensating Actions (Bồi hoàn nghiệp vụ)

1. **Huỷ chuyến trước khi khởi hành (Cancellation during Matching / Assigned):**
   * Nếu hành khách hủy hoặc Matching timeout: Orchestrator gọi bồi hoàn đồng bộ `DELETE /api/drivers/{id}/reserve`.
   * Driver Service giải phóng tài xế từ `RESERVED` về `AVAILABLE`. Trip cập nhật sang `CANCELLED`, saga_log ghi `SAGA_COMPENSATED`.
2. **Sự cố thanh toán sau khi hoàn tất cuốc (Payment Failure after completion):**
   * Vì hành khách đã được chở đến nơi, tài xế đã hoàn tất nhiệm vụ: Driver Service nhận `TripCompleted` và chuyển `BUSY -> AVAILABLE` ngay, không chờ kết quả thanh toán (xem `events.md` §4.5).
   * Orchestrator đánh dấu `trip.status = PAYMENT_FAILED` và ghi lỗi vào `saga_log` để thực hiện retry hoặc ghi nhận nợ, tuyệt đối **không rollback** sai trạng thái đặt xe của tài xế.

Đề bài DS03 yêu cầu ít nhất một distributed transaction sử dụng **Saga hoặc compensating actions**.

---

# 14. Fault Tolerance

## 14.1. Failure Scenario 1 – Matching Service Down

```text
Passenger
   |
   v
Trip Service
   |
   v
RideRequested
   |
   v
Message Broker
   X
Matching Service
```

Message vẫn nằm trong queue.

Sau khi Matching Service restart:

```text
Matching Service
      |
      v
Consume pending message
      |
      v
Process matching
```

---

## 14.2. Failure Scenario 2 – Payment Timeout

```text
Trip
 |
 | PaymentRequested
 v
Payment
 |
 X timeout
 |
 v
Retry
 |
 X
 |
 v
DLQ / PaymentFailed
```

Payment phải sử dụng idempotency để tránh:

```text
Charge
Charge
```

khi cùng một request được retry.

---

## 14.3. Failure Scenario 3 – Driver Disconnected

Nếu Driver mất kết nối khi đang `RESERVED`:

```text
Heartbeat timeout
       |
       v
Reservation expired
       |
       v
Driver → AVAILABLE/OFFLINE
       |
       v
Matching → Rematch
```

---

# 15. Retry Strategy

Retry phải có giới hạn.

Ví dụ:

```text
MAX_RETRY = 3

Attempt 1
    ↓
Attempt 2
    ↓
Attempt 3
    ↓
DLQ
```

Có thể sử dụng exponential backoff:

```text
1s
2s
4s
```

Không retry vô hạn.

---

# 16. Deduplication

Mỗi message:

```text
eventId = UUID
```

Consumer lưu:

```text
processed_events
```

Ví dụ:

```text
eventId = evt-123
```

Nếu `evt-123` đã tồn tại:

```text
DO NOT PROCESS AGAIN
```

Điều này đặc biệt quan trọng đối với Payment Service.

---

# 17. Dead Letter Queue

Message được đưa vào DLQ khi:

```text
Processing failed
        |
        v
Retry 1
        |
        v
Retry 2
        |
        v
Retry 3
        |
        v
DLQ
```

DLQ message cần giữ:

```text
eventId
eventType
payload
error
retryCount
failedAt
correlationId
```

---

# 18. Security Requirements

## 18.1. Authentication

Sử dụng:

```text
JWT
```

Flow:

```text
Login
  |
  v
Identity Service
  |
  v
JWT
  |
  v
API Gateway
  |
  v
Microservices
```

## 18.2. Authorization

### Passenger

Có quyền:

```text
Create Ride
Cancel Own Ride
View Own Trip
```

### Driver

Có quyền:

```text
Update Own Status
Update Own Location
Accept Trip
Reject Trip
Start Trip
Complete Trip
```

## 18.3. Security Rules

* Không lưu plaintext password.
* Không commit secrets vào GitHub.
* Dùng environment variables.
* Validate input.
* Giới hạn request/message size.
* Không log password/token.
* Kiểm tra role.
* TLS được khuyến nghị.

Các yêu cầu này tương ứng với mục Basic Security của đề bài.

---

# 19. Observability

## 19.1. Logging

Mỗi log nên chứa:

```text
timestamp
service
instance
level
eventType
correlationId
requestId
result
duration
```

Ví dụ:

```text
2026-09-30T10:20:01
service=matching-service
event=DriverMatched
correlationId=corr-001
driverId=D001
tripId=T001
result=SUCCESS
```

## 19.2. Metrics

Các metrics đề xuất:

```text
http_requests_total
http_request_duration
http_errors_total
matching_success_total
matching_failure_total
payment_success_total
payment_failure_total
queue_messages_total
queue_dlq_messages_total
```

## 19.3. Distributed Tracing

Trace:

```text
Gateway
   |
   +--> Trip
          |
          +--> Matching
          |
          +--> Pricing
          |
          +--> Payment
                  |
                  +--> Notification
```

---

# 20. Non-Functional Requirements

| ID     | Requirement                                                       |
| ------ | ----------------------------------------------------------------- |
| NFR-01 | Services must run independently.                                  |
| NFR-02 | Services must communicate over network.                           |
| NFR-03 | Each business service owns its database/schema.                   |
| NFR-04 | System must support concurrent requests.                          |
| NFR-05 | Retry must be bounded.                                            |
| NFR-06 | Event processing must be idempotent.                              |
| NFR-07 | System must recover from selected service failures.               |
| NFR-08 | System must provide sufficient logs for demonstration.            |
| NFR-09 | Secrets must not be stored in source code.                        |
| NFR-10 | System must be deployable using Docker or equivalent environment. |

---

# 21. Deployment Architecture

Hệ thống được thiết kế để chạy bằng **Docker Compose** trong môi trường development/demo. Docker Compose chịu trách nhiệm khởi động các service, tạo network, cung cấp service discovery thông qua Docker DNS và kết nối các infrastructure components.

## 21.1. Docker Network

Tất cả thành phần cần giao tiếp nội bộ sử dụng network:

```text
ride-hailing-network
```

Docker DNS cho phép service gọi nhau bằng tên:

```text
api-gateway:8080
identity-service:8081
driver-service:8082
trip-service:8083
matching-service:8084
pricing-service:8085
payment-service:8086
notification-service:8087
kafka:9092
```

Không triển khai Eureka Server hoặc Eureka Client.

## 21.2. Application Services

| Service | Internal Port | Docker Service Name | Persistence | Message Broker |
| --- | ---: | --- | --- | --- |
| API Gateway | 8080 | `api-gateway` | No | No |
| Identity Service | 8081 | `identity-service` | PostgreSQL (`db_identity`) | No |
| Driver Service | 8082 | `driver-service` | PostgreSQL (`db_driver`) + Redis GEO | Optional |
| Trip Service | 8083 | `trip-service` | PostgreSQL (`db_trip`) | Kafka |
| Matching Service | 8084 | `matching-service` | PostgreSQL (`db_matching`) | Kafka |
| Pricing Service | 8085 | `pricing-service` | PostgreSQL (`db_pricing`) | Optional |
| Payment Service | 8086 | `payment-service` | PostgreSQL (`db_payment`) | Kafka |
| Notification Service | 8087 | `notification-service` | PostgreSQL (`db_notification`) | Kafka |

Chỉ API Gateway bắt buộc publish port ra host cho client truy cập. Các service backend chỉ expose trong Docker network nội bộ.

## 21.3. Database-per-Service

Mỗi business service sở hữu database PostgreSQL riêng biệt theo đặc tả tại `database_schema.md`:

```text
identity-db      → db_identity     (users, refresh_tokens)
driver-db        → db_driver       (drivers, vehicles, driver_locations, processed_events + Redis GEO)
trip-db          → db_trip         (trips, saga_log, outbox_events, processed_events, idempotency_keys)
matching-db      → db_matching     (match_requests, driver_offers, processed_events)
pricing-db       → db_pricing      (fare_rules, surge_zones, quotes)
payment-db       → db_payment      (payments, ledger, outbox_events, processed_events)
notification-db  → db_notification (notifications, processed_events)
```

Ví dụ connection trong Docker:

```text
jdbc:postgresql://trip-db:5432/trip_db
```

Không sử dụng `localhost` cho service-to-database communication trong Docker.

## 21.4. Kafka

Hệ thống chọn **Apache Kafka** làm message broker chính. Phiên bản demo sử dụng một Kafka broker chạy trong Docker Compose.

Kafka có service name:

```text
kafka:9092
```

Các service chính sử dụng Kafka:

* Trip Service
* Matching Service
* Payment Service
* Notification Service

Các service khác chỉ sử dụng Kafka khi business flow thực tế yêu cầu.

## 21.5. API Gateway Routing

API Gateway sử dụng Spring Cloud Gateway và route trực tiếp tới Docker service name. Không sử dụng `lb://...` vì hệ thống không có Eureka/service discovery layer.

Ví dụ:

```text
/api/auth/**       → http://identity-service:8081
/api/drivers/**    → http://driver-service:8082
/api/trips/**      → http://trip-service:8083
/api/matching/**   → http://matching-service:8084
/api/pricing/**    → http://pricing-service:8085
/api/payments/**   → http://payment-service:8086
```

## 21.6. Monitoring Stack

Monitoring sử dụng:

```text
Spring Boot Actuator
        ↓
Micrometer Prometheus Registry
        ↓
Prometheus
        ↓
Grafana
```

Mỗi Spring Boot service cung cấp endpoint:

```text
/actuator/health
/actuator/prometheus
```

Prometheus scrape metrics thông qua Docker service name, ví dụ:

```text
http://trip-service:8083/actuator/prometheus
http://matching-service:8084/actuator/prometheus
http://payment-service:8086/actuator/prometheus
```

Grafana sử dụng Prometheus làm datasource.

## 21.7. Docker Compose Components

Docker Compose tối thiểu bao gồm:

```text
api-gateway
identity-service
driver-service
trip-service
matching-service
pricing-service
payment-service
notification-service

identity-db
driver-db
trip-db
matching-db
payment-db

kafka
prometheus
grafana
```

Các secret và cấu hình môi trường phải được cung cấp thông qua environment variables hoặc `.env`; không commit secret thật vào repository.

---

# 22. Suggested Technology Stack

| Layer             | Technology                                                  |
| ----------------- | ----------------------------------------------------------- |
| Language          | Java 17 (LTS)                                               |
| Framework         | Spring Boot 3.x                                             |
| Build Tool        | Gradle (Gradle Wrapper 8.x / Multi-project build)           |
| API               | Spring Web (RESTful APIs) / Springdoc OpenAPI (Swagger UI)  |
| Security          | Spring Security + JWT (JJWT / Nimbus JOSE)                  |
| Database          | PostgreSQL (Database-per-service pattern)                   |
| ORM / Persistence | Spring Data JPA / Hibernate                                 |
| Message Queue     | Apache Kafka + Spring Kafka                                |
| Service Discovery | Docker Compose service name + Docker DNS                    |
| API Gateway       | Spring Cloud Gateway                                        |
| Container         | Docker + Docker Compose                                     |
| Observability     | Spring Boot Actuator, Micrometer, Prometheus + Grafana      |
| Testing           | JUnit 5 + Mockito + Testcontainers                          |
| Version Control   | Git + GitHub                                                |

### Lý do lựa chọn và vai trò kỹ thuật:

* **Java 17 (LTS):** Phiên bản dài hạn ổn định, được cộng đồng và doanh nghiệp hỗ trợ rộng rãi. Tận dụng các tính năng hiện đại như Java Records (rất thích hợp cho DTOs và Event payloads bất biến), Pattern Matching, Text Blocks, cùng GC hiệu năng cao (ZGC/G1).
* **Spring Boot 3.x:** Thế hệ mới nhất của Spring Boot được thiết kế tối ưu trên nền tảng Java 17+ và Spring Framework 6. Cung cấp hệ sinh thái mạnh mẽ cho microservices: tích hợp sẵn Actuator, Micrometer cho observability, cấu hình dễ dàng cho REST và Message Broker.
* **Gradle:** Công cụ build hiện đại với hiệu suất biên dịch vượt trội (nhờ Incremental Build và Build Cache). Hỗ trợ mô hình Gradle Multi-Project giúp tổ chức đồng bộ toàn bộ các microservices trong một repository duy nhất (monorepo), dễ dàng chia sẻ thư viện dùng chung (`common-library`) chứa các DTOs, Event schemas và tiện ích chung mà không cần publish artifact ra ngoài.
* **Docker + Docker Compose:** Dùng để đóng gói và chạy các microservices cùng infrastructure trong môi trường nhất quán. Docker Compose cung cấp network và DNS nội bộ, cho phép service-to-service communication bằng service name mà không cần Eureka Server.
* **Prometheus + Grafana:** Dùng để thu thập và trực quan hóa metrics từ Spring Boot Actuator/Micrometer, hỗ trợ quan sát health, request, error, matching, payment và message-processing metrics trong quá trình demo.

---

# 23. Testing Requirements

Đề bài yêu cầu tối thiểu:

* 8 functional tests
* 2 concurrency/conflict tests
* 2 fault/recovery tests
* 2 security/invalid-data tests
* 1 performance/scalability experiment.

## 23.1. Functional Tests

| ID    | Test                     |
| ----- | ------------------------ |
| FT-01 | Register Passenger       |
| FT-02 | Login                    |
| FT-03 | Driver becomes AVAILABLE |
| FT-04 | Passenger creates ride   |
| FT-05 | Driver matching          |
| FT-06 | Driver accepts           |
| FT-07 | Trip completion          |
| FT-08 | Payment success          |

## 23.2. Concurrency Tests

### CT-01

Hai Passenger request cùng một Driver.

Expected:

```text
Request A → SUCCESS
Request B → REJECT / REMATCH
```

### CT-02

Duplicate event được gửi hai lần.

Expected:

```text
First event  → Process
Second event → Deduplicate
```

## 23.3. Fault Tests

### FT-01

Stop Matching Service.

Expected:

```text
Message → Queue
Service restart
       ↓
Message processed
```

### FT-02

Payment timeout.

Expected:

```text
Retry
 ↓
Success OR DLQ
```

## 23.4. Security Tests

* Invalid JWT.
* Missing JWT.
* Wrong role.
* Invalid input.
* Duplicate idempotency key.

---

# 24. Performance Experiment

Workload đề xuất:

```text
10 concurrent users
       ↓
50 concurrent users
       ↓
100 concurrent users
       ↓
500 concurrent users
```

Metrics:

```text
Throughput
Average latency
P50 latency
P95 latency
Error rate
CPU usage
Memory usage
Queue processing time
```

Có thể sử dụng:

```text
JMeter
k6
Gatling
```

---

# 25. Mandatory Demonstration

Theo DS03, demo phải thể hiện các tình huống chính sau: khởi động service độc lập, Driver available, Passenger request, Matching chọn Driver, Driver hoàn thành trip, hai Passenger đồng thời request cùng Driver, service failure, retry/queue/compensation và restart service để tiếp tục xử lý.

## Demo Flow

### Scenario 1 – Normal Ride

```text
Driver → AVAILABLE

Passenger
    ↓
Request Ride
    ↓
Trip Service
    ↓
RideRequested
    ↓
Matching
    ↓
DriverMatched
    ↓
Driver ACCEPT
    ↓
START
    ↓
COMPLETE
    ↓
Pricing
    ↓
Payment
    ↓
Notification
```

### Scenario 2 – Concurrent Request

```text
Passenger A ──┐
              ├──> Matching ──> Driver D001
Passenger B ──┘

Result:

A → D001
B → Rematch
```

### Scenario 3 – Service Failure

```text
Trip
 |
 v
Message Broker
 |
 X
Matching DOWN
 |
 v
Queue
 |
 v
Matching RESTART
 |
 v
Process message
```

### Scenario 4 – Payment Failure

```text
TripCompleted
      |
      v
Payment
      |
      X
    FAILED
      |
      v
Retry
      |
      +---- SUCCESS
      |
      +---- DLQ / Compensation
```

---

# 26. Repository Structure

Theo yêu cầu deliverable của đề bài, repository phải có `README.md`, `src/`, `deploy/`, `tests/`, `scripts/`, `docs/` và `.env.example`.

Đề xuất cấu trúc dự án dựa trên mô hình Gradle Multi-project (hỗ trợ build đồng nhất toàn bộ hoặc độc lập từng microservice):

```text
ride-hailing-microservices/
│
├── README.md
├── .env.example
├── docker-compose.yml
├── build.gradle              # Gradle root build script
├── settings.gradle           # Cấu hình danh sách subprojects microservices
├── gradlew                   # Gradle wrapper script (Linux/macOS)
├── gradlew.bat               # Gradle wrapper script (Windows)
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── src/
│   ├── api-gateway/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── identity-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── driver-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── trip-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── matching-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── pricing-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── payment-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   ├── notification-service/
│   │   ├── build.gradle
│   │   └── src/main/java/...
│   └── common-library/       # Shared DTOs, Event schemas, Exceptions
│       ├── build.gradle
│       └── src/main/java/...
│
├── deploy/
│   ├── docker/
│   ├── kafka/
│   ├── postgres/
│   └── monitoring/
│       ├── prometheus/
│       │   └── prometheus.yml
│       └── grafana/
│
├── tests/
│   ├── integration/
│   ├── concurrency/
│   ├── fault/
│   ├── security/
│   └── performance/
│
├── scripts/
│   ├── start.sh
│   ├── stop.sh
│   ├── load-test.sh
│   └── fault-injection.sh
│
└── docs/
    ├── SRS.md
    ├── architecture.md
    ├── api.md
    ├── events.md
    ├── report.pdf
    └── slides.pdf
```

---

# 27. Team Task Assignment

Đề xuất nhóm 3 thành viên:

| Member   | Main Responsibility                                                      |
| -------- | ------------------------------------------------------------------------ |
| Member 1 | Identity + Driver Service + Security                                     |
| Member 2 | Trip + Matching + Concurrency + Saga                                     |
| Member 3 | Pricing + Payment + Notification + Message Queue + Deployment/Monitoring |

Mỗi thành viên cần có commit history thể hiện phần đóng góp của mình. Đề bài cũng yêu cầu mỗi thành viên hiểu toàn bộ architecture và có thể giải thích phần được giao.

---

# 28. Requirements Traceability

| DS03 Requirement           | Solution                       |
| -------------------------- | ------------------------------ |
| ≥ 4 business microservices | 7 business services            |
| REST/gRPC                  | REST APIs                      |
| Message Queue              | Apache Kafka + Spring Kafka   |
| Event ID                   | UUID                           |
| Idempotency                | Idempotency key                |
| Retry                      | Bounded retry                  |
| Deduplication              | Processed event store          |
| DLQ                        | Dead Letter Queue              |
| Correlation ID             | UUID correlation ID            |
| Distributed transaction    | Saga                           |
| Concurrency                | Optimistic concurrency control |
| Fault tolerance            | Timeout + Retry + Recovery     |
| Eventual consistency       | Event-driven communication     |
| Security                   | JWT + RBAC                     |
| Observability              | Logs + Metrics + Tracing + Prometheus + Grafana |

---

# 29. Risks and Limitations

## 29.1. Single Point of Failure

Nếu chỉ có một Kafka/RabbitMQ instance:

```text
Broker DOWN
   ↓
Async communication unavailable
```

Trong phiên bản prototype, đây là giới hạn có thể chấp nhận nhưng phải được nêu trong báo cáo.

## 29.2. Eventual Consistency

Một số UI có thể tạm thời hiển thị trạng thái cũ.

Ví dụ:

```text
Driver accepted
      ↓
Trip Service
      ↓
Notification event
```

Notification có thể đến sau một khoảng thời gian.

## 29.3. Simulated Location

Location không phải GPS thật.

## 29.4. Simulated Payment

Payment không đại diện cho payment gateway production.

## 29.5. Docker-Based Service Discovery

Service discovery dựa trên Docker Compose service name phù hợp với môi trường Docker Compose của project. Đây không phải là một service discovery mechanism độc lập như Eureka và phụ thuộc vào việc các container nằm trong cùng Docker network.

---

# 30. Acceptance Criteria

Hệ thống được xem là đạt yêu cầu khi:

* [ ] Có ít nhất 4 business microservices.
* [ ] Các service chạy độc lập.
* [ ] Các service giao tiếp qua network.
* [ ] Service-to-service discovery sử dụng Docker service name/Docker DNS.
* [ ] Không sử dụng Eureka Server/Eureka Client.
* [ ] Mỗi service sở hữu database/schema riêng.
* [ ] Có REST communication.
* [ ] Có asynchronous message queue.
* [ ] Có event ID/idempotency key.
* [ ] Có retry.
* [ ] Có deduplication.
* [ ] Có DLQ/error-message storage.
* [ ] Có correlation ID.
* [ ] Có distributed transaction bằng Saga/compensation.
* [ ] Có concurrency scenario.
* [ ] Chứng minh Driver không bị assign cho hai Trip.
* [ ] Có ít nhất hai failure/recovery scenarios.
* [ ] Có security tests.
* [ ] Có performance experiment.
* [ ] Có logs/traces chứng minh flow.
* [ ] Có Prometheus thu thập metrics.
* [ ] Có Grafana hiển thị metrics/monitoring dashboard.
* [ ] Có thể restart service và tiếp tục xử lý.
* [ ] Repository có đầy đủ deliverables.

---

# 31. Deliverables

Theo đề bài, repository phải có source code, deployment configuration, tests, scripts, documentation và `.env.example`; đồng thời phải có commit history của các thành viên, hướng dẫn redeployment, task-assignment table và final release/tag.

### Required

```text
README.md
src/
deploy/
tests/
scripts/
docs/
.env.example
```

### Documentation

```text
docs/SRS.md
docs/architecture.md
docs/api.md
docs/events.md
docs/report.pdf
docs/slides.pdf
```

---

# 32. Conclusion

Ride-Hailing Microservices System là một hệ thống phân tán gồm nhiều business microservices độc lập, sử dụng REST cho synchronous communication và message queue cho asynchronous communication.

Các vấn đề Distributed Systems trọng tâm được giải quyết gồm:

```text
Microservices
      ↓
Network Communication
      ↓
Message Queue
      ↓
Eventual Consistency
      ↓
Concurrency Control
      ↓
Idempotency
      ↓
Retry + Deduplication
      ↓
DLQ
      ↓
Saga
      ↓
Fault Tolerance
      ↓
Recovery
```

Thiết kế này tập trung vào các yêu cầu bắt buộc của DS03 thay vì chỉ xây dựng một hệ thống CRUD đặt xe đơn thuần.

---

# Appendix A – Core Ride Flow

```text
                    PASSENGER
                        |
                        | POST /trips
                        v
                  +-------------+
                  | Trip Service|
                  +------+------+
                         |
                         | RideRequested
                         v
                  +-------------+
                  |Message Broker|
                  +------+------+
                         |
                         v
                  +-------------+
                  |  Matching   |
                  +------+------+
                         |
                         | Reserve
                         v
                  +-------------+
                  |Driver Service|
                  +------+------+
                         |
                         | DriverMatched
                         v
                  +-------------+
                  | Trip Service|
                  +------+------+
                         |
                         | Driver Accept
                         v
                     IN_PROGRESS
                         |
                         | Complete
                         v
                  +-------------+
                  |   Pricing   |
                  +------+------+
                         |
                         v
                  +-------------+
                  |   Payment   |
                  +------+------+
                         |
                 +-------+-------+
                 |               |
              SUCCESS           FAILED
                 |               |
                 v               v
              PAID          RETRY/DLQ
                 |
                 v
             Notification
```

# Appendix B – Distributed System Concepts Demonstrated

| Concept                    | Demonstration                       |
| -------------------------- | ----------------------------------- |
| Service independence       | Separate containers                 |
| Naming/discovery           | Docker Compose service name + Docker DNS |
| Network communication      | REST                                |
| Asynchronous communication | Apache Kafka                         |
| Concurrency                | Concurrent driver reservation       |
| Consistency                | Strong local + eventual distributed |
| Distributed transaction    | Saga                                |
| Idempotency                | Event/request IDs                   |
| Fault tolerance            | Retry + timeout                     |
| Recovery                   | Queue + persisted state             |
| Failure detection          | Health check/heartbeat              |
| Observability              | Correlation ID + logs               |
| Security                   | JWT + RBAC                          |
| Scalability                | Multiple service instances          |
