# Member 2 W3 — triển khai và bàn giao

Nhánh `feature/m2/week3-integration`, nền `develop` tại `bb09de7`.

## Phần M2

- MatchRequest assigned UUID (`requestId = tripId`) với lifecycle Persistable; không tạo passenger/pickup giả.
- Matching consume RideRequested/TripCancelled, composite dedup, chọn driver gần nhất hợp lệ, fallback CAS 409, outbox nguyên tử. DriverReserved có passengerId, ETA, lease và causation.
- Internal clients gửi key/correlation, release kèm tripId; không biến lỗi HTTP thành danh sách rỗng.
- Tối đa 3 vòng/60s, scheduler 5s; compensation khi outbox hoặc DB commit lỗi. Không rematch MATCHED event đang có thể in-flight.
- Trip kiểm tra identity/role/ownership và state; khóa dòng Trip khi cập nhật để tránh race cancel/start. Giá lấy từ quote/final, TripCompleted đủ 8 field.
- Payment result cập nhật từ COMPLETED, dedup/history/saga cùng transaction. PaymentFailed không hoàn tác tài xế.
- Hủy chuyến commit state/outbox trước, sau đó mới DELETE trong after-commit callback. Nếu DB/outbox rollback thì không nhả tài xế của chuyến còn hoạt động. API chờ lần thử DELETE; event và REST cleanup có thể chạy đua sau commit, đều phải idempotent.
- Outbox đợi ACK có timeout, giữ message khi lỗi và chặn event sau cùng aggregate vượt event lỗi trong một lượt.
- Không thêm dependency; không sửa source service của M1/M3, Compose hoặc script dùng chung.

## M1 cần bàn giao

1. POST reserve với CAS/version, response đủ field, internal-key auth, 409 CONCURRENT_UPDATE và lease 30s.
2. DELETE reserve có query tripId, chỉ nhả đúng reservation, idempotent 204; Driver giữ job lease cleanup.
3. Driver consume DriverAssigned/TripCompleted/TripCancelled với dedup và kiểm tra reserved_for_trip. DELETE chỉ nhả RESERVED; event hủy nhả được BUSY đúng trip.
4. Gateway xác thực JWT/role, ghi đè X-User-Id/X-User-Role, bảo vệ internal headers/routes. Header tự khai qua port Trip không thay thế JWT; các service dựa trên ranh giới tin cậy Gateway theo kiến trúc hiện tại.

## M3 cần bàn giao

1. Truyền cùng INTERNAL_API_KEY cho **Trip và Matching** trong Compose. M2 mặc định key trống và từ chối lời gọi internal; không hardcode key thật.
2. Có thể override DRIVER_SERVICE_URL, PRICING_SERVICE_URL, TRIP_SERVICE_URL cho local/test; default là Docker DNS.
3. E2E script dùng UUID correlationId thay e2e-..., polling có deadline cho driver/notification bất đồng bộ, và không coi lỗi đọc DLQ offsets là 0.
4. Fixture DriverReserved cần reservedUntil còn hạn khi kiểm tra lease. Script fixtures chưa override amount: kiểm tra JSON trước khi gửi vào Trip có finalFare khác mẫu.
5. Review payload và chạy E2E qua Gateway sau khi đủ phụ thuộc. Kafka kit/topics/fixtures/script Happy Path đã có nên tái sử dụng.

## Kiểm thử

Kết quả cuối sau sửa review: **27 Matching + 44 Trip = 71 test pass**, không failure/error/skip khi bật PostgreSQL test; cả hai `bootJar` pass. Có kiểm thử Kafka KRaft nhúng với 3 partitions và Spring Boot Kafka auto-configuration thật. Hai phát hiện review đã có regression RED→GREEN: rollback khi hủy không nhả tài xế, correlation UUID viết tắt bị chặn trước khi tạo Trip. Đây là kết quả M2, chưa phải E2E hệ thống qua Gateway.

Chạy `gradlew.bat test` riêng trong từng thư mục `src/matching-service`, `src/trip-service`. Test HTTP dùng server cục bộ; Kafka relay dùng KRaft broker nhúng từ dependency sẵn có.

Test PostgreSQL chỉ bật bằng M2_TEST_DB_URL, user/password test `m2_test`/`m2_test`. **Chỉ trỏ vào database test riêng:** các test tạo/xóa bảng ở schema m2_trip, m2_matching; regression MatchRequest dùng bảng match_requests ở schema mặc định.

```powershell
docker run -d --name ds03-m2-w3-test-db -e POSTGRES_USER=m2_test -e POSTGRES_PASSWORD=m2_test -e POSTGRES_DB=m2_tests -p 127.0.0.1:55432:5432 postgres:16-alpine
$env:M2_TEST_DB_URL='jdbc:postgresql://localhost:55432/m2_tests'
# Chạy từ từng thư mục service:
.\gradlew.bat test
docker stop ds03-m2-w3-test-db
```

Test concurrency M2 kiểm tra orchestration khi Driver trả CAS 409. CAS SQL thật thuộc Driver và E2E hai passenger qua Gateway vẫn cần M1/môi trường chung; không lấy test mock thay bằng chứng này.

## Schema và giới hạn

Matching thêm processed_events theo composite event_id/consumer_name đã có trong database_schema.md. MatchRequest vẫn UUID, không thêm version. Giữ mapping Trip/Outbox của baseline, không chạy lại toàn bộ DDL minh họa như migration và không xóa volume để đổi schema.

Stale DriverReserved sau lease, xác nhận assignment liên service, downtime dài hơn lease và recovery Saga đầy đủ vẫn là W5. Chỉ nghiệm thu Happy Path toàn hệ thống khi đủ bàn giao trên.
