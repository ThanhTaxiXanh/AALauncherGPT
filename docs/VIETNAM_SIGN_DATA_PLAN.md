# Vietnam traffic-sign data: licensed-source plan and current support

## Verified candidate sources (2026-10)

1. OpenStreetMap Vietnam extract (Geofabrik): https://download.geofabrik.de/asia/vietnam.html
   — ODbL, attribution required; download complete road geometry, directional maxspeed,
   traffic-sign nodes, enforcement relations. Coverage and completeness vary.
2. VIETMAP commercial Maps API: https://maps.vietmap.vn/docs/map-api/overview/
   — vendor advertises 600,000+ km roads and 200,000+ signs; requires approved
   account, pricing and API/redistribution rights. **Do not scrape Vietmap Live.**
3. Third-party aggregations on GitHub: some claim thousands of cameras and signs,
   but may mix Waze/commercial sources with unclear redistribution rights.
   **Do not bundle into the application until each item's provenance is licensed.**

The targets 300,000 km road network / 5,000 cameras / 15,000 signs are
**coverage goals, not downloaded or verified counts**.

## Current implementation

The existing RoadSigns service requests OpenStreetMap tags through Overpass in
small GPS-centered regions, with spatial matching, conditional speeds, stale-cache
policy and direction restrictions. NativeMap already uses GeoJSON MapLibre layers to
draw nearby sign circles with labels directly on the map (zoom-dependent size).
This increment additionally differentiates OSM enforcement=traffic_signals cameras,
Vietnam-style no-entry/no-parking/school/curve identifiers and other warning types.
Generic man_made=surveillance is deliberately NOT labeled as a traffic-enforcement
camera. Lack of maxspeed never implies a known legal limit.

For national offline coverage, design a **separate data pipeline**:
Geofabrik PBF -> Osmium filtering of drivable ways/signs/cameras and turn restrictions
-> license/source verification -> spatial tile-indexed SQLite/MBTiles bundle ->
signed incremental regional updates -> MapLibre feature loading within map viewport.
Record OSM IDs, provenance, timestamp and direction for each entity. Avoid
putting hundreds of thousands of features into one GeoJSON string, fetching
the whole country from public Overpass instances, or loading all records on
Android main thread. Camera coordinates must be verified before release alerts.

## Safety and licensing

Displayed signs are informational and can be incomplete, stale or belong to a
parallel road. Always follow the road's physical signs. MapLibre attribution
for OSM must remain visible, including in screenshots/VirtualDisplay.
No GPS matching or road-graph device-performance benchmark is run here.
