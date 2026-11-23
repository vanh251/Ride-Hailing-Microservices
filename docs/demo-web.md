# Giao diện demo

Các trang chạy ngay trong API Gateway tại `/demo/`. Không cần Node, frontend server hoặc CORS. Mọi thao tác gọi API thật với JWT của từng tài khoản.

## Khởi động

```powershell
docker compose up -d --build --wait
```

Mở `http://localhost:8080/demo/login.html`. Nếu database đang chứa dữ liệu tuần 3, thực hiện quy trình cutover và migration trong `docs/operations.md` trước; không xóa volume để nâng cấp.

## Ứng dụng hành khách và tài xế

Gateway phục vụ ba trang dành cho người dùng cuối:

| Trang | Ai dùng | Làm được gì |
|---|---|---|
| `/demo/login.html` | Mọi người | Đăng nhập, tạo tài khoản hành khách hoặc tài xế; tự chuyển sang trang đúng vai trò |
| `/demo/rider.html` | Chỉ `PASSENGER` | Chọn điểm đón/đến trên bản đồ, xem tài xế gần, báo giá, đặt xe, theo dõi và hủy chuyến |
| `/demo/driver.html` | Chỉ `DRIVER` | Đặt vị trí trên bản đồ, bật/tắt nhận chuyến, nhận, bắt đầu và hoàn thành chuyến |

- Phân quyền thật nằm ở Gateway (JWT và vai trò trên từng API). Trang chỉ chuyển người dùng sai vai trò về trang đăng nhập.
- Mỗi sự kiện của chuyến hiện thành popup, lấy từ Notification Service. Tài xế biết chuyến của mình qua thông báo `DriverAssignmentConfirmed`, nên sau khi notification-service khởi động lại, chuyến đang chạy không hiện lại trên trang tài xế.
- Bản đồ dùng Leaflet và tile của openstreetmap.org, nên máy demo cần internet.
- Demo nhanh: mở hai cửa sổ. Một cửa sổ đăng nhập `passenger01@ds03.test`, cửa sổ kia `driver01@ds03.test` (mật khẩu `Demo@12345`). Chọn điểm đón **Nhà hát Lớn**, nằm sát vị trí của driver01, để driver01 là tài xế gần nhất được ghép. Các tài xế seed khác cũng đang AVAILABLE, nên điểm đón ở nơi khác có thể ghép cho người khác.

## Kiểm thử đồng thời (`/demo/index.html`)

Kịch bản "Two passengers simultaneously request the same driver". Trang tự tạo tài khoản, nên không dùng tài khoản seed.

1. **Chuẩn bị tài khoản và tài xế**: tạo hai hành khách A, B và một tài xế (mật khẩu `Demo@123456`) trong một vùng ngẫu nhiên ở Tây Nguyên, xa các tài xế seed ở Hà Nội. Tài xế được đặt vị trí, bật `AVAILABLE`, và trang kiểm tra vùng đó có đúng một tài xế sẵn sàng.
2. **Gửi đồng thời A + B**: hai `POST /api/trips` được gửi cùng lúc tại cùng điểm đón. Trang đọc lại hai chuyến và tài xế mỗi giây.
   - **ĐẠT** (khung xanh): một chuyến `ASSIGNED` có tài xế, tài xế `BUSY`, chuyến kia chưa có tài xế và tự hủy sau 60 giây tìm kiếm.
   - **LỖI** (khung đỏ): cùng tài xế được gán cho cả hai chuyến.
3. Đối chiếu database bằng lệnh có sẵn trên trang (`reserved_for_trip`, `version` trong `driver_db`), vì kết luận trên trang chỉ dựa vào API.
4. **Dọn dẹp sau kiểm thử**: hủy các chuyến còn mở và chuyển tài xế kiểm thử sang `OFFLINE`, để lần chạy sau không bị tài xế cũ chen vào.

Nhật ký trên trang ghi mọi request ghi dữ liệu kèm Correlation ID để tra trong log. Tải lại trang làm mất phiên kiểm thử đang chạy; khi đó bấm Chuẩn bị lại.

## Kiểm thử tự động

```powershell
node --test scripts/tests/demo-client-test.mjs scripts/tests/ride-core-test.mjs
cd src/api-gateway
.\gradlew.bat test
```
