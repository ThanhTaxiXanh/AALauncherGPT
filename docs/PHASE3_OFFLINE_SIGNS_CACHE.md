# Phase 3 — Cache biển báo offline

## Chức năng

- `OfflineSignCache` lưu phản hồi Overpass dưới dạng file phiên bản V2,
  có thời gian lưu (Unix milliseconds), tọa độ truy vấn và SHA-256 để
  phát hiện dữ liệu hỏng/ghi dở. File được ghi qua file tạm và thay thế
  atomically khi hệ thống file hỗ trợ.
- Đọc được cache cũ `lat,lon\\nJSON` dựa trên ngày sửa file. Cache cũ
  **không** được dùng làm giới hạn tốc độ đang có hiệu lực.
- Ký hiệu biển OSM offline hiển thị dấu `?` màu xám và tên cho biết
  `OSM lưu offline (chưa xác minh)`. Thời gian hiển thị tối đa: **7 ngày**
  kể từ lúc lưu; chỉ hiển thị trong phạm vi **900 m** từ trung tâm tải dữ liệu.
- Dữ liệu OSM lấy trực tiếp có thể tạo cảnh báo biển báo và giới hạn tốc độ
  **chỉ trong tối đa 15 phút** với kiểm tra đường và GPS hiện tại.
  Sau 15 phút, biển OSM chuyển sang *gợi ý tham khảo* và giới hạn tốc độ
  trả về `null`. Khởi động lại ứng dụng **không gia hạn tính mới**.
- Dữ liệu `verified_signs.geojson` đã có `verified=true` và `source`
  hợp lệ được duy trì độc lập, kể cả khi mất mạng. Asset hiện tại có thể
  vẫn chưa có mục nào; không tự tạo dữ liệu giả.
- Chỉ khi có mạng và tải thành công một phản hồi Overpass mới thì cache
  và trạng thái trực tiếp được cập nhật. Không gửi dữ liệu hoặc tọa độ
  người dùng đến máy chủ báo cáo nào ngoài các truy vấn Overpass cũ.

## An toàn và giới hạn

SHA-256 là kiểm tra tính toàn vẹn của file, **không xác nhận nguồn gốc hay
độ đúng của biển báo**. Không khôi phục giới hạn tốc độ pháp lý từ dữ liệu
hết hạn. Dấu `?` chỉ là vị trí lịch sử có thể đã thay đổi; không dùng
để ra quyết định khi lái xe.

Chưa có cache đa vùng, tải bản đồ offline/tile, khoảng cách biển báo theo
road graph hoặc xử lý điều kiện phương tiện. Cần kiểm thử thực địa cùng
thiết bị Android Auto.

## Kiểm thử

Unit tests dùng file tạm, ngày giờ cố định, checksum bị sửa, file cache
cũ và quá hạn, đồng thời xác nhận cache từ đĩa không bao giờ được coi là
`LIVE` dù mới ghi.
