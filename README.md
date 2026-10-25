# AA Taxi Driver

Ứng dụng Android/Android Auto mã nguồn Kotlin. **Tên ứng dụng:** AA Taxi Driver. **applicationId và namespace:** `Thanh.TaxiDriver.AALauncher`.

Mã nguồn được tách theo module chức năng dưới `app/src/main/java/Thanh/TaxiDriver/AALauncher/`:

- `car`: CarService, vòng đời Surface và menu Android Auto
- `dashboard`: Dashboard, preset, Layout Builder và màn hình điện thoại
- `ui`: Trung tâm cấu hình MainActivity
- `map`, `gps`, `road`, `signs`: MapLibre, GPS, road graph, biển báo
- `calendar`, `reports`, `qr`, `media`: Lịch, báo cáo, thư viện QR và Media3
- `core`: cơ sở dùng chung (chẩn đoán)

**Lưu ý về dữ liệu khi đổi ID:** `com.cartaxi.launcher` và `Thanh.TaxiDriver.AALauncher` là **hai ứng dụng khác nhau** theo Android. Ứng dụng mới sẽ không cập nhật đè ứng dụng cũ; sự kiện, cài đặt, báo cáo và QR nội bộ của ứng dụng cũ sẽ không xuất hiện tự động. Không xóa ứng dụng cũ trước khi xuất/sao lưu dữ liệu quan trọng. Xem `docs/PRE_PHASE11_REPACKAGE.md`.

Chạy kiểm thử: `gradle :app:assembleDebug :app:testDebugUnitTest`. Kiểm tra chất lượng và Android Auto trong Phase 11 vẫn cần thực hiện, bao gồm các vấn đề tuân thủ Navigation đã ghi trong báo cáo audit.
