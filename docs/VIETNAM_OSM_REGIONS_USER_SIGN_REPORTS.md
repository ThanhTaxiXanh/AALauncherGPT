# Việt Nam — Cơ sở dữ liệu OSM theo vùng và báo cáo biển báo cá nhân (V1)

## Nguồn và giới hạn

- Nguồn mở: bản trích xuất OSM Việt Nam tại
  https://download.geofabrik.de/asia/vietnam.html ; bản quyền ODbL,
  giữ thông tin © OpenStreetMap contributors.
- Không sao chép VietMap Live / Gofa hay danh sách camera không rõ bản quyền.
- Không tự khẳng định mục tiêu 300.000 km đường, 5.000 camera hay
  15.000 biển báo đã được tích hợp: chất lượng phụ thuộc đóng góp OSM.
- Mã hiện vẫn sử dụng Overpass và các bộ lọc RoadGraph của Phase 3
  cho đường, giới hạn tốc độ và cảnh báo theo hướng xe; V1 SQLite dùng
  cho **điểm biển báo/camera offline**. Đây chưa phải full nationwide
  routable graph hay bản offline của toàn bộ nền bản đồ.
  
## Cách chuẩn bị vùng OSM thực

Tải `vietnam-latest.osm.pbf` từ Geofabrik về máy tính được quyền sử dụng,
cài Python + `pip install osmium`, rồi tạo các gói biển báo theo bounding box:

```bash
python scripts/osm_pbf_sign_regions.py vietnam-latest.osm.pbf \
  --bbox 105.6,20.9,106.4,21.5 \
  --region bac_ninh --out-dir generated/vietnam
```

Script quét dữ liệu OSM bằng luồng, chỉ xuất node được gắn thẻ
`traffic_sign`, `maxspeed`, `highway=stop/give_way/speed_camera`,
`enforcement`, `traffic_calming`. Nó chia từng file 4.000 bản ghi,
mỗi file có tên `bac_ninh_000.geojson`, v.v. Nếu file >8 MiB
hãy tạo lại với `--max-features 1000`. Chỉ số liệu OSM có thật mới được
xuất; không đoán số camera hay đoạn tốc độ không gắn dữ liệu.

Trên điện thoại: **Trung tâm cấu hình → Dữ liệu OSM Việt Nam theo vùng →
Nhập vùng OSM GeoJSON**. Chọn từng file và nhập đúng mã vùng
`bac_ninh_000` tương ứng. SQLite `vietnam_osm_regions_v1.db`
tạo chỉ mục theo tọa độ, nhập theo transaction; thay lại một vùng không
xóa vùng khác. Bộ truy vấn gần GPS giới hạn tối đa 120 điểm trong 2 km,
không nạp toàn bộ dữ liệu quốc gia vào RAM. Cơ sở dữ liệu được cập nhật
thủ công và tách khỏi các file Overpass cache cũ.

## Báo cáo biển báo của người dùng

Trong **Báo cáo giao thông → Tạo báo cáo**, chọn một trong các
loại biển tốc độ, hết tốc độ, STOP, nhường đường, cấm đi vào/cấm vượt/
cấm đỗ, gần trường, camera tốc độ, camera đèn đỏ và cảnh báo khác.
Khi chọn biển tốc độ, nhập giá trị km/h hợp lệ. GPS đính kèm là
**tùy chọn có đồng ý riêng** và chỉ chấp nhận GPS gần đây, chính xác
trong 50 m. Báo cáo không GPS sẽ không có marker.

Trong Trung tâm cấu hình bật **Hiển thị báo cáo cá nhân trên bản đồ**.
Mỗi báo cáo hiện dưới marker `CN?`, ví dụ `CN?80` hoặc `CN?CAM`,
không thể bị hiểu là biển báo đã được xác minh. Báo cáo không sửa
biển giới hạn tốc độ thật, không kích hoạt tự động cảnh báo pháp lý.
Dữ liệu chỉ lưu trên thiết bị, có sửa/xóa, xóa hàng loạt GPS, xuất
JSON mặc định bỏ tọa độ; không có đồng bộ hoặc chia sẻ cộng đồng.

`NativeMap` dùng chung cho Dashboard điện thoại và bản Android Auto
**debug**, nên các lớp điểm biển và báo cáo xuất hiện trên cả hai nếu
có GPS và bật quyền riêng tư. Các ô QR/Lịch trên Android Auto vẫn chỉ
là thử nghiệm không phù hợp NF-2 và không có trong release.

## Việc cần làm tiếp

- Tạo và phân phối hợp pháp những gói PBF đã chuẩn hóa cho từng tỉnh;
  hiện repository chỉ chứa công cụ chuyển đổi, **không chứa dữ liệu
  hàng nghìn camera/biển báo có tọa độ được xác minh**.
- Kiểm tra chiều đường, làn song hành, hiệu lực theo thời gian và
  đối chiếu biển ngoài thực tế, trước khi cảnh báo bằng giọng nói.
- Thiết kế nguồn cộng đồng có kiểm duyệt/quyền đồng ý nếu muốn
  chia sẻ báo cáo giữa thiết bị; không đồng bộ ngầm GPS.
- Bài kiểm thử thiết bị Android/DHU trong Phase 11B và phần đo hiệu năng
  GPS matching/road graph vẫn hoãn theo yêu cầu.
