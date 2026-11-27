# Identity Service – dữ liệu test

## Tài khoản demo (seed tự động)

Khi `identity-service` khởi động với `SEED_DATA_ENABLED=true` (mặc định), class `DemoUserSeeder` tạo sẵn các tài khoản dưới đây trong `identity_db`.
Email nào đã tồn tại thì bỏ qua, nên khởi động lại service không tạo trùng.

Tất cả dùng chung mật khẩu `SEED_USER_PASSWORD` (mặc định `Demo@12345`). Đây là dữ liệu giả chỉ dùng cho local/demo: tắt bằng `SEED_DATA_ENABLED=false` ở môi trường khác.

`userId` là **cố định**. 10 tài xế có hồ sơ xe tương ứng bên `driver-service` với `driverId` = `userId` (xem `tests/data/driver/README.md`).

| # | Họ tên | Email | Role | userId (= driverId) |
|---|---|---|---|---|
| 1 | Tran Van Binh | `driver01@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000001` |
| 2 | Le Hoang Nam | `driver02@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000002` |
| 3 | Pham Minh Tuan | `driver03@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000003` |
| 4 | Hoang Duc Anh | `driver04@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000004` |
| 5 | Vu Thi Lan | `driver05@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000005` |
| 6 | Do Quang Huy | `driver06@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000006` |
| 7 | Bui Van Long | `driver07@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000007` |
| 8 | Dang Thi Mai | `driver08@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000008` |
| 9 | Ngo Thanh Son | `driver09@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000009` |
| 10 | Duong Van Khoa | `driver10@ds03.test` | DRIVER | `d0000000-0000-4000-8000-000000000010` |
| 11 | Nguyen Thi Hoa | `passenger01@ds03.test` | PASSENGER | `a0000000-0000-4000-8000-000000000001` |
| 12 | Ly Minh Chau | `passenger02@ds03.test` | PASSENGER | `a0000000-0000-4000-8000-000000000002` |
| 13 | Phan Gia Bao | `passenger03@ds03.test` | PASSENGER | `a0000000-0000-4000-8000-000000000003` |

> Nếu `identity_db` đã được seed bởi bản cũ (id ngẫu nhiên), seeder chỉ ghi cảnh báo và không đổi id. Xóa volume để seed lại: `docker compose down -v`, hoặc xóa riêng hai volume `<project>_identity_db_data` và `<project>_driver_db_data` (`<project>` mặc định là tên thư mục repo, xem bằng `docker volume ls`).

## Request mẫu

| File | Endpoint | Kết quả mong đợi |
|---|---|---|
| `01_register_passenger.json` | `POST /api/auth/register/passenger` | `201` (lần 2 → `409 EMAIL_ALREADY_REGISTERED`) |
| `02_register_driver.json` | `POST /api/auth/register/driver` | `201` + `driver` (status `OFFLINE`, biển số lưu `29A-12345`); gửi lại với email khác → `409 LICENSE_PLATE_ALREADY_REGISTERED` |
| `03_login_seeded_driver.json` | `POST /api/auth/login` | `200` + `accessToken`, `role = DRIVER` |
| `04_login_seeded_passenger.json` | `POST /api/auth/login` | `200` + `accessToken`, `role = PASSENGER` |
| `05_login_wrong_password.json` | `POST /api/auth/login` | `401 INVALID_CREDENTIALS` |
| `06_login_missing_password.json` | `POST /api/auth/login` | `400 VALIDATION_ERROR` |
| `07_register_driver_invalid_plate.json` | `POST /api/auth/register/driver` | `400 VALIDATION_ERROR` từ driver-service; tài khoản bị xóa lại (login bằng email đó → `401`) |
| `08_register_driver_missing_vehicle.json` | `POST /api/auth/register/driver` | `400 VALIDATION_ERROR` ngay ở identity, không tạo gì |

Đăng ký tài xế khi driver-service đang tắt → `503 DRIVER_SERVICE_UNAVAILABLE`, tài khoản không được giữ lại (xem saga trong `docs/api.md`).

## Luồng demo: Login → GET /api/users/{id}

```bash
# 1. Đăng nhập bằng tài xế seed (qua API Gateway :8080, hoặc gọi thẳng :8081)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d @tests/data/identity/03_login_seeded_driver.json

# 2. Lấy accessToken và userId từ response ở bước 1
TOKEN=<accessToken>
USER_ID=<userId>

# 3. Xem hồ sơ (cần JWT)
curl -s http://localhost:8080/api/users/$USER_ID -H "Authorization: Bearer $TOKEN"
```

## Lỗi 401 của các endpoint cần JWT

| Trường hợp | `code` |
|---|---|
| Không gửi header `Authorization: Bearer ...` | `MISSING_TOKEN` |
| Token sai định dạng, sai chữ ký, thiếu claim `role` | `INVALID_TOKEN` |
| Token hết hạn (`JWT_EXPIRATION_MS`, mặc định 24h) | `TOKEN_EXPIRED` |
