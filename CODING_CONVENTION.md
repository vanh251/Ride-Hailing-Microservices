# CODING CONVENTION

## Ride-Hailing Microservices System – DS03

> **Version:** 1.2 (Aligned with Architecture, Database Schema, SRS, Events Contract & OpenAPI/Swagger UI Standards)
> **Project:** Ride-Hailing Microservices System – Simulating Grab/Uber
> **Team:** DS03
> **Language:** Java 17
> **Framework:** Spring Boot 3.x
> **Build Architecture:** Independent Gradle Projects per service (Gradle 8.x / 9.x Wrapper)
> **Database:** PostgreSQL 16 (Database-per-service, 5 DB instances)
> **Message Broker:** Apache Kafka 3.8.0 (KRaft mode)
> **Containerization:** Docker & Docker Compose
> **API Documentation:** Springdoc OpenAPI 3.x & Swagger UI (Per-service isolated)
> **Monitoring & Observability:** Spring Boot Actuator, Micrometer, Prometheus, Grafana

---

# 1. Mục đích

File này là **quy chuẩn code bắt buộc** cho toàn bộ thành viên dự án.

Mục tiêu:

* Code giữa các member có cùng style.
* AI Agent / Copilot / Claude / Cursor / các công cụ vibe coding tạo code đúng kiến trúc.
* Không tự ý thay đổi API Contract.
* Không tự ý tạo Kafka Event/Topic mới.
* Không tự ý thay đổi Database Contract.
* Hạn chế conflict khi merge code.
* Dễ review, test, debug và maintain.
* Đảm bảo các microservice hoạt động đúng theo kiến trúc đã thống nhất.

> **Rule quan trọng:**
> Khi code bằng AI, AI phải tuân thủ file `CODING_CONVENTION.md` này trước khi thực hiện task.

---

# 2. Kiến trúc hệ thống

Hệ thống mô phỏng nền tảng đặt xe công nghệ (Grab/Uber) gồm API Gateway và 7 business microservices:

```text
                    ┌─────────────────────────┐
                    │ Client / Web / App      │
                    └────────────┬────────────┘
                                 │ HTTP/REST (:8080)
                    ┌────────────▼────────────┐
                    │       API Gateway       │ (Spring Cloud Gateway WebMVC)
                    └────────────┬────────────┘
                                 │ (Docker DNS / HTTP)
         ┌───────────────────────┼───────────────────────┐
         │                       │                       │
         ▼ :8081                 ▼ :8082                 ▼ :8083
  Identity Service        Driver Service          Trip Service
         │                       │                       │
         ▼                       ▼                       ▼
  [ identity_db ]         [  driver_db  ]         [   trip_db   ]
  (PostgreSQL:5432)       (PostgreSQL:5432)       (PostgreSQL:5432)

         ┌───────────────────────┬───────────────────────┐
         │                       │                       │
         ▼ :8084                 ▼ :8085                 ▼ :8086
  Matching Service        Pricing Service         Payment Service
         │                  (Stateless -                 │
         ▼                    không DB)                  ▼
  [ matching_db ]                                 [  payment_db  ]
  (PostgreSQL:5432)                               (PostgreSQL:5432)
         │                                               │
         └───────────────────────┬───────────────────────┘
                                 │ (Events via Kafka:9092)
                                 ▼
                    ┌─────────────────────────┐
                    │   Apache Kafka (KRaft)  │ (:9092)
                    └────────────┬────────────┘
                                 │
                                 ▼
                     Notification Service (:8087)
                     (Stateless - không DB, log/SSE)
```

## 2.1. Service Ports & Persistence Mapping

| Service Name | Port (Docker internal) | Port (Host publish - Swagger UI & Debug) | Database / Persistence | Message Broker Role |
|---|---|---|---|---|
| **api-gateway** | `8080` | `8080` | Không có | Không |
| **identity-service** | `8081` | `8081` | PostgreSQL (`identity_db`) | Không |
| **driver-service** | `8082` | `8082` | PostgreSQL (`driver_db`) | Không |
| **trip-service** | `8083` | `8083` | PostgreSQL (`trip_db`) | Kafka (Producer + Consumer) |
| **matching-service** | `8084` | `8084` | PostgreSQL (`matching_db`) | Kafka (Producer + Consumer) |
| **pricing-service** | `8085` | `8085` | **Không có (Stateless)** | Không |
| **payment-service** | `8086` | `8086` | PostgreSQL (`payment_db`) | Kafka (Producer + Consumer) |
| **notification-service**| `8087` | `8087` | **Không có (Stateless)** | Kafka (Consumer only) |
| **Kafka (KRaft)** | `9092` | Internal only | Volume (`kafka_data`) | Self |
| **Prometheus** | `9090` | `9090` | Volume (`prometheus_data`) | Scrape Actuator metrics |
| **Grafana** | `3000` | `3000` | Volume (`grafana_data`) | Visualization dashboard |

> **Lưu ý Host Ports:**  
> Các microservice publish ports `8081`–`8087` lên máy host để developer có thể truy cập trực tiếp **Swagger UI** (`http://localhost:<port>/swagger-ui/index.html`) và debug độc lập từng service.  
> Tuy nhiên, khi các container giao tiếp với nhau trong Docker network, **bắt buộc** dùng Docker DNS (ví dụ: `http://driver-service:8082`), không được dùng `localhost`.


---

# 3. Nguyên tắc kiến trúc bắt buộc

## 3.1. Database-per-Service

Chỉ các service có dữ liệu nghiệp vụ lâu dài mới sở hữu PostgreSQL Database riêng:

```text
identity-service     → identity_db  (users, refresh_tokens)
driver-service       → driver_db    (drivers, vehicles, driver_locations, driver_status, processed_events)
trip-service         → trip_db      (trips, outbox_events, processed_events, saga_log)
matching-service     → matching_db  (match_requests, driver_offers, processed_events)
payment-service      → payment_db   (payments, ledger, outbox_events, processed_events)

pricing-service      → KHÔNG CÓ DATABASE (Stateless: tính cước dựa trên formula/rules)
notification-service → KHÔNG CÓ DATABASE (Stateless: lắng nghe Kafka events và log/thông báo)
```

### Tuyệt đối cấm (Cross-database query / Foreign Key)

```text
trip-service
    ↓ (Direct SQL query / Foreign Key)
driver_db (hoặc bất kỳ database của service khác)
```

* **Zero physical Foreign Keys across database boundaries:** Tuyệt đối không tạo Foreign Key vật lý giữa các database khác nhau.
* Mọi liên kết chéo giữa các service bắt buộc sử dụng **Soft UUID Reference** (ví dụ: `driverId: UUID` lưu trong bảng `trips`).
* Lấy dữ liệu hoặc tương tác liên service bắt buộc thông qua **REST API** nội bộ hoặc **Kafka Events**.

### Cách làm đúng

```text
trip-service
    ↓ REST API (Docker DNS: http://driver-service:8082) hoặc Kafka Event
driver-service
    ↓
driver_db
```

---

## 3.2. Không dùng Eureka

Project **không sử dụng Eureka / Service Discovery Server**.

Các service giao tiếp trong Docker Network thông qua Docker DNS.

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

### Tuyệt đối không dùng

```text
http://localhost:8082
```

để một container gọi sang container khác.

`localhost` bên trong container chỉ trỏ về chính container đó.

---

# 4. Cấu trúc project (Independent Gradle Projects)

> **NGUYÊN TẮC CỐT LÕI VỀ BUILD:**  
> Thư mục gốc (`ride-hailing-microservices/`) **KHÔNG PHẢI** là một Gradle project!  
> Tuyệt đối không có root `build.gradle`, không có root `settings.gradle`, và không có root `gradlew`.  
> Mỗi microservice trong `src/<service-name>` là một **dự án Gradle hoàn toàn độc lập**, có build file, wrapper và Dockerfile riêng biệt.

### Cấu trúc Repository tổng thể:

```text
ride-hailing-microservices/          <-- ROOT FOLDER (Non-Gradle)
├── docker-compose.yml               <-- Orchestrate containers, 5 DBs, Kafka, Prometheus, Grafana
├── .env.example                     <-- Template biến môi trường
├── README.md                        <-- Hướng dẫn tổng quan & cách chạy
├── CODING_CONVENTION.md             <-- Quy chuẩn lập trình bắt buộc
├── docs/                            <-- Hệ thống hợp đồng & tài liệu kiến trúc (SRS, API, Event, Schema)
├── deploy/                          <-- Prometheus & Grafana configurations
└── src/
    ├── api-gateway/                 <-- Independent Gradle Project (:8080)
    │   ├── build.gradle
    │   ├── gradlew / gradlew.bat
    │   ├── Dockerfile
    │   └── src/main/java/com/example/apigateway/...
    ├── identity-service/            <-- Independent Gradle Project (:8081)
    │   ├── build.gradle
    │   ├── gradlew / gradlew.bat
    │   ├── Dockerfile
    │   └── src/main/java/com/example/identityservice/...
    ├── driver-service/              <-- Independent Gradle Project (:8082)
    ├── trip-service/                <-- Independent Gradle Project (:8083)
    ├── matching-service/            <-- Independent Gradle Project (:8084)
    ├── pricing-service/             <-- Independent Gradle Project (:8085)
    ├── payment-service/             <-- Independent Gradle Project (:8086)
    └── notification-service/        <-- Independent Gradle Project (:8087)
```

### Quy tắc chạy lệnh Build / Test:

Mọi lệnh Gradle phải được thực thi bên trong thư mục của service tương ứng:

```bash
# ĐÚNG:
cd src/trip-service && ./gradlew clean test && cd ../..

# SAI (sẽ báo lỗi command not found hoặc không có task):
./gradlew test
```

### Cấu trúc nội bộ từng Microservice:

```text
src/<service-name>/
├── build.gradle
├── settings.gradle
├── Dockerfile
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── example/
│   │   │           └── <servicename>/           # e.g., tripservice
│   │   │               ├── controller/          # REST Controllers
│   │   │               ├── service/             # Business Logic Layer (Interfaces & Impls)
│   │   │               ├── repository/          # Spring Data JPA (chỉ ở 5 service có DB)
│   │   │               ├── entity/              # JPA Entities (chỉ ở 5 service có DB)
│   │   │               ├── dto/                 # Request & Response DTOs
│   │   │               │   ├── request/
│   │   │               │   └── response/
│   │   │               ├── mapper/              # Entity/DTO mappers
│   │   │               ├── exception/           # Custom Exceptions & GlobalExceptionHandler
│   │   │               ├── config/              # Web, Security, Kafka, Actuator configs
│   │   │               ├── client/              # REST Clients gọi service khác
│   │   │               └── kafka/               # Kafka components (nếu service có dùng)
│   │   │                   ├── producer/
│   │   │                   ├── consumer/
│   │   │                   └── event/           # Event Envelope & Payload classes
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/                    # Flyway scripts (nếu có)
│   │
│   └── test/
│       └── java/
│           └── com/example/<servicename>/
```

Không tự ý tạo package mới nằm ngoài quy chuẩn nếu package hiện tại đã đáp ứng được yêu cầu.

---

# 5. Naming Convention

## 5.1. Class

Sử dụng `PascalCase`.

```java
TripController
TripService
TripRepository
TripRequest
TripResponse
TripCompletedEvent
```

---

## 5.2. Method

Sử dụng `camelCase`.

```java
createTrip()
findTripById()
assignDriver()
calculateFare()
processPayment()
```

---

## 5.3. Variable

```java
tripId
driverId
passengerId
pickupLocation
destination
```

---

## 5.4. Constant

Sử dụng `UPPER_SNAKE_CASE`.

```java
MAX_RETRY_COUNT
DEFAULT_PAGE_SIZE
TRIP_EVENTS_TOPIC
PAYMENT_EVENTS_TOPIC
```

---

## 5.5. Package

Sử dụng chữ thường (`lowercase`), không viết hoa, không gạch dưới.

* **Base package của dự án hiện tại:** `com.example.<servicename>` (ví dụ: `com.example.identityservice`, `com.example.tripservice`, `com.example.driverservice`, v.v.).
* **Sub-packages tiêu chuẩn:**

```text
controller
service
repository
entity
dto.request
dto.response
exception
config
client
kafka.producer
kafka.consumer
kafka.event
```

Không dùng:

```text
Controllers
Services
DTOs
```

---

# 6. DTO Convention

Không trả trực tiếp JPA Entity ra API.

### Không nên

```java
@GetMapping("/{id}")
public Trip getTrip(@PathVariable UUID id) {
    return tripService.findById(id);
}
```

### Nên

```java
@GetMapping("/{id}")
public TripResponse getTrip(@PathVariable UUID id) {
    return tripService.findById(id);
}
```

---

## 6.1. Request DTO

Tên:

```text
CreateTripRequest
UpdateTripRequest
LoginRequest
RegisterRequest
AcceptTripRequest
```

---

## 6.2. Response DTO

Tên:

```text
TripResponse
DriverResponse
PaymentResponse
UserResponse
```

---

## 6.3. DTO không chứa business logic

DTO chỉ dùng để:

* Nhận request.
* Trả response.
* Validate dữ liệu.
* Mapping dữ liệu.

Không đặt logic nghiệp vụ phức tạp trong DTO.

---

## 6.4. OpenAPI Schema Annotation Convention (@Schema)

Tất cả Request DTO và Response DTO **bắt buộc** phải được annotate đầy đủ bằng Swagger/OpenAPI annotations:

1. **Ở cấp Class:** Phải có `@Schema(description = "...")` mô tả mục đích của DTO.
2. **Ở cấp Field:** Mỗi field cần có `@Schema` với:
   * `description`: Ý nghĩa rõ ràng của trường dữ liệu.
   * `example`: Dữ liệu mẫu thực tế, đúng định dạng và ngữ cảnh nghiệp vụ.
3. **Ràng buộc Validation:** Các validation annotation (`@NotNull`, `@NotBlank`, `@Email`, `@Size`, `@Min`, `@Max`, `@Positive`, v.v.) phải đi kèm để Swagger UI tự động nhận diện field `required` và `constraints`.
4. **Quy tắc bảo mật dữ liệu mẫu:**
   * UUID: Dùng format chuẩn RFC-4122 (ví dụ: `"550e8400-e29b-41d4-a716-446655440000"`).
   * Timestamp: Dùng format ISO-8601 UTC (ví dụ: `"2026-10-03T10:00:00Z"`).
   * **Tuyệt đối không** đưa password thật, JWT secret, private key hoặc dữ liệu nhạy cảm vào ví dụ mẫu (`example`).
   * Không bao giờ tạo field `password` hay `passwordHash` trong **Response DTO**.

### Ví dụ chuẩn cho Request DTO:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to create a new ride trip")
public class CreateTripRequest {

    @Schema(description = "ID of the passenger requesting the ride", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Passenger ID is required")
    private UUID passengerId;

    @Schema(description = "Pickup location details")
    @NotNull(message = "Pickup location is required")
    @Valid
    private LocationDto pickupLocation;

    @Schema(description = "Dropoff destination details")
    @NotNull(message = "Dropoff location is required")
    @Valid
    private LocationDto dropoffLocation;
}
```

### Ví dụ chuẩn cho Response DTO:

```java
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response containing trip lifecycle information")
public class TripResponse {

    @Schema(description = "Unique trip identifier", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID id;

    @Schema(description = "Current trip status", example = "SEARCHING")
    private TripStatus status;

    @Schema(description = "Assigned driver ID (null if still searching)", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID driverId;

    @Schema(description = "Calculated trip fare in VND", example = "45000.0")
    private BigDecimal fare;

    @Schema(description = "Creation timestamp", example = "2026-10-03T10:00:00Z")
    private Instant createdAt;
}
```

---

# 7. Controller Convention

Controller chỉ chịu trách nhiệm:

```text
HTTP Request
     ↓
Validation
     ↓
Service
     ↓
HTTP Response
```

Không đặt business logic trong Controller.

### Không nên

```java
@PostMapping
public TripResponse createTrip(...) {

    // validate driver
    // calculate price
    // reserve driver
    // save trip
    // publish kafka
}
```

### Nên

```java
@PostMapping
public TripResponse createTrip(
        @Valid @RequestBody CreateTripRequest request) {

    return tripService.createTrip(request);
}
```

---

## 7.1. OpenAPI / Swagger Annotations Convention

Tất cả REST Controller **bắt buộc** phải khai báo đầy đủ các OpenAPI 3.x annotations để Swagger UI hiển thị thông tin chính xác, cho phép test trực tiếp và tài liệu hóa chuẩn chỉ:

### 1. Ở cấp Controller Class:
* `@Tag(name = "...", description = "...")`: Gom nhóm endpoint theo domain nghiệp vụ. Tên tag phải rõ ràng (ví dụ: `Trips`, `Matching`, `Pricing`, `Payments`, `Notifications`, `Authentication`, `Drivers`), tuyệt đối không dùng tên vô nghĩa như `Controller`, `Demo`, `Test`.
* `@SecurityRequirement(name = "bearerAuth")`: Đặt ở class level nếu toàn bộ endpoint trong Controller yêu cầu JWT Bearer Token, hoặc đặt ở từng method được bảo vệ.
* **Public APIs** (ví dụ: `/api/auth/register/passenger`, `/api/auth/register/driver`, `/api/auth/login`): **Không** gắn `@SecurityRequirement`.

### 2. Ở cấp Method / Endpoint:
* `@Operation(summary = "...", description = "...")`:
  * `summary`: Tóm tắt ngắn gọn hành động (ví dụ: `"Create a trip"`).
  * `description`: Mô tả chi tiết hành vi, luồng xử lý hoặc điều kiện tiên quyết.
* `@ApiResponses({ ... })`: Phải liệt kê đầy đủ các status code mà endpoint có thể trả về:
  * `200` / `201` / `202`: Kèm `content = @Content(schema = @Schema(implementation = <ResponseDTO>.class))`
  * `400`: Invalid request payload hoặc validation failed (kèm `ErrorResponse.class`)
  * `401`: Unauthorized nếu thiếu hoặc sai JWT token
  * `403`: Forbidden nếu không đủ quyền
  * `404`: Resource not found (kèm `ErrorResponse.class`)
  * `409`: Conflict / Optimistic lock mismatch / Trạng thái không hợp lệ (kèm `ErrorResponse.class`)
  * Không document các status code mà API không bao giờ trả về.

### 3. Ở cấp Parameter:
* `@Parameter(description = "...", example = "...")`: Bắt buộc cho các tham số `@PathVariable`, `@RequestParam`, `@RequestHeader` (ví dụ: `X-Correlation-ID`, `X-Idempotency-Key`).

### Ví dụ chuẩn Controller hoàn chỉnh:

```java
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
@Tag(name = "Trips", description = "Trip lifecycle management APIs")
@SecurityRequirement(name = "bearerAuth")
public class TripController {

    private final TripService tripService;

    @Operation(summary = "Create a trip", description = "Creates a new ride request and initializes trip searching")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Trip created successfully",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Missing or invalid JWT"),
            @ApiResponse(responseCode = "409", description = "Passenger already has an active trip",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<TripResponse> createTrip(
            @Valid @RequestBody CreateTripRequest request) {

        TripResponse response = tripService.createTrip(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get trip by ID", description = "Retrieves trip details by its unique identifier")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trip found",
                    content = @Content(schema = @Schema(implementation = TripResponse.class))),
            @ApiResponse(responseCode = "404", description = "Trip not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<TripResponse> getTrip(
            @Parameter(description = "Unique trip ID", example = "550e8400-e29b-41d4-a716-446655440001")
            @PathVariable UUID id) {

        TripResponse response = tripService.getTrip(id);
        return ResponseEntity.ok(response);
    }
}
```

---

# 8. Service Convention

Business logic phải nằm trong Service.

```java
@Service
@RequiredArgsConstructor
public class TripService {

    private final TripRepository tripRepository;

    public TripResponse createTrip(CreateTripRequest request) {

        // business logic

        return ...;
    }
}
```

Transaction nên được đặt tại Service Layer.

```java
@Transactional
public TripResponse createTrip(...) {
    ...
}
```

---

# 9. Repository Convention

Repository chỉ chịu trách nhiệm truy cập database.

Ví dụ:

```java
@Repository
public interface TripRepository
        extends JpaRepository<Trip, UUID> {

    Optional<Trip> findById(UUID id);
}
```

Không đặt business logic trong Repository.

### Không nên

```java
tripRepository.calculateFare();
tripRepository.assignDriver();
tripRepository.startTrip();
```

Business logic phải nằm ở Service.

---

# 10. Entity Convention

Entity đại diện cho dữ liệu thuộc service.

Ví dụ:

```java
@Entity
@Table(name = "trips")
public class Trip {

    @Id
    private UUID id;

    private UUID passengerId;

    private UUID driverId;

    private TripStatus status;

    ...
}
```

Không dùng Entity của service khác.

### Tuyệt đối không được

```java
import com.example.driverservice.entity.Driver;
```

từ `trip-service` (không được import Entity hoặc chia sẻ class Entity giữa các microservice).

Thay vào đó, luôn sử dụng **Soft UUID Reference**:

```java
private UUID driverId;
```

---

# 11. ID Convention

ID của entity phân tán nên sử dụng UUID khi phù hợp.

Ví dụ:

```java
UUID tripId;
UUID driverId;
UUID passengerId;
UUID paymentId;
```

Không tự ý thay đổi kiểu ID của một resource sau khi API Contract đã được thống nhất.

---

# 12. Enum Convention

Các trạng thái nghiệp vụ bắt buộc phải sử dụng Enum, tuyệt đối không dùng Magic String.

### 12.1. Trạng thái chuyến đi (TripStatus)

Đối chiếu chuẩn xác từ [SRS.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/SRS.md) & [database_schema.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/database_schema.md):

```java
public enum TripStatus {
    REQUESTED,       // Khách vừa tạo yêu cầu
    PRICED,          // Đã ước tính cước phí (tùy chọn)
    SEARCHING,       // Đang tìm tài xế qua Matching Service
    ASSIGNED,        // Đã khóa và gán tài xế thành công (sau CAS reservation)
    IN_PROGRESS,     // Tài xế đã đón khách và chuyến đi đang diễn ra
    COMPLETED,       // Chuyến đi hoàn thành tại điểm đến (trigger payment)
    PAID,            // Thanh toán thành công (Saga hoàn tất)
    PAYMENT_FAILED,  // Thanh toán thất bại (Saga compensation ghi nhận nợ)
    CANCELLED        // Khách hủy hoặc matching timeout
}
```

### 12.2. Trạng thái tài xế (DriverStatus)

```java
public enum DriverStatus {
    OFFLINE,         // Không trực tuyến
    AVAILABLE,       // Sẵn sàng nhận cuốc
    RESERVED,        // Đang bị giữ tạm thời bởi Matching Service (CAS lock)
    BUSY             // Đang thực hiện chuyến đi (không dùng tên ON_TRIP)
}
```

> Bảng chuyển trạng thái đầy đủ (ai/cái gì kích hoạt): [docs/api.md](docs/api.md) → `PATCH /api/drivers/{id}/status`. Tài xế chỉ tự chuyển `OFFLINE ⇄ AVAILABLE`; các chuyển còn lại do hệ thống (CAS reserve, lease, event `DriverAssigned` / `TripCompleted` / `TripCancelled`).

### 12.3. Quyền hạn người dùng (UserRole)

```java
public enum UserRole {
    PASSENGER,
    DRIVER,
    ADMIN
}
```

### 12.4. Trạng thái thanh toán (PaymentStatus)

```java
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED
}
```

Không sử dụng magic string:

```java
if (trip.getStatus().equals("completed")) {
}
```

Nên:

```java
if (trip.getStatus() == TripStatus.COMPLETED) {
}
```

---

# 13. REST API Convention

API sử dụng RESTful design và thống nhất toàn bộ qua API Gateway.

## 13.1. API Gateway Routing Table

Tất cả request từ bên ngoài (Clients/Postman) bắt buộc phải đi qua **API Gateway** (`http://localhost:8080`).

| Gateway Route Prefix | Target Microservice | Target Internal URL (Docker DNS) |
|---|---|---|
| `/api/auth/**` | `identity-service` | `http://identity-service:8081/api/auth/**` |
| `/api/users/**` | `identity-service` | `http://identity-service:8081/api/users/**` |
| `/api/drivers/*`, `/api/drivers/*/status`, `/api/drivers/*/location` | `driver-service` | `http://driver-service:8082/api/drivers/...` |
| `/api/trips/**` | `trip-service` | `http://trip-service:8083/api/trips/**` |
| `/api/matching/*/match` | `matching-service` | `http://matching-service:8084/api/matching/{requestId}/match` |
| `/api/pricing/quote` | `pricing-service` | `http://pricing-service:8085/api/pricing/quote` |
| `/api/payments/*` | `payment-service` | `http://payment-service:8086/api/payments/{tripId}` |
| `/api/notifications/**` | `notification-service` | `http://notification-service:8087/api/notifications/**` |
| `/actuator/health` | `api-gateway` | (Local healthcheck) |

> **Quy tắc giao tiếp:**  
> - External Client → API Gateway (`localhost:8080`).  
> - Service → Service nội bộ: Gọi trực tiếp qua Docker DNS (ví dụ: `http://driver-service:8082`), **không** gọi qua Gateway và **không** gọi qua localhost.  
> - Gateway **không** route `/internal/**`, `/api/drivers/{id}/reserve`, `/api/matching/{requestId}/rematch`, `POST /api/pricing/final`, `POST /api/payments/{tripId}/charge` và `/refund` (trả `404`); các endpoint này chỉ dành cho service gọi nội bộ.
> - `POST /api/matching/{requestId}/match` qua Gateway chỉ nhận token `ADMIN`.

---

## 13.2. URL Naming

Sử dụng danh từ số nhiều (`plural noun`), chữ thường (`kebab-case` nếu có từ ghép).

```text
/api/trips
/api/drivers
/api/payments
/api/users
/api/drivers/available
```

Không dùng động từ trong URI:

```text
/api/createTrip     <-- SAI
/api/getDrivers     <-- SAI
/api/doPayment      <-- SAI
```

---

## 13.3. HTTP Methods

| Method | Mục đích | Idempotent? |
|---|---|---|
| `GET` | Truy vấn dữ liệu | Có |
| `POST` | Tạo mới resource / Thực thi command nghiệp vụ | Không (cần Idempotency-Key nếu là thanh toán) |
| `PUT` | Cập nhật toàn bộ resource | Có |
| `PATCH` | Cập nhật một phần resource | Có |
| `DELETE` | Xóa resource | Có |

---

## 13.4. HTTP Status Codes

Sử dụng status code đúng ngữ nghĩa:

* `200 OK`: Thành công (GET, PATCH, PUT, DELETE trả về dữ liệu).
* `201 CREATED`: Tạo resource mới thành công (POST).
* `204 NO_CONTENT`: Xóa hoặc xử lý thành công nhưng không có body trả về.
* `400 BAD_REQUEST`: Lỗi validation dữ liệu đầu vào hoặc sai enum.
* `401 UNAUTHORIZED`: Thiếu hoặc sai JWT token.
* `403 FORBIDDEN`: Token hợp lệ nhưng không đủ quyền (Role không khớp).
* `404 NOT_FOUND`: Không tìm thấy resource tương ứng với ID.
* `409 CONFLICT`: Xung đột trạng thái / Race condition.
  Ví dụ: Tài xế đã bị cuốc khác giữ trước (CAS version mismatch); Email đã tồn tại.
* `422 UNPROCESSABLE_ENTITY`: Vi phạm ràng buộc logic nghiệp vụ (ví dụ: chuyển sai trạng thái state machine).
* `500 INTERNAL_SERVER_ERROR`: Lỗi server chưa được bắt ngoại lệ.

---

## 13.5. Response Wrapper Rule

* **Phản hồi thành công (2xx):** Trả về trực tiếp Resource Payload (hoặc Collection), **không bọc** trong envelope `{ "data": ... }`.
* **Phản hồi lỗi (4xx, 5xx):** Bắt buộc tuân theo format chuẩn tại [Mục 16](#16-error-response).

---

## 13.6. Swagger UI & OpenAPI 3.x Standards

Hệ thống tích hợp **Springdoc OpenAPI 3.x** trên toàn bộ các microservices để phục vụ tài liệu hóa, integration testing và gỡ lỗi API trực tiếp trên giao diện Swagger UI.

### 13.6.1. Nguyên tắc kiến trúc Swagger UI
* **Mỗi service sở hữu Swagger UI độc lập:** Mỗi microservice tự build và expose Swagger UI trên cổng dịch vụ của mình. Tuyệt đối không biến API Gateway thành nơi tập trung duy nhất nếu làm thay đổi kiến trúc routing.
* **Danh sách Endpoint tài liệu & cổng truy cập Host:**
  * **identity-service:** `http://localhost:8081/swagger-ui/index.html` (JSON: `http://localhost:8081/v3/api-docs`)
  * **driver-service:** `http://localhost:8082/swagger-ui/index.html` (JSON: `http://localhost:8082/v3/api-docs`)
  * **trip-service:** `http://localhost:8083/swagger-ui/index.html` (JSON: `http://localhost:8083/v3/api-docs`)
  * **matching-service:** `http://localhost:8084/swagger-ui/index.html` (JSON: `http://localhost:8084/v3/api-docs`)
  * **pricing-service:** `http://localhost:8085/swagger-ui/index.html` (JSON: `http://localhost:8085/v3/api-docs`)
  * **payment-service:** `http://localhost:8086/swagger-ui/index.html` (JSON: `http://localhost:8086/v3/api-docs`)
  * **notification-service:** `http://localhost:8087/swagger-ui/index.html` (JSON: `http://localhost:8087/v3/api-docs`)
* **Không hardcode port:** Port phải được load từ cấu hình môi trường `${SERVER_PORT}` trong `application.yml`.

### 13.6.2. Dependency chuẩn (Gradle)
Tất cả các service sử dụng duy nhất dependency Springdoc chính thức tương thích Spring Boot 3.x / 4.x và Java 17:

```groovy
dependencies {
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5'
}
```

> **Tuyệt đối cấm:** Sử dụng thư viện cũ lỗi thời như `springfox-swagger2`, `springfox-swagger-ui` hoặc Swagger 2.x.

### 13.6.3. Metadata & Cấu hình OpenApiConfig.java
Mỗi service bắt buộc phải có file cấu hình đặt tại package `config/OpenApiConfig.java`:

```java
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DS03 Trip Service API") // Format: DS03 <Tên Service> API
                        .description("REST API for trip lifecycle management.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("DS03 Microservices Team")
                                .email("team@ds03.example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter JWT Bearer token to authorize requests.")));
    }
}
```

* **Tiêu chuẩn Title:** `DS03 <Service Name> API` (Ví dụ: `DS03 Identity Service API`, `DS03 Driver Service API`, `DS03 Trip Service API`, v.v.). Không đặt tên chung chung như `Test API`, `Demo API`, `Swagger API`.
* **Security Scheme:** Bắt buộc đăng ký `bearerAuth` (`HTTP Bearer JWT`). Swagger UI sẽ hiển thị nút **Authorize**, cho phép developer nhập JWT token để tự động gửi header `Authorization: Bearer <token>` khi test các API được bảo vệ.

### 13.6.4. Cấu hình application.yml
Mỗi service khai báo cấu hình đường dẫn chuẩn trong `application.yml`:

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

### 13.6.5. Tương thích Spring Security
Nếu service có Spring Security kích hoạt (`identity-service` hoặc các service protected khác):
* Endpoint Swagger **phải** được cấp quyền truy cập công khai (`permitAll()`):
  ```java
  .requestMatchers(
      "/actuator/**",
      "/api/auth/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/v3/api-docs/**"
  ).permitAll()
  ```
* **Tuyệt đối cấm:** Sử dụng `permitAll("/**")` làm tắt cơ chế bảo mật của toàn bộ service chỉ để Swagger UI hoạt động.

### 13.6.6. Quy tắc bất khả xâm phạm về API Contract
1. **Phản ánh thực tế 100%:** Swagger UI phải phản ánh chính xác API hiện có trong code và khớp tuyệt đối với `docs/api.md`.
2. **Nghiêm cấm tạo endpoint giả:** Tuyệt đối không tạo mock controller hay API giả chỉ để Swagger UI trông phong phú hơn.
3. **Không document API chưa tồn tại:** Nếu tính năng thuộc về tuần sau hoặc chưa được phân công/chưa code, không document trước trên Swagger.
4. **Không expose dữ liệu nhạy cảm:** Không đưa password thật, database credentials, JWT secret hay private keys vào schema và dữ liệu mẫu (`examples`).
5. **Đồng bộ mã lỗi Error Response:** Mọi mã lỗi 4xx/5xx trong `@ApiResponse` đều phải liên kết với Schema chuẩn `ErrorResponse.class` ([Mục 16](#16-error-response)).

---

# 14. Validation

Sử dụng Jakarta Validation.

```java
@NotNull
@NotBlank
@Email
@Size
@Min
@Max
@Positive
```

Ví dụ:

```java
public class CreateTripRequest {

    @NotNull
    private UUID passengerId;

    @NotBlank
    private String pickupLocation;

    @NotBlank
    private String destination;
}
```

Controller:

```java
@PostMapping
public TripResponse createTrip(
        @Valid @RequestBody CreateTripRequest request) {

    return tripService.createTrip(request);
}
```

---

# 15. Exception Handling

Mỗi service phải có Global Exception Handler.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
}
```

Các exception thường gặp:

```text
ResourceNotFoundException
BusinessException
ConflictException
UnauthorizedException
ForbiddenException
ValidationException
```

Không trả stack trace cho client.

### Không được

```json
{
  "error": "NullPointerException at TripService.java:123"
}
```

---

# 16. Error Response

Error response phải tuân theo format chuẩn toàn hệ thống định nghĩa trong [docs/api.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/api.md) Mục 8:

```json
{
  "timestamp": "2026-10-01T10:30:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Driver is already reserved by another ride request",
  "path": "/api/drivers/d001/reserve",
  "correlationId": "corr-f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "code": "DRIVER_ALREADY_RESERVED"
}
```

Không expose:

* Database password.
* JWT secret.
* Stack trace.
* Internal credentials.
* Sensitive information.

---

# 17. Kafka Convention

Kafka 3.8.0 (KRaft mode) là xương sống giao tiếp bất đồng bộ giữa các microservices.

## 17.1. Official Event Catalogue (8 Events chính thức)

Toàn bộ hệ thống chỉ sử dụng chính xác 8 domain events đã thống nhất trong [docs/events.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/events.md):

| Event Type (PascalCase) | Topic | Producer | Primary Consumers | Kích hoạt khi nào |
|---|---|---|---|---|
| `RideRequested` | `trip-events` | `trip-service` | `matching-service`, `notification-service` | Khách tạo cuốc xe (`POST /api/trips`) |
| `DriverReserved` | `matching-events` | `matching-service` | `trip-service`, `notification-service` | Matching CAS lock tài xế thành công |
| `DriverAssigned` | `trip-events` | `trip-service` | `driver-service`, `notification-service` | Trip nhận `DriverReserved` và chuyển sang `ASSIGNED` (driver: `RESERVED → BUSY`) |
| `TripStarted` | `trip-events` | `trip-service` | `notification-service` | Tài xế đón khách & bắt đầu chạy |
| `TripCompleted` | `trip-events` | `trip-service` | `payment-service`, `driver-service`, `notification-service` | Tài xế hoàn thành cuốc xe (kích hoạt thanh toán; driver: `BUSY → AVAILABLE`) |
| `PaymentSucceeded` | `payment-events` | `payment-service` | `trip-service`, `notification-service` | Thu tiền cước thành công (Saga hoàn tất) |
| `PaymentFailed` | `payment-events` | `payment-service` | `trip-service`, `notification-service` | Thu tiền thất bại (Saga compensation ghi nhận nợ) |
| `TripCancelled` | `trip-events` | `trip-service` | `driver-service`, `matching-service`, `notification-service` | Khách hủy chuyến hoặc matching timeout |

### Nghiêm cấm tự ý tạo Event mới:

```text
❌ DriverMatched
❌ PaymentRequested
❌ DriverRejected
❌ TripAccepted
```

Mọi nhu cầu thêm event mới phải được cả nhóm review và cập nhật trước vào [docs/events.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/events.md).

---

# 18. Kafka Topics & Partitioning

Danh mục topic chuẩn hóa theo [docs/events.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/events.md):

| Topic Name | Partitions | Producers | Consumers | Description |
|---|---|---|---|---|
| `trip-events` | 3 | `trip-service` | `matching-service`, `payment-service`, `notification-service` | Vòng đời chuyến đi |
| `matching-events` | 3 | `matching-service` | `trip-service`, `notification-service` | Kết quả gán tài xế |
| `payment-events` | 3 | `payment-service` | `trip-service`, `notification-service` | Kết quả thanh toán cước |
| `trip-events.DLQ` | 1 | Kafka (auto) | Monitoring / Manual | Dead Letter Queue của trip-events |
| `matching-events.DLQ` | 1 | Kafka (auto) | Monitoring / Manual | Dead Letter Queue của matching-events |
| `payment-events.DLQ` | 1 | Kafka (auto) | Monitoring / Manual | Dead Letter Queue của payment-events |

> **Quy tắc Partition Key bắt buộc:**  
> Mọi message publish lên topic phải truyền `aggregateId` (ví dụ: `tripId.toString()`) làm Partition Key.  
> Việc này đảm bảo mọi event của cùng một chuyến đi luôn rơi vào cùng một Kafka partition, đảm bảo tuyệt đối tính thứ tự (FIFO ordering).

---

# 19. Event Envelope

Kafka event phải tuân theo Event Envelope đã thống nhất.

Ví dụ:

```json
{
  "eventId": "uuid",
  "eventType": "RideRequested",
  "aggregateId": "uuid",
  "aggregateType": "TRIP",
  "producer": "trip-service",
  "correlationId": "uuid",
  "causationId": "uuid",
  "schemaVersion": "1.0",
  "occurredAt": "2026-10-01T10:30:00Z",
  "payload": {}
}
```

Các field quan trọng:

```text
eventId
eventType
aggregateId
aggregateType
producer
correlationId
causationId
schemaVersion
occurredAt
payload
```

Không tự ý xóa hoặc đổi ý nghĩa các field này.

---

# 20. Event ID

Mỗi event phải có `eventId` duy nhất.

Ví dụ:

```java
UUID.randomUUID()
```

Consumer sử dụng `eventId` để phát hiện duplicate event.

---

# 21. Idempotency và Deduplication

Trong hệ thống phân tán, Kafka đảm bảo ngữ nghĩa chuyển giao **At-Least-Once** (có thể gửi lặp message khi network bị chập chờn). Do đó, mọi Consumer và một số API thanh toán bắt buộc phải có cơ chế Idempotent.

### 21.1. Kafka Consumer Deduplication (`processed_events` Table)

Các service có database và tiêu thụ Kafka (`trip-service`, `matching-service`, `payment-service`, `driver-service`) bắt buộc phải sở hữu bảng deduplication:

```sql
CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 21.2. HTTP API Idempotency (`X-Idempotency-Key` Header)

Các API nhạy cảm không thể retry tùy tiện (đặc biệt là `POST /api/payments/charge`):
* Client bắt buộc truyền Header: `X-Idempotency-Key: <UUID>`.
* `payment-service` lưu key này vào bảng `payments(idempotency_key)`.
* Nếu gặp trùng key: Trả về kết quả giao dịch trước đó ngay lập tức (không thực hiện trừ tiền 2 lần).

---

## 21.1. Consumer flow

```text
Kafka Message
      ↓
Check eventId
      ↓
Already processed?
   ┌──┴──┐
  YES    NO
   │      │
 ACK     Business Logic
   │      │
 Return   ↓
        Save processed_event
             ↓
           ACK
```

---

# 22. Kafka Consumer Rules

Consumer phải:

1. Validate event.
2. Kiểm tra duplicate.
3. Xử lý business logic.
4. Ghi `processed_events`.
5. Commit transaction.
6. ACK message.

Không được xử lý event nhiều lần nếu event đã được xử lý thành công.

---

# 23. Retry và DLQ

Khi Kafka message xử lý thất bại:

```text
Message
   ↓
Consumer
   ↓
Retry
   ↓
Retry
   ↓
Retry
   ↓
DLQ
```

Retry phải có giới hạn.

Không được:

```text
while(true) {
    retry();
}
```

Sau số lần retry cho phép, message phải được đưa vào DLQ.

---

# 24. Kafka Producer & Transactional Outbox Pattern

Producer phải đảm bảo:

* Tạo `eventId` duy nhất (UUID).
* Set `correlationId` xuyên suốt flow.
* Set `causationId` (ID của event kích hoạt trước đó).
* Set `schemaVersion = "1.0"`.
* Set `occurredAt` theo chuẩn UTC (Instant).
* Truyền `aggregateId` (ví dụ: `tripId`) làm partition key khi publish.

### Nguyên tắc Transactional Outbox Pattern:

Để đảm bảo tính tin cậy tuyệt đối và tránh lỗi **Dual-Write** (Database commit nhưng Kafka bị ngắt kết nối làm mất event):

```text
1. Business logic + INSERT outbox_events  → thực thi trong 1 local DB transaction duy nhất
2. Background poller (hoặc outbox scheduler) đọc bản ghi chưa publish (published = FALSE)
3. Publish event lên Kafka (với partition key = aggregateId)
4. Update bản ghi outbox_events thành published = TRUE
```

Các service phát sinh event chính (`trip-service`, `payment-service`) bắt buộc áp dụng bảng `outbox_events` theo [docs/database_schema.md](file:///d:/Nam_4/He-thong-phan-tan/ds-project/docs/database_schema.md).

Hằng số Topic không được hard-code rải rác:

```java
public static final String TRIP_EVENTS_TOPIC = "trip-events";
public static final String MATCHING_EVENTS_TOPIC = "matching-events";
public static final String PAYMENT_EVENTS_TOPIC = "payment-events";
```

---

# 25. Correlation ID

Mỗi request xuyên suốt hệ thống cần có:

```text
correlationId
```

Ví dụ:

```text
Passenger
   ↓ correlationId=A
API Gateway
   ↓
Trip Service
   ↓
Kafka
   ↓ correlationId=A
Matching Service
   ↓
Driver Service
```

Correlation ID phải được log để trace request.

---

# 26. Causation ID

Khi event được tạo ra từ một event khác:

```text
Event A
   ↓
Event B
```

Event B có thể sử dụng:

```text
causationId = Event A.eventId
```

Mục đích là trace nguyên nhân của event.

---

# 27. Database Convention

### Danh sách 5 PostgreSQL Database chính thức:

```text
identity_db  → User credentials, roles, JWT refresh tokens
driver_db    → Driver profiles, vehicles, live locations, driver status
trip_db      → Trip state machine, outbox events, processed events, saga log
matching_db  → Match requests, driver offers, processed events
payment_db   → Payment transactions, ledger records, outbox events, processed events
```

> **Lưu ý:** `pricing-service` và `notification-service` là các **Stateless Services** — tuyệt đối KHÔNG tạo database cho 2 service này.

### Naming Table & Column:

* **Tên table:** `snake_case`, số nhiều (plural).
  Ví dụ: `users`, `drivers`, `vehicles`, `trips`, `payments`, `processed_events`, `outbox_events`, `saga_log`.
* **Tên column:** `snake_case`.
  Ví dụ: `driver_id`, `passenger_id`, `created_at`, `updated_at`, `version`, `trip_status`.
* **Zero Cross-DB Foreign Keys:** Khóa ngoại vật lý chỉ tồn tại nội bộ trong cùng một database. Liên kết giữa các service chỉ lưu trữ dưới dạng `UUID` (soft reference).

---

# 28. Timestamp

Các timestamp nên sử dụng thời gian UTC.

Trong Java ưu tiên:

```java
Instant
```

Ví dụ:

```java
private Instant createdAt;
private Instant updatedAt;
```

Không tự ý sử dụng timezone local cho dữ liệu phân tán.

---

# 29. Concurrency Control (CAS Atomic SQL Update)

Matching & Driver Reservation là trọng tâm xử lý tương tranh của hệ thống.

> **CRITICAL ARCHITECTURAL DECISION:**  
> Hệ thống áp dụng **ZERO DISTRIBUTED LOCKS** (không dùng Redis, Redlock, hay ZooKeeper).  
> Tính toàn vẹn dữ liệu được đảm bảo 100% bằng **Compare-And-Swap (CAS) Atomic SQL UPDATE** ở tầng PostgreSQL.

### Vấn đề Race Condition (Hai khách cùng tranh chấp 1 tài xế):

```text
Request A ──┐
            ├── Cả hai cùng thấy Driver trạng thái AVAILABLE
Request B ──┘
```

Nếu chỉ kiểm tra:
```java
if (driver.getStatus() == AVAILABLE) { // SAI NGHIÊM TRỌNG! Dễ bị double booking
    driver.setStatus(RESERVED);
}
```

### Cơ chế CAS Atomic UPDATE bắt buộc:

Matching Service gọi REST API sang Driver Service (`POST /api/drivers/{id}/reserve`), Driver Service thực thi câu lệnh SQL nguyên tử:

```sql
UPDATE drivers
SET status = 'RESERVED',
    version = version + 1,
    updated_at = NOW()
WHERE driver_id = :driverId
  AND status = 'AVAILABLE'
  AND version = :expectedVersion;
```

* **Nếu số row updated = 1:** Khóa thành công → Trả về HTTP `200 OK`.
* **Nếu số row updated = 0:** Đã bị request khác giành mất hoặc version bị lệch → Trả về HTTP `409 Conflict`. Matching Service tự động fallback sang tìm tài xế tiếp theo.

### Lớp bảo vệ bổ sung (Partial Unique Index):

Tại `trip_db`, bảng `trips` có partial unique index ngăn một tài xế nhận 2 chuyến đang hoạt động:

```sql
CREATE UNIQUE INDEX uq_driver_single_active_trip
ON trips(driver_id)
WHERE status IN ('ASSIGNED', 'IN_PROGRESS');
```

Mọi thay đổi liên quan đến reservation bắt buộc phải có **Concurrency Test** kiểm chứng.

---

# 30. Transaction

Transaction phải được đặt ở business service.

Ví dụ:

```java
@Transactional
public void reserveDriver(UUID driverId) {
    ...
}
```

Không sử dụng transaction một cách tùy tiện xuyên nhiều microservice.

### Không được giả định

```text
Trip DB transaction
        +
Driver DB transaction
        +
Payment DB transaction
```

là một distributed transaction đơn giản.

Các flow phân tán phải sử dụng:

```text
Kafka
Saga
Compensation
Eventual Consistency
```

theo thiết kế hệ thống.

---

# 31. Saga Convention

Saga được sử dụng cho các business flow phân tán.

Ví dụ:

```text
Trip Completed
      ↓
Payment
      ↓
Payment Failed
      ↓
Compensation
```

Khi một bước thất bại:

```text
Forward Action
      ↓
Failure
      ↓
Compensation
```

Không rollback database của service khác bằng cách truy cập trực tiếp database đó.

---

# 32. Payment Failure

Payment failure phải được xử lý thông qua event:

```text
PaymentFailed
```

Không được giả lập bằng cách sửa trực tiếp dữ liệu trong `trip-service`.

Flow:

```text
TripCompleted
      ↓
payment-service
      ↓
PaymentFailed
      ↓
trip-service
      ↓
Compensation / state update
```

---

# 33. Logging

Log phải có context.

Ví dụ:

```text
timestamp
level
service
correlationId
eventId
eventType
message
```

Ví dụ:

```text
INFO
service=matching-service
correlationId=abc
eventId=xyz
eventType=RideRequested
message="Processing ride request"
```

Không log:

```text
password
JWT token
database password
secret key
```

---

# 34. Log Level

Sử dụng:

```text
TRACE
DEBUG
INFO
WARN
ERROR
```

### INFO

Business flow quan trọng:

```text
Trip created
Driver assigned
Payment completed
```

### WARN

Các vấn đề có thể recover:

```text
Kafka retry
Duplicate event
Slow external call
```

### ERROR

Các lỗi cần xử lý:

```text
Database failure
Unhandled exception
Kafka processing failure
```

Không dùng `ERROR` cho mọi log.

---

# 34.1. Observability & Monitoring Convention (Actuator, Prometheus, Grafana)

Toàn bộ 8 microservices bắt buộc phải tích hợp **Spring Boot Actuator** và **Micrometer Prometheus Registry**:

### Cấu hình `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
  prometheus:
    metrics:
      export:
        enabled: true
```

### Endpoints bắt buộc:

* `GET /actuator/health`: Dùng cho Docker Compose healthcheck và Gateway service verification.
* `GET /actuator/prometheus`: Endpoint scrape dữ liệu định kỳ cho Prometheus (:9090).

### Quy tắc đặt tên Custom Metric (Micrometer Counter / Timer):

```text
ridehailing.<domain>.<action>.<type>
```

Ví dụ:
* `ridehailing.matching.duration`: Thời gian xử lý matching cuốc xe.
* `ridehailing.trips.created.total`: Tổng số chuyến đi được tạo.
* `ridehailing.payment.failure.total`: Tổng số giao dịch thanh toán thất bại.

---

# 35. Configuration

Configuration phải nằm trong:

```text
application.yml
```

hoặc environment variable.

Ví dụ:

```yaml
server:
  port: ${SERVER_PORT:8083}
```

Database:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

---

# 36. Secrets

Tuyệt đối không commit:

```text
.env
password
JWT secret
API key
database credentials
private key
access token
```

Repository chỉ chứa:

```text
.env.example
```

Ví dụ:

```env
DB_USERNAME=postgres
DB_PASSWORD=change-me
JWT_SECRET=change-me
```

---

# 37. Security

Authentication sử dụng:

```text
JWT
Spring Security
```

Authorization sử dụng role:

```text
PASSENGER
DRIVER
ADMIN
```

Không hard-code authorization.

Không được:

```java
if (user.getEmail().equals("admin@gmail.com")) {
    ...
}
```

Phải sử dụng role/authority.

---

# 38. Password

Password phải được hash.

Ví dụ:

```java
PasswordEncoder
```

Không bao giờ lưu:

```text
password = "123456"
```

plain text trong database.

---

# 39. Testing Convention

Mỗi feature quan trọng phải có test.

## Unit Test

```text
Service
Business Logic
Validation
Exception
```

## Integration Test

```text
Controller
Database
Kafka
```

## Concurrency Test

Đặc biệt với:

```text
Driver Reservation
Matching
```

---

# 40. Test Naming

Sử dụng format:

```text
methodName_condition_expectedResult
```

Ví dụ:

```java
createTrip_validRequest_returnsTrip()

createTrip_invalidPassenger_throwsException()

reserveDriver_concurrentRequests_onlyOneSucceeds()

processPayment_duplicateEvent_doesNotProcessTwice()
```

Tên test phải nói rõ:

```text
WHAT
WHEN
EXPECTED RESULT
```

---

# 41. Bug Fix Rule

Mỗi bug nghiêm trọng nên có regression test.

Ví dụ bug:

```text
2 passenger cùng reserve 1 driver
```

Sau khi fix phải có test:

```java
reserveDriver_concurrentRequests_onlyOneSucceeds()
```

Không chỉ sửa code mà không có test.

---

# 42. Git Branch Convention

Branch chính:

```text
main
develop
```

## main

Stable version.

Chỉ merge code đã được kiểm tra.

## develop

Integration branch.

Code từ các member được merge vào đây.

---

# 43. Feature Branch

Format:

```text
feature/<member>/<feature>
```

Ví dụ:

```text
feature/m1/identity-service
feature/m1/jwt-security

feature/m2/trip-service
feature/m2/matching-service
feature/m2/concurrency-control

feature/m3/payment-service
feature/m3/kafka-consumer
feature/m3/notification-service
```

Bug:

```text
bugfix/m2/driver-double-reservation
```

Hotfix:

```text
hotfix/payment-processing
```

---

# 44. Không tạo branch quá lớn

Không:

```text
feature/m2/do-everything
```

Nên tách:

```text
feature/m2/trip-service
feature/m2/matching-service
feature/m2/concurrency-control
feature/m2/saga
```

Mỗi branch nên có một mục tiêu rõ ràng.

---

# 45. Commit Convention

Sử dụng Conventional Commits.

Format:

```text
<type>(<scope>): <description>
```

Các type:

```text
feat
fix
refactor
test
docs
chore
build
ci
perf
```

Ví dụ:

```text
feat(trip): add create trip endpoint

feat(matching): implement nearest driver matching

fix(driver): prevent duplicate driver reservation

fix(payment): handle payment failure event

test(matching): add concurrent reservation test

docs(api): update trip api contract

chore(docker): update kafka configuration

ci(github): add build workflow
```

---

# 46. Commit Rules

Commit phải:

* Nhỏ.
* Có mục tiêu rõ ràng.
* Không chứa code không liên quan.
* Không commit secret.
* Không commit generated files không cần thiết.

Không:

```text
feat: update everything
```

Nên:

```text
feat(trip): add trip creation service
test(trip): add trip creation tests
```

---

# 47. Pull Request Convention

Mỗi PR nên có:

```text
## Summary

## Changed Services

## API Changes

## Kafka/Event Changes

## Database Changes

## Tests

## Risks / Breaking Changes
```

Ví dụ:

```text
## Summary

Implement driver reservation with optimistic locking.

## Changed Services

- driver-service
- matching-service

## API Changes

No API changes.

## Kafka/Event Changes

No event contract changes.

## Database Changes

Added version column.

## Tests

- Unit test
- Concurrent reservation test

## Risks

None.
```

---

# 48. Contract-First Rule

Đây là nguyên tắc bắt buộc.

Trước khi service A gọi service B phải thống nhất:

```text
API Contract
Event Contract
Database Contract
```

Không được:

```text
Code trước
→ sau đó mới thông báo service khác
```

Phải:

```text
Contract
   ↓
Implementation
   ↓
Integration Test
```

---

# 49. Không tự ý thay đổi Contract

Nếu muốn thay đổi:

```text
API
Event
Database
```

phải thông báo team trước.

Ví dụ không tự ý đổi:

```json
{
  "driverId": "..."
}
```

thành:

```json
{
  "assignedDriver": "..."
}
```

nếu API contract đã được thống nhất.

---

# 50. Vibe Coding Rules

## Đây là phần bắt buộc đối với AI Agent.

Khi sử dụng:

```text
Claude
ChatGPT
GitHub Copilot
Cursor
Antigravity
Gemini
Codex
```

AI phải tuân thủ các nguyên tắc dưới đây.

---

## Rule 1 — Đọc context trước khi code

Trước khi sửa code, AI phải đọc:

```text
README.md
SRS.md
API Contract
Event Contract
CODING_CONVENTION.md
```

và inspect code hiện tại của service.

Không được code dựa trên assumption.

---

## Rule 2 — Không tự tạo kiến trúc mới

AI không được tự ý thêm:

```text
Eureka
Consul
Redis
Redlock
RabbitMQ
gRPC
Kubernetes
Service Mesh
```

nếu task không yêu cầu.

Project hiện tại chỉ sử dụng:

```text
REST API (qua Docker DNS & Spring Cloud Gateway)
Apache Kafka (KRaft mode:9092)
PostgreSQL 16 (5 databases độc lập)
Docker Compose
Prometheus & Grafana
```

### Rule 2.1 — Không tự ý thêm Database cho pricing-service hoặc notification-service
* `pricing-service` là pure stateless (tính cước theo công thức).
* `notification-service` là pure stateless (lắng nghe event và push/log).

### Rule 2.2 — Không dùng Distributed Lock bên ngoài
Tuyệt đối không thêm Redis hay ZooKeeper để lock tài xế. Mọi cơ chế concurrency control giải quyết bằng **CAS Atomic SQL UPDATE** trên `drivers.version`.

### Rule 2.3 — Luôn chạy lệnh Gradle trong thư mục của service
Thư mục gốc không có Gradle. Lệnh build/test luôn phải cd vào service:
`cd src/<service-name> && ./gradlew test`

---

## Rule 3 — Không tự tạo API

AI không được tự ý thêm:

```text
/api/matching/force-match
/api/trips/admin-reset
/api/drivers/auto-assign
```

nếu API chưa được định nghĩa.

Nếu cần API mới:

```text
STOP
↓
Report proposed API
↓
Team approve
↓
Update API Contract
↓
Implement
```

---

## Rule 4 — Không tự tạo Kafka Event

AI không được tự ý tạo:

```text
DriverMatched
PaymentRequested
DriverRejected
TripAccepted
```

Nếu cần event mới:

```text
STOP
↓
Đề xuất event
↓
Team review
↓
Update Event Contract
↓
Implement
```

---

## Rule 5 — Không tự thay đổi Database Contract

AI không được tự ý:

```text
rename column
delete column
change ID type
change relationship
change enum
```

nếu không được yêu cầu.

---

## Rule 6 — Không refactor ngoài scope

Nếu task:

```text
Implement matching algorithm
```

AI không được tự tiện:

```text
rewrite Trip Service
rewrite Kafka architecture
rename packages
upgrade Spring Boot
replace database
```

Chỉ thay đổi phần cần thiết.

---

## Rule 7 — Không sửa service của member khác

Ví dụ Member 2 đang làm:

```text
trip-service
matching-service
```

AI không được tự ý sửa:

```text
payment-service
notification-service
```

trừ khi task yêu cầu integration.

---

## Rule 8 — Không thêm dependency nếu không cần

Trước khi thêm dependency:

```text
Check existing dependencies
↓
Có thư viện đáp ứng không?
↓
Nếu có → sử dụng thư viện hiện tại
↓
Nếu không → đề xuất dependency mới
```

Không thêm dependency chỉ vì AI quen sử dụng nó.

---

## Rule 9 — Không dùng deprecated API

AI phải ưu tiên API tương thích:

```text
Java 17
Spring Boot 3.x
Jakarta namespace
```

Không tự ý sử dụng API đã deprecated.

---

## Rule 10 — Không che giấu lỗi

Không được:

```java
try {
    ...
} catch (Exception e) {
    // ignore
}
```

Không được:

```java
catch (Exception e) {
    return null;
}
```

Phải xử lý exception đúng ngữ cảnh.

---

# 51. AI Coding Workflow

Mỗi task AI nên thực hiện theo flow:

```text
1. Understand Task
        ↓
2. Read Convention
        ↓
3. Inspect Existing Code
        ↓
4. Identify Affected Service
        ↓
5. Check API/Event/DB Contract
        ↓
6. Implement Minimal Change
        ↓
7. Run Tests
        ↓
8. Run Build
        ↓
9. Review Changes
        ↓
10. Report
```

---

# 52. AI không được đoán

Nếu thiếu thông tin:

```text
Do not guess.
```

Ví dụ AI không biết:

```text
TripStatus có những state nào?
```

Không được tự tạo:

```text
PENDING
SEARCHING
MATCHED
```

AI phải kiểm tra:

```text
Entity
Enum
SRS
API Contract
Event Contract
```

Nếu vẫn không có:

```text
Ask / report ambiguity.
```

---

# 53. AI Task Prompt Template

Mỗi member có thể sử dụng prompt:

```text
You are working on the DS03 Ride-Hailing Microservices System.

Read and follow:
- SRS.md
- API Contract
- Event Contract
- CODING_CONVENTION.md

Task:
[DESCRIBE TASK]

Service:
[SERVICE NAME]

Requirements:
- Follow existing architecture.
- Do not invent new API.
- Do not invent new Kafka event.
- Do not change database contract unless explicitly requested.
- Do not modify unrelated services.
- Do not add unnecessary dependencies.
- Keep changes minimal.
- Follow existing naming and package conventions.

Before coding:
1. Inspect the existing implementation.
2. Identify affected files.
3. Check existing API/Event/DB contracts.

After coding:
1. Run tests.
2. Run build.
3. Check for compilation errors.
4. Report files changed.
5. Report tests executed.
6. Report any risks or breaking changes.
```

---

# 54. Definition of Done

Một feature chỉ được xem là hoàn thành khi:

```text
[ ] Code compile
[ ] Unit tests pass
[ ] Integration tests pass nếu cần
[ ] API đúng contract
[ ] OpenAPI annotations đầy đủ (@Tag, @Operation, @ApiResponses, @Schema)
[ ] Swagger UI mở được tại /swagger-ui/index.html và test được API
[ ] /v3/api-docs trả về OpenAPI schema hợp lệ
[ ] Event đúng contract
[ ] DB đúng contract
[ ] Exception handling đầy đủ
[ ] Validation đầy đủ
[ ] Logging hợp lý
[ ] Không có secret
[ ] Không có TODO quan trọng
[ ] Không có debug print
[ ] Không sửa code ngoài scope
[ ] Docker build thành công
[ ] Service chạy được
```

---

# 55. Pre-Commit Checklist

Trước khi commit:

```text
[ ] git status
[ ] Review git diff
[ ] Remove debug code
[ ] Remove passwords/secrets
[ ] Run tests
[ ] Run build
[ ] Check API changes
[ ] Check Swagger UI & /v3/api-docs
[ ] Check Kafka changes
[ ] Check DB changes
[ ] Check logs
```

---

# 56. Integration Checklist

Trước khi merge vào `develop`:

```text
[ ] Service build thành công
[ ] Docker build thành công
[ ] Service start thành công
[ ] Health check OK
[ ] API test OK
[ ] Kafka event test OK nếu có
[ ] Database migration/schema OK
[ ] No breaking API changes
[ ] No breaking Event changes
[ ] No secrets
```

---

# 57. Happy Path Priority

Trong quá trình phát triển, ưu tiên:

```text
1. Happy Path
2. Integration
3. Concurrency
4. Kafka
5. Saga
6. Retry / DLQ
7. Fault Recovery
8. Testing
9. Performance
10. Documentation
11. Advanced Features
```

Không được bỏ qua Happy Path để chạy theo feature nâng cao.

---

# 58. End-to-End Happy Path

Flow chính:

```text
Register
   ↓
Login
   ↓
Driver AVAILABLE
   ↓
Passenger creates Trip
   ↓
Matching
   ↓
Driver Selected
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

Đây là flow ưu tiên cao nhất để kiểm chứng hệ thống.

---

# 59. Service Ownership

| Member   | Services / Responsibilities                 |
| -------- | ------------------------------------------- |
| Member 1 | Identity + Driver + Security                |
| Member 2 | Trip + Matching + Concurrency + Saga        |
| Member 3 | Pricing + Payment + Notification + Kafka/MQ |

Ngoài ra:

```text
Member 1 → API Gateway / API Contract
Member 2 → Integration Test
Member 3 → Docker / Monitoring / CI/CD
```

Ownership không có nghĩa member khác không được hỗ trợ, nhưng tránh tự ý sửa service của nhau.

---

# 60. Những điều TUYỆT ĐỐI KHÔNG LÀM

```text
❌ Commit password
❌ Commit JWT secret
❌ Hard-code credentials
❌ Cross-service database access
❌ Dùng localhost giữa containers
❌ Tự tạo API
❌ Tự tạo API giả / mock endpoint chỉ để hiển thị trên Swagger
❌ Document API sai lệch với code thực tế hoặc docs/api.md
❌ Expose password, secret, token trong Swagger schema/example
❌ Disable Spring Security (permitAll("/**")) chỉ để chạy Swagger
❌ Tự tạo Kafka Event
❌ Tự đổi Kafka Topic
❌ Tự đổi DB schema
❌ Expose Entity trực tiếp
❌ Business logic trong Controller
❌ Ignore Exception
❌ Log password/token
❌ Infinite Kafka retry
❌ Disable test để build pass
❌ Refactor unrelated code
❌ Push trực tiếp code chưa test vào main
❌ Merge code chưa review
```

---

# 61. Quy tắc vàng

> **Contract trước — Code sau.**

> **Không đoán — Inspect trước.**

> **Không tự mở rộng scope.**

> **Một service sở hữu một database.**

> **Một event phải có contract rõ ràng.**

> **Consumer phải idempotent.**

> **Distributed transaction không được giải quyết bằng cách truy cập DB của service khác.**

> **AI chỉ là coding assistant, không phải người quyết định architecture.**

> **Nếu AI không chắc chắn → dừng và hỏi, không tự đoán.**

---

# 62. Version Control của Convention

File này được xem là một phần của project contract.

Khi thay đổi quy tắc:

```text
CODING_CONVENTION.md
        ↓
Team Review
        ↓
Approve
        ↓
Commit
        ↓
Notify all members
```

Không để mỗi member sử dụng một version convention khác nhau.

---

# 63. Final Rule

Mọi thành viên phải đảm bảo:

```text
Read Convention
      ↓
Understand Contract
      ↓
Implement
      ↓
Test
      ↓
Review
      ↓
Commit
      ↓
Pull Request
      ↓
Merge develop
```

**Mục tiêu cuối cùng không phải là viết code thật nhanh, mà là để 3 member có thể cùng vibe code mà code của AI vẫn nằm trong cùng một kiến trúc, contract và coding style.**
