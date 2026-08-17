# ShopeeFood Recorder — Final v2.0.6

## Vì sao có v2.0.6

v2.0.6 là bản rollback có chủ đích về **automation core v0.2.5** — nhánh đã được kiểm chứng thực tế trên SUNMI với tỷ lệ ghi nhận rất cao. Các thử nghiệm automation phức tạp của v2.0.0/v2.0.1 (worker nhanh, user-idle arbitration, exclusive overlay, rapid polling, parallel retry) không còn điều khiển luồng ghi SĐT.

## Luồng automation v2.0.6

1. Notification ShopeeFood được ghi local và xếp FIFO.
2. Mỗi đơn có **1 giây cho âm báo** trước khi automation mở đơn.
3. Dùng `contentIntent`; nếu không vào đúng đơn thì fallback notification shade như v0.2.5.
4. Xác nhận đúng `Chi tiết đơn hàng` và short ID.
5. Mở `Liên hệ khách hàng`.
6. Chỉ dùng `Khách nhận đơn`.
7. Nếu SĐT hiện trực tiếp trong ShopeeFood thì lưu ngay; nếu OEM mở Dialer thì bắt SĐT ở Dialer.
8. SĐT được khóa vào đúng order ID bằng contact binding trước khi lưu.
9. Lưu local trước; completion notification và Hub sync chạy sau, không chặn capture.
10. Hoàn tất đơn hiện tại rồi mới lấy đơn FIFO tiếp theo.

## Dữ liệu mới vẫn được giữ

- Business code: `75 - #4325` → `SPF-4325`.
- SĐT: `+84912345678` / `84912345678` / `912345678` → `0912345678`.
- Thời gian: Asia/Ho_Chi_Minh.
- Excel: `STT | Mã đơn hàng | SĐT | Thời gian nhận đơn`.
- Hub sync + heartbeat + Force Resync 7 ngày vẫn giữ.
- Dữ liệu local: `orders.json`, retention 7 ngày.

## Update

- applicationId: `vn.orderrecorder.shopee`
- versionCode: `25`
- versionName: `2.0.5`
- dùng cùng keystore nội bộ với các bản trước để có thể cài đè.

**Không uninstall app cũ.** Build `app-release.apk` bằng GitHub Actions và cài Update trực tiếp trên SUNMI.

## Safety fuse v2.0.6 — chống auto click vô hạn

- Mỗi đơn tự động tối đa **3 lần thử**.
- Mỗi lần xử lý tối đa **30 giây**.
- Đơn quá **8 phút** không còn được tự mở lại.
- Khi chạm giới hạn: trạng thái `needs_review`, bị loại khỏi auto queue và **không tự click lại nữa**.
- Khi update từ v2.0.2, queue/state retry cũ được xóa một lần để dừng ngay các đơn mắc kẹt qua đêm.
- Dữ liệu đơn (`orders.json`) và cấu hình Hub không bị xóa.
- Đơn cũ đã `needs_review` vẫn có thể được cứu thủ công: nhân viên tự mở đơn + Liên hệ + Khách nhận đơn; app chỉ ghi số khi người dùng chủ động thao tác, không tự mở lại đơn cũ.


## Persistent Technical Black Box v2.0.6
- Ghi nhật ký kỹ thuật theo ngày, không giới hạn 24 dòng.
- Giữ qua nhiều ngày và qua update APK.
- Xuất toàn bộ lịch sử thành 1 file `.txt` trong mục HỆ THỐNG.
- Logging ghi bất đồng bộ để không chặn hot-path lấy SĐT.
- Hub heartbeat version đã đồng bộ đúng `2.0.6`.
- Core automation burst-safe từ v2.0.5 không thay đổi trong bản logging này.
