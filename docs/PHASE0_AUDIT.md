# Phase 0 — Kiểm kê kiến trúc CarTaxiLauncher

- Nguồn: `ThanhTaxiXanh/CarTaxiLauncher` tại commit `2e54d4cbba960dc002180f4cddad278cec956cca` (nhánh `main`).
- Đích: `ThanhTaxiXanh/AALauncherGPT`.
- Đây là kết quả phân tích mã nguồn tĩnh; chưa thay thế kiểm thử trên Android Auto thật.

## 1. Cây mã nguồn

- `app/src/main/java/com/cartaxi/launcher`: `CarService.kt`, `MainActivity.kt`, `NativeMap.kt`, `LauncherRenderer.kt`, `GpsFilter.kt`, `RoadSigns.kt`, `LunarCalendar.kt`, `Events.kt`, `Diag.kt`.
- `app/src/main/assets/verified_signs.geojson`; `app/src/main/res/xml/automotive_app_desc.xml`.
- `app/src/test/java/com/cartaxi/launcher/RoadSignsParsingTest.kt`.
- `.github/workflows/build.yml` và `.github/workflows/femto-test.yml`; Android Gradle, wrapper và manifest.

## 2. Lớp Kotlin hiện có

| Tệp | Vai trò |
|---|---|
| `MainActivity` | Màn hình điện thoại, quyền vị trí, QR, sự kiện |
| `CarService`, `LauncherScreen` | Host Android Auto, `Session`, `Screen`, `SurfaceCallback` |
| `NativeMap`, `MapPresentation`, `ArrowView` | MapLibre, VirtualDisplay, camera, mũi tên 2.5D, tương tác |
| `LauncherRenderer`, `UiState` | Render Dashboard Canvas và hit testing |
| `GpsFilter`, `GpsFix` | Lọc Kalman và hướng xe |
| `RoadSigns`, `SignItem`, `SignsState` | Overpass, speed limits, cảnh báo và cache |
| `LunarCalendar`, `LunarDate` | Tính lịch Việt Nam offline |
| `Events`, `CalEvent`, `EventLine` | Sự kiện và hiển thị ngày |
| `Diag` | Ghi lỗi |

## 3–5. Luồng tổng thể, MainActivity và Android Auto

1. `MainActivity` ghi cấu hình `SharedPreferences("cfg")`, sự kiện trong `events`, ảnh QR tại `filesDir/qr.png`.
2. `CarService.onCreateSession()` tạo `LauncherScreen` với `CarContext` và application context.
3. `LauncherScreen` đăng ký `SurfaceCallback` với `AppManager`, quản lý cập nhật GPS và `NativeMap`.
4. `NativeMap.start()` tạo `VirtualDisplay` + `ImageReader`; `MapPresentation` gắn `MapView` vào màn hình ảo.
5. `onImage()` chụp frame MapLibre dưới dạng Bitmap; `LauncherRenderer.render()` kết hợp frame với lịch, QR, biển báo và các nút, rồi vẽ lên Android Auto Surface.
6. Scroll/fling/scale/click từ SurfaceCallback được chuyển sang `NativeMap` hoặc hit test trong `LauncherRenderer`.

## 6. GPS

`LocationManager.GPS_PROVIDER` cập nhật qua `LauncherScreen.locListener`; tốc độ thô tính km/h ở màn hình, tọa độ đưa vào `NativeMap.onLocation()`. `GpsFilter` dùng bộ lọc Kalman, vệ tinh GNSS và heading để lọc nhiễu. `NativeMap` kiểm tra độ chính xác, bước nhảy bất thường, road snapping, làm mượt camera và mũi tên.

## 7–9. Overpass, giới hạn tốc độ và biển báo

- `RoadSigns.request()` tải dữ liệu Overpass bằng POST trên background executor, có retry tối thiểu 30 giây, vùng truy vấn 900 m và cache JSON cục bộ.
- `parse()` đọc OSM way tags `maxspeed`, `maxspeed:forward`, `maxspeed:backward` và node `traffic_sign`, `highway`, `enforcement`, `direction`.
- `state()` chọn way theo khoảng cách hình học, hướng và tính liên tục, rồi chỉ hiển thị giới hạn explicit. Chưa xử lý `maxspeed:conditional`.
- Biển báo phía trước được chọn theo góc hướng xe/bearing và bán kính; hiện khoảng cách tính đường chim bay, chưa phải dọc tuyến.
- `NativeMap.installSigns()` tạo `GeoJsonSource`, `CircleLayer`, `SymbolLayer` để vẽ nhãn lên bản đồ. `LauncherRenderer.drawSigns()` vẽ thêm danh sách và giới hạn tốc độ trên Dashboard.

## 10. GitHub Actions / APK

- `build.yml` job `verify`: Java 17, Gradle 8.9, `assembleDebug`, `testDebugUnitTest`, kiểm tra certificate lịch sử build-1 và build-71.
- `inspect_signing` chỉ chạy khi người dùng thủ công bật cờ và xác minh PKCS12 secret.
- `publish` chỉ chạy workflow_dispatch với `publish_release=true` trên `main`, có so sánh dấu vân tay SHA-256 trước khi phát hành.
- `femto-test.yml` là workflow thủ công với commit nguồn cố định `ccf16ca...`, không thể giả định hoạt động sau chuyển repository.
- Lần chạy CI thành công của nguồn tại `2e54d4c` được GitHub ghi nhận ngày 20/10/2026. Cần chạy CI riêng trên AALauncherGPT.
- Không sao chép keystore hoặc secrets sang repository đích; không tự phát hành bản release.

## 11. Vấn đề xác định bằng code

1. `LauncherRenderer.render()` chỉ có hai nhánh bố cục tỉ lệ cố định, không có Layout Builder.
2. `LauncherRenderer.trimWhite()` có nguy cơ làm mất QR quiet zone; `Bitmap.createScaledBitmap(..., true)` cũng có thể làm mềm cạnh mã QR.
3. `RoadSigns` chưa xử lý `maxspeed:conditional`, geometry/road segment confidence và khoảng cách theo tuyến.
4. Cache Overpass cũ được tải nhưng `state()` và `nearby()` từ chối dữ liệu stale; offline-first chưa đầy đủ.
5. Menu Android Auto có callback trống trong `LauncherScreen.onGetTemplate()`.
6. `CarService.createHostValidator()` cho phép tất cả host; cần đánh giá an toàn và compatibility trước khi sản xuất.
7. `MainActivity` chỉ lưu một QR; chưa có backup/restore, Media3, report persistence, event reminder.
8. Workflow Femto dùng hardcoded commit của repository cũ và secrets không chuyển tự động.

## 12. Thành phần có thể tái sử dụng

`CarService`/`LauncherScreen`, `NativeMap`, `GpsFilter`, `RoadSigns`, `LunarCalendar`, `Events`, `LauncherRenderer`, `verified_signs.geojson`, Gradle, CI verify và quy trình ký có bảo vệ.

## 13. Thành phần nên refactor

- Tách `NativeMap` thành camera, GPS/matching, style, render và road-sign integration theo từng bước.
- Tách UI layout khỏi `LauncherRenderer` thành một model cây bố cục được lưu trên điện thoại.
- Tách logic speed limit và upstream Overpass khỏi `RoadSigns` nhưng giữ adapter tương thích.
- Chuẩn hóa cấu hình `SharedPreferences` thành repository/DataStore có migration khi cần.
- Chuyển code QR sang file manager giữ nguyên dữ liệu ảnh và quiet zone.

## 14. Các tính năng mới

Traffic Report offline-first; phone configuration center; 8 preset + custom Layout Builder; calendar/event reminders; QR library; MediaSession/Media3; dashboard đa ô trên Android thường; Android Auto dùng template được phép; kiểm thử regression và compatibility matrix.

## 15. Kế hoạch thay đổi file

| Giai đoạn | File/nhóm file | Quy tắc |
|---|---|---|
| 0 | Import dự án nguồn, tài liệu audit | Giữ `applicationId` và mã nguồn nguyên trạng trước kiểm thử |
| 1 | Renderer QR, tests, CI | Chỉnh sửa nhỏ; build/test rồi mới mở rộng |
| 2 | `NativeMap`, camera/GPS classes | Tách trách nhiệm, giữ MapLibre/OpenFreeMap |
| 3 | `RoadSigns` và speed limit model | Chỉ công bố tốc độ explicit có độ tin cậy |
| 4 | `feature/reports`, phone UI | Offline-first; không ghi vào Overpass |
| 5–6 | cấu hình điện thoại, layout tree | Dùng chung state, không tạo Activity theo từng preset |
| 7–8 | `LunarCalendar`, `Events`, QR, Media | Bảo toàn dữ liệu cũ; kiểm thử lịch nhuận/QR |
| 9–10 | `CarService`, custom dashboard | Tôn trọng host template và driver-distraction rules |
| 11 | unit/instrumentation tests, CI, hiệu năng | Không tự release, không thay signer |

## Compatibility sơ bộ

| Chức năng | Android Auto tiêu chuẩn | Custom Dashboard Android |
|---|---|---|
| Map, GPS, tốc độ, biển báo | Đang có render Surface, cần test host | Có thể dùng chung engine |
| Báo cáo giao thông | Chỉ thao tác theo template được hỗ trợ | Form đầy đủ |
| Layout Builder, QR, lịch, Media nhiều ô | Chưa có căn cứ cho vẽ tùy ý | Mục tiêu chính |

**Trạng thái kiểm thử:** chưa có Android SDK trong môi trường phân tích cục bộ; chưa thực hiện build APK tại đây. GitHub Actions của repository đích sẽ là nguồn xác nhận sau khi import.
