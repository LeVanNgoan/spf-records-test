# Cập nhật ShopeeFood Recorder v2.0.6 trên SUNMI

1. Build source bằng GitHub Actions `Build ShopeeFood Order Recorder APK`.
2. Tải artifact `shopeefood-order-recorder-final-v2.0.6`, giải nén lấy `app-release.apk`.
3. **Không uninstall app hiện tại. Không Clear Data/Clear Storage.**
4. Mở `app-release.apk` trên SUNMI và chọn **Cập nhật / Update**.
5. Mở app và xác nhận `v2.0.6`.
6. Kiểm tra quyền Notification Listener, Accessibility và trạng thái Hub.
7. Trong `HỆ THỐNG`, nút `Xuất toàn bộ nhật ký kỹ thuật` phải xuất được file `.txt`.

## Quan trọng về nhật ký
Log v2.0.6 được giữ qua nhiều ngày và qua lần update APK tiếp theo nếu vẫn cài đè cùng app/signature. Uninstall hoặc Clear Data sẽ xóa log nội bộ.
