# Driver Service – dữ liệu test

> **Nâng cấp từ bản cũ:** bảng `drivers` đã bỏ cột `user_id` (giờ `driver_id` = `userId`) và thêm `full_name NOT NULL`. `ddl-auto: update` không xóa được cột cũ, nên volume `driver_db` tạo trước thay đổi này phải tạo lại, nếu không đăng ký tài xế trả `503` và seeder ghi `could not be created`:
>
> ```bash
> docker compose down -v                      # xóa mọi volume, hoặc chỉ xóa driver_db:
> docker compose rm -sf driver-db && docker volume rm <project>_driver_db_data   # <project>: xem `docker volume ls`
> docker compose up -d --build
> ```

## Hồ sơ tài xế demo (seed tự động)

Khi `driver-service` khởi động với `SEED_DATA_ENABLED=true` (mặc định), class `DemoDriverSeeder` tạo hồ sơ cho 10 tài khoản DRIVER mà `identity-service` seed sẵn. `driverId` chính là `userId` của tài khoản (xem `tests/data/identity/README.md`), đăng nhập bằng `driverNN@ds03.test` / `Demo@12345`.
Hồ sơ đã tồn tại thì bỏ qua. Vị trí là giả lập quanh Hồ Gươm, Hà Nội; khoảng cách tính từ Hồ Gươm (21.0285, 105.8542).

| # | driverId | Họ tên | Trạng thái | Vị trí | Cách Hồ Gươm | Xe |
|---|---|---|---|---|---|---|
| 1 | `d000…0001` | Tran Van Binh | AVAILABLE | Tràng Tiền | 0.49 km | `30A-10001` Toyota Vios, SEDAN |
| 2 | `d000…0002` | Le Hoang Nam | AVAILABLE | Hàng Bài | 1.08 km | `30A-10002` Honda City, SEDAN |
| 3 | `d000…0003` | Pham Minh Tuan | AVAILABLE | Hàng Ngang | 0.77 km | `29B1-10003` Honda Wave, MOTORBIKE |
| 4 | `d000…0004` | Hoang Duc Anh | AVAILABLE | Long Biên | 3.25 km | `30A-10004` Ford Everest, SUV |
| 5 | `d000…0005` | Vu Thi Lan | AVAILABLE | Ba Đình | 2.27 km | `30A-10005` Hyundai Accent, SEDAN |
| 6 | `d000…0006` | Do Quang Huy | AVAILABLE | Hai Bà Trưng | 2.55 km | `30A-10006` Kia Carnival, VAN |
| 7 | `d000…0007` | Bui Van Long | AVAILABLE | Đống Đa | 2.86 km | `29B1-10007` Yamaha Exciter, MOTORBIKE |
| 8 | `d000…0008` | Dang Thi Mai | AVAILABLE | Cầu Giấy | 6.68 km (ngoài 5 km) | `30A-10008` Mazda 3, SEDAN |
| 9 | `d000…0009` | Ngo Thanh Son | OFFLINE | Tây Hồ | 5.40 km | `30A-10009` Toyota Fortuner, SUV |
| 10 | `d000…0010` | Duong Van Khoa | OFFLINE | chưa có | – | `30A-10010` Kia Morning, SEDAN |

`d000…00NN` = `d0000000-0000-4000-8000-0000000000NN`.

Kết quả mong đợi của `GET /api/drivers/available?latitude=21.0285&longitude=105.8542` (bán kính mặc định 5 km): 7 tài xế, thứ tự #1, #3, #2, #5, #6, #7, #4. Tài xế #8 bị loại vì ở ngoài 5 km; #9 và #10 vì đang `OFFLINE`.

## Request mẫu

| File | Endpoint | Kết quả mong đợi |
|---|---|---|
| `01_status_available.json` | `PATCH /api/drivers/{id}/status` | `200`, `status = AVAILABLE` (gửi lại lần nữa vẫn `200`, không đổi gì) |
| `02_status_offline.json` | `PATCH /api/drivers/{id}/status` | `200`, `status = OFFLINE` |
| `03_status_busy_not_allowed.json` | `PATCH /api/drivers/{id}/status` | `422 INVALID_STATUS_TRANSITION` |
| `04_status_unknown_value.json` | `PATCH /api/drivers/{id}/status` | `400 MALFORMED_REQUEST` |
| `05_location_hoan_kiem.json` | `PATCH /api/drivers/{id}/location` | `200`, vị trí lưu 6 chữ số thập phân |
| `06_location_out_of_range.json` | `PATCH /api/drivers/{id}/location` | `400 VALIDATION_ERROR` |

Quyền truy cập:

| Endpoint | Token cần có |
|---|---|
| `GET /api/drivers/available` | Không cần (matching-service gọi không kèm token); bắt buộc `latitude` + `longitude`, tối đa `limit` (mặc định 20, ≤ 50) tài xế |
| `GET /api/drivers/nearby` | `PASSENGER` |
| `GET /api/drivers/{id}` | Bất kỳ role nào; vị trí của tài xế `OFFLINE` chỉ trả cho chính tài xế đó, người khác thấy `location = null` |
| `PATCH /api/drivers/{id}/status`, `/location` | `DRIVER` và `{id}` phải là chính mình → sai role `403 ACCESS_DENIED`, sửa hồ sơ người khác `403 NOT_OWN_RESOURCE` |

## Luồng demo (qua API Gateway :8080, hoặc gọi thẳng :8082 cho driver và :8081 cho identity)

```bash
GW=http://localhost:8080
T=tests/data/driver

# 1. Tài xế #9 (đang OFFLINE) đăng nhập; userId trả về chính là driverId
curl -s -X POST $GW/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"driver09@ds03.test","password":"Demo@12345"}'
DTOKEN=<accessToken>
DID=d0000000-0000-4000-8000-000000000009

# 2. Bật nhận cuốc và báo vị trí gần Hồ Gươm
curl -s -X PATCH $GW/api/drivers/$DID/status   -H "Authorization: Bearer $DTOKEN" -H "Content-Type: application/json" -d @$T/01_status_available.json
curl -s -X PATCH $GW/api/drivers/$DID/location -H "Authorization: Bearer $DTOKEN" -H "Content-Type: application/json" -d @$T/05_location_hoan_kiem.json

# 3. Danh sách tài xế rảnh quanh Hồ Gươm (không cần token): tài xế #9 giờ đứng đầu (~0.25 km)
curl -s "$GW/api/drivers/available?latitude=21.0285&longitude=105.8542"

# 4. Hành khách tìm 3 tài xế gần nhất và xem chi tiết một tài xế
curl -s -X POST $GW/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"passenger01@ds03.test","password":"Demo@12345"}'
PTOKEN=<accessToken>
curl -s "$GW/api/drivers/nearby?latitude=21.0285&longitude=105.8542&limit=3" -H "Authorization: Bearer $PTOKEN"
curl -s $GW/api/drivers/$DID -H "Authorization: Bearer $PTOKEN"

# 5. Các trường hợp lỗi
curl -s -X PATCH $GW/api/drivers/$DID/status -H "Authorization: Bearer $DTOKEN" -H "Content-Type: application/json" -d @$T/03_status_busy_not_allowed.json   # 422
curl -s -X PATCH $GW/api/drivers/d0000000-0000-4000-8000-000000000001/status -H "Authorization: Bearer $DTOKEN" \
  -H "Content-Type: application/json" -d @$T/02_status_offline.json                                                                  # 403 NOT_OWN_RESOURCE
curl -s -X PATCH $GW/api/drivers/$DID/status -H "Authorization: Bearer $PTOKEN" -H "Content-Type: application/json" -d @$T/02_status_offline.json   # 403 ACCESS_DENIED
```
