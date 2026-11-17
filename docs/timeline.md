# 📅 TIMELINE PHÁT TRIỂN DỰ ÁN DS03

## Ride-Hailing Microservices System – Simulating Grab/Uber

**Thời gian:** 30/09/2026 → 20/11/2026
**Số thành viên:** 3
**Deadline nội bộ:** 20/11/2026
**Deadline chính thức theo specification:** 22/11/2026
**Final Release:** `v1.0.0`

---

# 1. Phân công thành viên

| Thành viên   | Phụ trách chính                             | Phụ trách hỗ trợ          |
| ------------ | ------------------------------------------- | ------------------------- |
| **Member 1** | Identity + Driver + Security                | API Gateway, API Contract |
| **Member 2** | Trip + Matching + Concurrency + Saga        | Integration Test          |
| **Member 3** | Pricing + Payment + Notification + Kafka/MQ | Docker, Monitoring, CI/CD |

> **Lưu ý:** Không nên để Member 3 một mình phụ trách toàn bộ Infrastructure/DevOps. Các thành viên cần hỗ trợ lẫn nhau và mỗi người phải có commit history thể hiện phần đóng góp của mình.

---

# 2. Timeline tổng thể

```text
30/09 ─────────────────────────────────────────────────── 20/11
  │
  ├── W1 ── Foundation + Contract
  │
  ├── W2 ── Core Services
  │
  ├── W3 ── End-to-End Happy Path
  │
  ├── W4 ── Kafka + Distributed Communication
  │
  ├── W5 ── Saga + Fault Tolerance
  │
  ├── W6 ── Testing + Monitoring + Performance
  │
  ├── W7 ── Integration + Final Demo
  │
  └── FINAL ── Documentation + Release v1.0.0
```

---

# 3. W1 — 30/09 → 04/10

## 🎯 Mục tiêu: Foundation + Contract

### Member 1 — Identity + Driver

* [ ] Setup `identity-service`
* [ ] Setup `driver-service`
* [ ] PostgreSQL riêng cho từng service
* [ ] User/Role entity
* [ ] Register/Login
* [ ] JWT
* [ ] Driver/Vehicle entity
* [ ] Driver status:

  * [ ] `OFFLINE`
  * [ ] `AVAILABLE`
  * [ ] `RESERVED`
  * [ ] `BUSY`
* [ ] API contract Identity/Driver
* [ ] Unit test cơ bản

### Member 2 — Trip + Matching Skeleton

* [ ] Setup `trip-service`
* [ ] Setup `matching-service`
* [ ] Thiết kế `RideRequest`
* [ ] Thiết kế `Trip`
* [ ] Thiết kế `TripStatus`
* [ ] Trip lifecycle:

```text
REQUESTED
    ↓
SEARCHING
    ↓
ASSIGNED
    ↓
IN_PROGRESS
    ↓
COMPLETED
    ↓
PAID
```

> Failure states: `CANCELLED`, `PAYMENT_FAILED`

* [ ] Matching API contract
* [ ] Thiết kế concurrency scenario

### Member 3 — Infrastructure + Kafka

* [ ] Setup Gradle multi-project
* [ ] Docker Compose
* [ ] PostgreSQL
* [ ] Kafka
* [ ] Kafka topics
* [ ] `common-library`
* [ ] Event envelope
* [ ] Setup Actuator
* [ ] Setup Prometheus/Grafana skeleton

### Event Envelope

```json
{
  "eventId": "UUID",
  "eventType": "RideRequested",
  "aggregateId": "trip-id",
  "aggregateType": "Trip",
  "producer": "trip-service",
  "correlationId": "UUID",
  "causationId": "UUID",
  "schemaVersion": "1.0",
  "occurredAt": "...",
  "payload": {}
}
```

> Định nghĩa đầy đủ và quy ước giá trị (PascalCase, UUID thuần, tiền là JSON number): `docs/events.md` §2.

### ✅ Deadline — 04/10

* [ ] Git repository
* [ ] `main`
* [ ] `develop`
* [ ] 7 service skeleton
* [ ] Docker Compose
* [ ] PostgreSQL
* [ ] Kafka
* [ ] API contracts
* [ ] Event contracts
* [ ] JWT skeleton

> Chưa cần hoàn thiện toàn bộ business logic.

---

# 4. W2 — 05/10 → 11/10

## 🎯 Mục tiêu: Hoàn thành Core Business Services

### Member 1 — Identity + Driver

#### Identity Service

```text
POST /api/auth/register/passenger
POST /api/auth/register/driver
POST /api/auth/login
GET  /api/users/{id}
```

* [ ] Password hashing
* [ ] JWT
* [ ] RBAC
* [ ] Validation
* [ ] Global exception handling

#### Driver Service

```text
GET   /api/drivers/available
PATCH /api/drivers/{id}/status
PATCH /api/drivers/{id}/location
GET   /api/drivers/{id}
```

---

### Member 2 — Trip + Matching

#### Trip Service

```text
POST /api/trips
GET  /api/trips/{id}
POST /api/trips/{id}/accept
POST /api/trips/{id}/start
POST /api/trips/{id}/complete
POST /api/trips/{id}/cancel
```

#### Matching Service

```text
POST /api/matching/{requestId}/match
POST /api/matching/{requestId}/rematch
```

Implement:

```text
RideRequested
      ↓
SEARCHING (trip status)
      ↓
Find AVAILABLE Driver
      ↓
CAS Reserve Driver → RESERVED
      ↓
Publish driver.reserved event
      ↓
ASSIGNED (trip status)
```

---

### Member 3 — Pricing + Payment + Notification

#### Pricing

```text
POST /api/pricing/quote
POST /api/pricing/final
```

#### Payment

```text
POST /api/payments/{tripId}/charge
GET  /api/payments/{tripId}
POST /api/payments/{tripId}/refund
```

#### Notification

* [ ] Kafka consumer
* [ ] Passenger notification
* [ ] Driver notification

### ✅ Deadline — 11/10

Có thể chạy và test từng service độc lập bằng Postman.

---

# 5. W3 — 12/10 → 18/10

# 🎯 Mục tiêu: End-to-End Happy Path

Đây là **checkpoint quan trọng nhất**.

Hệ thống phải chạy được:

```text
Passenger
   ↓
API Gateway
   ↓
Trip Service
   ↓
RideRequested
   ↓
Kafka
   ↓
Matching Service
   ↓
Driver Service
   ↓
Driver Reserved
   ↓
Driver Accept
   ↓
Trip
   ↓
Complete
   ↓
Pricing
   ↓
Payment
   ↓
Notification
```

### Member 1

* [ ] API Gateway
* [ ] JWT authentication
* [ ] Role authorization
* [ ] Driver reservation API
* [ ] Driver state transition

### Member 2

* [ ] Matching algorithm
* [ ] Driver selection
* [ ] Reservation timeout
* [ ] Rematch
* [ ] Trip state machine
* [ ] Concurrent request handling phiên bản đầu

> Cập nhật triển khai M2 ngày 09/11/2026: source và test W3 nằm trên nhánh `feature/m2/week3-integration`; xem `docs/member2-week3-handoff.md`. Các ô nghiệm thu giữ chưa đánh dấu đến khi có reserve/Gateway của M1, cấu hình M3 và demo E2E toàn hệ thống.

### Member 3

* [ ] Kafka producer
* [ ] Kafka consumer
* [ ] Pricing integration
* [ ] Payment integration
* [ ] Notification integration

### 🎯 Demo cuối W3

```text
Register
  ↓
Login
  ↓
Driver AVAILABLE
  ↓
Passenger tạo Trip
  ↓
Matching
  ↓
Driver được chọn
  ↓
Driver Accept
  ↓
Start Trip
  ↓
Complete Trip
  ↓
Calculate Fare
  ↓
Payment
  ↓
Notification
```

### 🚨 Quy tắc

Nếu cuối W3 chưa chạy được Happy Path:

> **Không chuyển sang làm Advanced Features.**

---

# 6. W4 — 19/10 → 25/10

# 🎯 Mục tiêu: Kafka + Distributed Communication

Tuần này tập trung vào các yêu cầu Distributed Systems.

---

## Member 1 — Security + Observability

* [ ] Driver events
* [ ] Authentication/security hoàn chỉnh
* [ ] Correlation ID
* [ ] Request ID
* [ ] Structured logging

Ví dụ:

```text
2026-10-20
driver-service
INFO
correlationId=xxx
event=DriverReserved
driverId=D01
```

---

## Member 2 — Concurrency Control

### Scenario

```text
Passenger A ──┐
              ├──> Driver D01
Passenger B ──┘
```

Expected:

```text
Passenger A → SUCCESS
Passenger B → REJECT
```

Không được xảy ra:

```text
Passenger A → SUCCESS
Passenger B → SUCCESS
```

Implement:

* [ ] Optimistic concurrency control
* [ ] Driver version
* [ ] Atomic reservation
* [ ] Concurrency test

---

## Member 3 — Kafka + Reliability

Implement:

* [ ] Kafka Producer
* [ ] Kafka Consumer
* [ ] Event ID
* [ ] Correlation ID
* [ ] Idempotency
* [ ] Processed Event Store
* [ ] Retry
* [ ] Dead Letter Queue

### Events (8 events chính thức — theo events.md)

```text
trip-events topic:
  RideRequested    → trip-service publishes
  DriverAssigned   → trip-service publishes
  TripStarted      → trip-service publishes
  TripCompleted    → trip-service publishes
  TripCancelled    → trip-service publishes

matching-events topic:
  DriverReserved   → matching-service publishes

payment-events topic:
  PaymentSucceeded → payment-service publishes
  PaymentFailed    → payment-service publishes
```

> **Lưu ý:** Không có event `DriverMatched`, `DriverRejected`, `TripAccepted`, `PaymentRequested`.

### Retry

```text
Message
   ↓
Consumer
   ↓
FAIL
   ↓
Retry 1
   ↓
FAIL
   ↓
Retry 2
   ↓
FAIL
   ↓
Retry 3
   ↓
FAIL
   ↓
DLQ
```

### ✅ Deadline — 25/10

```text
✓ REST communication
✓ Kafka communication
✓ Event ID
✓ Correlation ID
✓ Idempotency
✓ Deduplication
✓ Concurrency control
✓ Retry
✓ DLQ
```

---

# 7. W5 — 26/10 → 01/11

# 🎯 Mục tiêu: Saga + Fault Tolerance

Đây là tuần tập trung vào các yêu cầu distributed transaction và recovery.

---

## Member 2 — Saga

### Payment Flow (theo events.md Saga Section 9)

```text
[9]  Driver: POST /api/trips/{id}/complete
       → trip-service gọi Pricing Service: POST /api/pricing/final → finalFare
       → Trip status: IN_PROGRESS → COMPLETED
[10] trip-service publishes: trip.completed (kèm finalFare) → trip-events
       ↓
[11] payment-service consumes: trip.completed
       → Deduplication check (eventId)
       → Charge finalFare lấy từ event (không gọi Pricing)
[12] payment-service publishes: payment.success → payment-events
       ↓
[13] trip-service consumes: payment.success
       → Trip status: COMPLETED → PAID
       → saga_log: SAGA_COMPLETED
[14] notification-service consumes: payment.success
       → Notify passenger + driver
```

### Payment Failure & Compensation

```text
payment-service → FAILED
   ↓
Retry (max 3, exponential backoff)
   ↓
FAILED → DLQ
   ↓
payment-service publishes: payment.failed → payment-events
   ↓
trip-service consumes: payment.failed
   → Trip status: COMPLETED → PAYMENT_FAILED
   → saga_log: COMPENSATED
   → Log debt (NO driver rollback)
```

> **Lưu ý:** Driver state KHÔNG bị rollback. Driver đã hoàn thành dịch vụ.

* [ ] Saga flow (Trip Service là orchestrator theo SRS §13, các bước nối với nhau qua Kafka event)
* [ ] Compensation cho payment failure
* [ ] Payment failure → PAYMENT_FAILED state
* [ ] Trip state consistency (saga_log table)

---

## Member 3 — Retry + DLQ + Recovery

* [ ] Bounded retry
* [ ] Exponential backoff
* [ ] DLQ
* [ ] Failed message storage
* [ ] Message recovery
* [ ] Consumer restart

Scenario:

```text
Message
   ↓
Service DOWN
   ↓
Kafka giữ message
   ↓
Service restart
   ↓
Consumer nhận lại
   ↓
Process thành công
```

---

## Member 1 — Fault + Security

### Security

* [ ] Invalid JWT
* [ ] Expired JWT
* [ ] Unauthorized role
* [ ] Invalid request
* [ ] Input validation

### Fault

```text
Matching Service DOWN
       ↓
Trip vẫn tồn tại
       ↓
Kafka giữ message
       ↓
Matching restart
       ↓
Process message
```

### ✅ Deadline — 01/11

Phải demo được:

```text
✓ Saga
✓ Payment failure
✓ Compensation
✓ Retry
✓ DLQ
✓ Service restart
✓ Message recovery
```

---

# 8. W6 — 02/11 → 08/11

# 🎯 Mục tiêu: Testing + Monitoring + Performance

Từ tuần này:

> **Feature Freeze**

Không thêm business feature lớn.

---

## Member 1 — Security Testing

* [ ] Register test
* [ ] Login test
* [ ] Invalid password
* [ ] Expired JWT
* [ ] Unauthorized role
* [ ] Invalid input
* [ ] Security regression

---

## Member 2 — Concurrency Testing

### Test 1

```text
2 passengers
     ↓
1 driver
```

### Test 2

```text
2 requests đồng thời
        ↓
same driver
```

Expected:

```text
1 SUCCESS
1 REJECT
```

* [ ] JUnit test
* [ ] Integration test
* [ ] Concurrent test
* [ ] Verify database state

---

## Member 3 — Fault + Performance + Monitoring

### Fault Tests

* [ ] Payment Service DOWN
* [ ] Matching Service DOWN
* [ ] Kafka Consumer DOWN
* [ ] Restart service
* [ ] Duplicate event

### Performance

```text
10 users
50 users
100 users
500 users
```

Measure:

```text
Throughput
P50
P95
Error Rate
CPU
Memory
Queue Processing
```

### Monitoring

* [ ] Prometheus
* [ ] Grafana
* [ ] Service health
* [ ] API request metrics
* [ ] Error metrics
* [ ] Kafka metrics

### ✅ Deadline — 08/11

Đủ tối thiểu:

```text
8 Functional Tests
2 Concurrency Tests
2 Fault/Recovery Tests
2 Security Tests
1 Performance Experiment
```

---

# 9. W7 — 09/11 → 15/11

# 🎯 Mục tiêu: Full Integration + Final Demo

## 09–11/11 — Full Integration

```text
API Gateway
      ↓
Identity
      ↓
Driver
      ↓
Trip
      ↓
Matching
      ↓
Pricing
      ↓
Payment
      ↓
Notification
```

* [ ] End-to-end test
* [ ] Fix integration bugs
* [ ] Verify Kafka events
* [ ] Verify database isolation
* [ ] Verify logs
* [ ] Verify monitoring

---

## 12/11 — Fault Injection

### Scenario 1

```text
Stop Matching
     ↓
Trip Request
     ↓
Message tồn tại
     ↓
Restart Matching
     ↓
Process
```

### Scenario 2

```text
Stop Payment
     ↓
TripCompleted (Kafka giữ message)
     ↓
Retry
     ↓
Recovery
```

---

## 13/11 — Docker Deployment

Fresh installation:

```bash
docker compose up -d
```

Test:

* [ ] PostgreSQL
* [ ] Kafka
* [ ] All services
* [ ] Gateway
* [ ] Prometheus
* [ ] Grafana

---

## 14/11 — Demo Preparation

### Scenario 1 — Normal Flow

```text
Passenger
 → Request
 → Matching
 → Driver
 → Complete
 → Payment
```

### Scenario 2 — Concurrency

```text
Passenger A ─┐
             ├── Driver D01
Passenger B ─┘
```

Expected:

```text
A → SUCCESS
B → REJECT
```

### Scenario 3 — Failure

```text
Payment DOWN
      ↓
Retry
      ↓
DLQ / Compensation
      ↓
Restart
      ↓
Continue
```

---

## 15/11 — Full Rehearsal

* [ ] Demo từ đầu đến cuối
* [ ] Mỗi thành viên trình bày phần của mình
* [ ] Test trên máy không có source build cache
* [ ] Kiểm tra thời gian demo
* [ ] Chuẩn bị backup plan

---

# 10. FINAL — 16/11 → 20/11

# 🎯 Mục tiêu: Code Freeze + Documentation + Release

## 16–17/11 — Code Freeze

Chỉ sửa:

* [ ] Bug
* [ ] Test
* [ ] Security
* [ ] Documentation
* [ ] Deployment
* [ ] CI/CD

> Không thêm feature mới.

---

# 11. Documentation

## Member 1

* [ ] Security documentation
* [ ] Identity documentation
* [ ] Driver documentation
* [ ] API documentation

## Member 2

* [ ] Architecture
* [ ] Matching algorithm
* [ ] Concurrency
* [ ] Consistency
* [ ] Saga

## Member 3

* [ ] Kafka
* [ ] Retry
* [ ] DLQ
* [ ] Docker
* [ ] Monitoring
* [ ] Performance

---

# 12. 19/11 — Final Validation

Test từ môi trường sạch:

```text
Clean Environment
       ↓
Clone Repository
       ↓
Configure .env
       ↓
docker compose up
       ↓
Start System
       ↓
Run Tests
       ↓
Run Demo
```

Kiểm tra:

* [ ] README
* [ ] `.env.example`
* [ ] Docker Compose
* [ ] Scripts
* [ ] Tests
* [ ] Documentation
* [ ] Report
* [ ] Git history
* [ ] CI/CD
* [ ] No secrets in repository

---

# 13. 19/11 — Create Final Release

```bash
git checkout main
git pull origin main

git tag v1.0.0
git push origin v1.0.0
```

GitHub:

```text
Release
  ↓
v1.0.0
  ↓
Final Submission
```

---

# 14. 20/11 — INTERNAL DEADLINE

## 🎯 DS03 v1.0.0 FINAL CHECKLIST

### Architecture

* [ ] 7 Business Services
* [ ] Independent services
* [ ] Database per service
* [ ] REST communication
* [ ] Kafka communication

### Distributed Systems

* [ ] Event ID
* [ ] Correlation ID
* [ ] Idempotency
* [ ] Deduplication
* [ ] Retry
* [ ] DLQ
* [ ] Saga
* [ ] Compensation
* [ ] Concurrency Control
* [ ] Fault Recovery
* [ ] Eventual Consistency

### Security

* [ ] JWT
* [ ] RBAC
* [ ] Password hashing
* [ ] Validation
* [ ] No secrets in Git

### Observability

* [ ] Logging
* [ ] Actuator
* [ ] Prometheus
* [ ] Grafana

### Testing

* [ ] 8 Functional Tests
* [ ] 2 Concurrency Tests
* [ ] 2 Fault/Recovery Tests
* [ ] 2 Security Tests
* [ ] 1 Performance Experiment

### Deployment

* [ ] Docker Compose
* [ ] `.env.example`
* [ ] Startup script
* [ ] Stop script
* [ ] Fault injection script
* [ ] Load test script

### Documentation

* [ ] README.md
* [ ] `docs/SRS.md`
* [ ] `docs/architecture.md`
* [ ] `docs/api.md`
* [ ] `docs/events.md`
* [ ] Report
* [ ] Slides
* [ ] Task assignment

### GitHub

* [ ] All members have commits
* [ ] `main` stable
* [ ] `develop` integrated
* [ ] Final PR merged
* [ ] Tag `v1.0.0`
* [ ] GitHub Release `v1.0.0`

---

# 15. Five Checkpoints quan trọng

| Checkpoint |  Deadline | Điều kiện đạt                            |
| ---------- | --------: | ---------------------------------------- |
| 🔴 **CP1** | **04/10** | Foundation + contracts + infrastructure  |
| 🟠 **CP2** | **18/10** | Full Happy Path                          |
| 🟡 **CP3** | **01/11** | Kafka + Saga + Retry + DLQ + Concurrency |
| 🟢 **CP4** | **15/11** | Full Demo + Fault Recovery + Performance |
| 🔵 **CP5** | **20/11** | Code Freeze + Documentation + `v1.0.0`   |

---

# 16. Nguyên tắc làm việc của nhóm

### 1. Contract-first

Trước khi service khác gọi vào:

```text
API Contract
Event Contract
Database Contract
```

phải được thống nhất.

### 2. `develop` luôn phải build được

```text
feature/*
    ↓
Pull Request
    ↓
develop
    ↓
Integration Test
    ↓
main
```

### 3. Không commit secret

Không commit:

```text
.env
password
JWT secret
database password
API key
```

Chỉ commit:

```text
.env.example
```

### 4. Mỗi task phải nhỏ

Ví dụ:

```text
feature/m2/matching-service
feature/m2/concurrency-control
feature/m3/kafka-consumer
feature/m3/payment-service
```

Không nên có branch kiểu:

```text
feature/m2/do-everything
```

### 5. Ưu tiên integration hơn feature

Thứ tự ưu tiên:

```text
1. Happy Path
2. Integration
3. Concurrency
4. Kafka
5. Saga
6. Retry/DLQ
7. Fault Recovery
8. Testing
9. Performance
10. Documentation
11. Advanced Features
```

---

# 17. Trạng thái mục tiêu ngày 20/11

```text
                    DS03 v1.0.0
                         │
        ┌────────────────┼────────────────┐
        │                │                │
    Business         Distributed       Quality
        │                │                │
   7 Services       Kafka/MQ          Testing
   REST             Saga              Security
   JWT              Retry             Monitoring
   Matching         DLQ               Performance
   Payment          Dedup             Docker
   Notification     Concurrency       Documentation
                         │
                         ↓
                  GitHub Release
                     v1.0.0
```

**Mục tiêu quan trọng nhất:** đến **18/10 phải chạy được toàn bộ Happy Path**, đến **01/11 phải hoàn thành các cơ chế Distributed Systems**, và từ **02/11 trở đi tập trung chủ yếu vào test, fault recovery, performance, demo và hoàn thiện sản phẩm**, thay vì tiếp tục mở rộng scope.
