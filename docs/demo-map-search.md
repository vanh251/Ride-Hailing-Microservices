# Tìm địa điểm trên giao diện đặt xe

- Khách hàng: mở `/demo/rider.html`, nhập tên/địa chỉ vào điểm đón hoặc điểm đến, bấm **Tìm** (hoặc Enter), rồi chọn một kết quả. Vẫn có thể chọn bằng cách chạm bản đồ, kéo ghim hoặc dùng địa điểm gợi ý.
- Nút **Vị trí của tôi** xin quyền GPS một lần mỗi lần bấm. Ở trang khách hàng, điểm xanh biểu thị GPS và được dùng làm điểm đón nếu chưa có lựa chọn mới hơn. Ở trang tài xế, nút cập nhật vị trí xe qua API hiện có. Cần HTTPS hoặc localhost và quyền truy cập vị trí.
- Xe gần nhất được đánh dấu bằng viền xanh; khoảng cách hiển thị là đường chim bay từ điểm đón. Danh sách tải khi chọn điểm đón hoặc bấm **Cập nhật xe gần đây**. Hệ thống vẫn tự ghép chuyến, nên tài xế được ghép có thể khác nếu trạng thái xe thay đổi sau khi tải danh sách.
- Tài xế: mở **Tìm điểm đi / điểm đến** để tra cứu. Các ghim tra cứu không sửa vị trí xe hoặc hành trình đã ghép. **Xóa điểm tra cứu** xóa các ghim này. Điểm đón của khách được ghép và khoảng cách tới điểm đón hiển thị trong chuyến.
- Không theo dõi vị trí tài xế theo thời gian thực. Chỉ trạng thái chuyến và hồ sơ của tài xế trên trang của chính họ tiếp tục dùng vòng cập nhật hiện có.

Tìm địa điểm dùng [Photon](https://github.com/komoot/photon#demo-server) qua Internet, chỉ khi người dùng bấm tìm; không gửi token đăng nhập. Điểm thay thế bằng click bản đồ vẫn dùng được khi dịch vụ tìm kiếm không phản hồi. Endpoint demo nằm ở `GEOCODER_URL` trong `geo.mjs`; dùng endpoint riêng/dịch vụ có cam kết khi có lưu lượng sản xuất.

## Kiểm tra

Từ thư mục gốc: `node --test scripts/tests/*test.mjs`.

Để chạy kiểm tra giao diện, phục vụ thư mục static bằng `python -m http.server 8765 --bind 127.0.0.1 --directory src/api-gateway/src/main/resources/static`. Chạy từng file `scripts/tests/location-ui-test.cjs` và `scripts/tests/location-races-test.cjs` qua Playwright MCP `browser_run_code_unsafe` với trường `filename` là đường dẫn tuyệt đối. Mỗi file tạo browser context riêng, dùng Leaflet thật và giả lập các HTTP API để kiểm tra có thể lặp lại mà không tạo chuyến thật.

Nếu chạy qua Docker: `docker compose up -d --build --no-deps api-gateway`, rồi tải lại trang bằng Ctrl+F5.
