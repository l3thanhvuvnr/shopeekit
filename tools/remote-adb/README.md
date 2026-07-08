# Remote ADB cho ShopeeKit (qua Tailscale)

Mục tiêu: ở nhà remote (RDP/AnyDesk) vào **PC office** để code, vẫn **xem log + cài build/debug** lên điện thoại test (POCO F3, không SIM, dùng hotspot 4G) đang ở nhà.

## Kiến trúc

- **Tailscale** dựng một LAN ảo mã hoá giữa PC office và điện thoại → `adb` chạy qua Internet như cắm dây.
- Build vẫn compile **trên PC office**, đẩy **thẳng** tới điện thoại qua đường hầm Tailscale (PC cá nhân ở nhà chỉ là màn hình từ xa, không nằm trong đường ADB).
- Vì cả hai sau NAT (office + 4G-CGNAT), traffic đi qua **DERP relay Singapore** (~150–400ms). Đủ cho logcat/shell/install (APK ~9MB ≈ 24s).

## Thông số đã cấu hình

| Thứ | Giá trị |
|---|---|
| Tailnet | `l3thanhvuvnr.github` (tài khoản GitHub cá nhân — KHÔNG dùng `vnresource.vn` vì ACL công ty chặn peer) |
| PC office | `100.72.197.121` (node `vulethanh`) |
| Điện thoại | `100.119.206.31` (node `poco-f3`) |
| Cổng wireless ADB | `5555` |

## Quy trình dùng hằng ngày

1. **Ở office, trước khi về:** cắm điện thoại USB vào PC office, chạy `rearm-phone.bat` (arm `adb tcpip 5555`).
2. **Ở nhà:** remote vào PC office → chạy `connect-phone.bat` → điện thoại hiện trong `adb devices` và Android Studio → Run/Debug/Install bình thường.
3. Xem log realtime: `adb -s 100.119.206.31:5555 logcat -s ShopeeKit:*` (KitLogger bridge sang logcat tag `ShopeeKit/<tag>`), hoặc mở LogViewer on-device qua scrcpy.
4. Xem/điều khiển màn hình điện thoại từ xa: chạy `screen-phone.bat` (scrcpy). Điều khiển cần **"USB debugging (Security settings)" ON** trên MIUI. Qua relay video có độ trễ — hợp quan sát Shopee UI + tap chậm, không hợp thao tác nhanh.

## BẮT BUỘC làm trên điện thoại để chạy bền (nếu bỏ qua sẽ hay bị kẹt)

1. **Always-on VPN cho Tailscale**: Cài đặt → Kết nối & chia sẻ → VPN → Tailscale (⚙) → **Always-on VPN: ON**. → giúp Tailscale tự nối lại, không kẹt "logged out" khi đổi mạng / màn hình tắt.
2. **Pin: Không giới hạn** cho Tailscale (Cài đặt → Pin → Tailscale).
3. **Autostart: ON** cho Tailscale (app Bảo mật → Tự khởi động).
4. **USB debugging (Security settings): ON** trong Developer options (cần tài khoản Xiaomi) → cho phép cài APK từ xa + điều khiển màn hình (scrcpy) không bị chặn.

## Sự cố thường gặp

- **`connect-phone.bat` ping fail / adb connect timeout:** app Tailscale trên điện thoại bị kẹt trạng thái. → Mở app, **tắt/bật lại nút Connect** (hoặc Always-on VPN đã bật thì hiếm khi gặp). Kiểm chứng: `tailscale ping 100.119.206.31` phải ra `pong ... via DERP`.
- **adb connect fail sau khi điện thoại reboot:** reboot xoá `tcpip 5555`. → Cắm USB vào 1 PC, chạy `rearm-phone.bat`.
- **Install báo `INSTALL_FAILED_VERSION_DOWNGRADE`:** APK trên đĩa cũ hơn bản đang cài. Bình thường Run bản mới từ Android Studio là hết; hoặc thêm `-d` khi `adb install` (cẩn thận chữ ký debug/release khác nhau).
- **Chậm khi cài APK lớn:** đang đi DERP relay. Tailscale có thể tự nâng lên kết nối trực tiếp theo thời gian; nếu không, relay vẫn dùng được.
