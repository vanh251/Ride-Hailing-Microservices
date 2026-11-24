# Thanh toán payOS sau chuyến đi

Giao diện khách hàng đặt chuyến với `paymentProvider: PAYOS`. Hệ thống ghép xe và tính giá cuối chuyến như trước. Sau khi tài xế hoàn thành, khách mở trang thanh toán payOS; chỉ xác nhận từ backend mới chuyển chuyến sang `PAID`. Không có lựa chọn trả trước.

Các client API cũ không gửi `paymentProvider` tiếp tục dùng `MOCK` để các kịch bản kiểm thử hiện có không thực hiện chuyển tiền thật. `MOCK` và `PAYOS` được lưu riêng theo chuyến; không tự chuyển sang simulator khi payOS thiếu cấu hình.

## Cấu hình kênh thật

Tạo và xác thực kênh trên payOS theo [tài liệu chính thức](https://payos.vn/docs/). Trong `.env` của máy triển khai, điền các giá trị từ kênh của bạn, không gửi khóa trong chat hoặc commit Git:

```dotenv
PAYOS_CLIENT_ID=
PAYOS_API_KEY=
PAYOS_CHECKSUM_KEY=
PAYOS_RETURN_URL=https://your-domain/demo/rider.html
PAYOS_CANCEL_URL=https://your-domain/demo/rider.html
```

Đăng ký webhook của kênh là `https://your-domain/api/payments/webhooks/payos`. Gateway chuyển đúng POST này vào Payment Service; webhook không dùng JWT của khách nhưng phải qua kiểm tra chữ ký payOS. URL công khai phải đi được từ payOS tới Gateway. Không mở endpoint tạo checkout cho người chưa đăng nhập.

Chỉ sau khi cấu hình kênh, khởi động lại Payment Service để nạp khóa. Khi chưa cấu hình, khách vẫn thấy khoản cần thanh toán; bấm thanh toán nhận thông báo chưa sẵn sàng, không ghi nhận đã trả tiền.

## Triển khai

Áp dụng hai migration dưới đây vào đúng database bằng một transaction (`psql -v ON_ERROR_STOP=1 --single-transaction`), trước khi chạy phiên bản mới:

- `scripts/db/2026-11-24-payos-trip.sql` → `trip_db`.
- `scripts/db/2026-11-24-payos-payment.sql` → `payment_db`.

Sau đó build và khởi động lại `trip-service`, `payment-service`, `api-gateway`. Không xóa volume database để cập nhật schema. Các chuyến cũ tiếp tục là MOCK.

## API và trạng thái

| API | Quyền và hành vi |
|---|---|
| `POST /api/payments/{tripId}/checkout` | Chủ chuyến dùng bearer PASSENGER; chỉ sau COMPLETED. Không gửi amount, passengerId hoặc callback URL từ frontend. |
| `GET /api/payments/{tripId}/checkout` | Chủ chuyến hoặc tài xế được ghép xem trạng thái; Payment Service tự kiểm tra JWT và quyền với dữ liệu Trip. |
| `POST /api/payments/webhooks/payos` | payOS gửi dữ liệu có chữ ký. Xác minh và đối chiếu trước khi ghi tiền. |

`PENDING` là đang chờ; `SUCCESS` là đã xác nhận tiền. Link `EXPIRED`/`CANCELLED` có thể tạo lại sau khi backend đối soát. `REVIEW_REQUIRED` cần xử lý đối soát, giao diện không đề nghị trả thêm. Giao dịch lặp không ghi ledger hoặc phát `PaymentSucceeded` hai lần.

URL quay về và thao tác đóng/hủy trang thanh toán không xác nhận đã trả tiền hoặc xóa nợ. Nếu khách đã chuyển tiền nhưng chưa thấy kết quả, dùng **Kiểm tra thanh toán** và chờ đối soát; không chuyển tiền thêm. Backend có tác vụ truy vấn provider để phục hồi khi webhook thất lạc.

Hoàn tiền thật và chuyển tiền cho tài xế chưa nằm trong bản này. API hoàn tiền giả lập phải từ chối khoản PAYOS để tránh ghi nhận hoàn tiền khi ngân hàng chưa hoàn.

## Kiểm tra

- Java: chạy `gradlew.bat test` trong từng thư mục `src/trip-service`, `src/payment-service`, `src/api-gateway`.
- Browser logic: từ gốc chạy `node --test scripts/tests/*test.mjs`.
- UI: phục vụ `src/api-gateway/src/main/resources/static` tại localhost:8765, chạy `scripts/tests/payos-ui-test.cjs` bằng Playwright MCP `browser_run_code_unsafe` với `filename` tuyệt đối. Test chặn trang checkout bằng fixture, không chuyển tiền thật.
- Provider: kiểm tra SDK với HTTP stand-in, chữ ký đúng/sai, số tiền lệch, webhook trùng, timeout sau khi tạo link và đối soát. Kết quả với fixture không chứng minh kênh ngân hàng thật đã hoạt động.

Kích hoạt thật cần kiểm tra thêm một giao dịch có kiểm soát trên kênh đã cấu hình, bao gồm webhook tới URL công khai và đối chiếu số tiền trong tài khoản nhận. Không coi việc mở được trang payOS là đã tích hợp thanh toán thành công.
