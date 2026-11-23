# Giao diện demo Ride Lab

Giao diện chạy ngay trong API Gateway tại `/demo/index.html`. Không cần Node, frontend server, tài khoản dịch vụ ngoài hoặc CORS. Các thao tác gọi API thật với JWT riêng cho từng phiên.

## Khởi động

Với stack đã nâng cấp schema tuần 5:

```powershell
docker compose up -d --build --wait
```

Mở `http://localhost:8080/demo/index.html`. Nếu database đang chứa dữ liệu tuần 3, thực hiện quy trình cutover và migration trong `docs/operations.md` trước; không xóa volume để nâng cấp.

Trong lần triển khai này, stack nghiệm thu riêng đang chạy tại `http://localhost:18080/demo/index.html`; Grafana tại `http://localhost:13000`, Prometheus tại `http://localhost:19090`. Dữ liệu cũ của stack chính được giữ nguyên. Ở **Trung tâm demo → Điều hành & quan sát**, đặt tiền tố Compose của stack nghiệm thu là:

```text
docker compose -p ds03-acceptance-20261123 -f build/acceptance/compose.json
```

Chạy lệnh copy từ thư mục gốc dự án. Với cổng Gateway khác 8080, giao diện yêu cầu cấu hình tiền tố trước khi copy để tránh thao tác nhầm stack.

## Trình diễn

1. Bấm **Chuẩn bị demo mới**: tạo hai Passenger và một Driver tại vùng đón riêng. Mật khẩu hồ sơ mới là `Demo@123456`; email được hiển thị trong phiên. Không tạo demo mới khi còn chuyến hoạt động.
2. Sang **Tài xế**, cập nhật vị trí gần điểm đón, rồi bật **AVAILABLE**.
3. Sang **Hành khách**, chọn A, ước tính giá rồi đặt chuyến. Quan sát trạng thái và Trip/Correlation ID.
4. Sang **Tài xế**, chọn đúng chuyến A/B; nhận, bắt đầu và hoàn thành. Chờ `PAID`, payment `SUCCESS` và tài xế `AVAILABLE`. API nhận chuyến không có trạng thái `ACCEPTED` riêng.
5. Để demo concurrency, khi A/B không còn chuyến hoạt động, vào **Trung tâm demo** và gửi đồng thời A+B. Giao diện kiểm tra có đúng một tài xế demo AVAILABLE trong vùng. Chỉ một chuyến được gán; hủy các chuyến sau khi đối chiếu.
6. Để demo lỗi, dừng Payment khi một chuyến `IN_PROGRESS`, rồi hoàn thành chuyến. Trip giữ `COMPLETED`. Dùng lệnh Kafka trong runbook để đọc broker lag; start Payment rồi quan sát cùng Trip ID chuyển `PAID`.
7. Để demo compensation, hủy chuyến `ASSIGNED`: Trip `CANCELLED`, Driver trở về `AVAILABLE`. Đối chiếu `saga_log` trong DB nếu cần nghiệm thu bền vững.

Runbook đủ 9 kịch bản nằm trong **Trung tâm demo**. Start/stop service thực hiện bằng Docker Desktop hoặc lệnh copy; giao diện không có quyền điều khiển Docker. Grafana health cho thấy service down; metric Kafka do ứng dụng xuất sẽ không cập nhật khi chính ứng dụng đó dừng. Dùng `kafka-consumer-groups` cho lag trong thời gian downtime.

## Trạng thái và phiên

- Poll mỗi 2 giây, không chồng lấn với thao tác cập nhật. Giữ phản hồi cuối cùng và đánh dấu dữ liệu cũ khi không đọc lại được.
- JWT chỉ lưu trong `sessionStorage`, không lưu mật khẩu. Đăng nhập lại cùng tài khoản giữ các chuyến đang theo dõi; không đổi tài khoản khi còn chuyến hoạt động.
- Khi POST đặt chuyến mất phản hồi hoặc trả 5xx, không tự gửi lại. Correlation ID được giữ và khóa đặt mới. Tra cứu Trip ID theo correlation trong DB, rồi dùng **Khôi phục theo dõi bằng Trip ID**. Nếu xác minh yêu cầu chưa tạo chuyến, có thể xóa phiên cục bộ rồi đăng nhập lại. Xóa phiên không xóa tài khoản, chuyến hay reservation ở backend.
- Nhật ký xuất ra là quan sát HTTP/trạng thái API, không phải Kafka event log hoặc Saga log. Không chứa JWT/mật khẩu. Kết luận concurrency từ API cần đối chiếu `reserved_for_trip` và `version` trong DB khi nghiệm thu.

## Kiểm thử

```powershell
node --test scripts/tests/demo-client-test.mjs
cd src/api-gateway
.\gradlew.bat test bootJar --offline --no-daemon
```

Kiểm thử event handler của app với API thật (tạo tài khoản/chuyến test) từ thư mục gốc:

```powershell
$env:DEMO_GATEWAY_URL='http://localhost:18080'
$env:DEMO_COMPOSE_PROJECT='ds03-acceptance-20261123'
$env:DEMO_COMPOSE_FILE=(Resolve-Path build/acceptance/compose.json).Path
node scripts/tests/demo-web-e2e.mjs
```

Biến Compose là tùy chọn; khi cung cấp, test dừng/restart Payment của stack được chỉ định. DOM fixture tối giản chạy chính event handler và fetch thật; không thay thế kiểm thử layout, bàn phím hoặc rendering trên trình duyệt. Lần triển khai này chưa xác minh trực quan vì công cụ browser không khả dụng và quyền điều khiển Chrome bị từ chối.
