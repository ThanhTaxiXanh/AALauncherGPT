# Offline Vietnam OSM in APK — Geofabrik national build

The special GitHub Actions job `bundled_vietnam_osm` runs on pushes to
`main` after all normal checks. It **downloads the current full
`vietnam-latest.osm.pbf` from Geofabrik** on the runner, uses pyosmium
to read actual OSM nodes and roads with `maxspeed*` tags, produces a
spatially indexed SQLite database, compresses it, adds it to Android
**debug** assets, and creates a separate APK artifact called
`AA-Taxi-Driver-Vietnam-OSM-offline-debug`.

Official source: https://download.geofabrik.de/asia/vietnam.html
License: ODbL, © OpenStreetMap contributors.

## Included

- Physical sign-like OSM *nodes*: traffic_sign, speed cameras, red-light
  enforcement, give-way, STOP, selected Vietnam traffic sign codes,
  speed nodes, warning and other explicitly tagged items.
- Speed-tagged motor-road *ways* now store **full OSM LineString geometry**,
  OSM way ID, highway class, road name, layer/bridge/tunnel level, oneway,
  `maxspeed`, directional speeds and directional/normal conditional rules.
  Railway, construction, proposed, path and track objects are excluded from
  speed-road matching. These rows are **road attributes, not roadside signs**.
- `road_speeds_rtree` indexes the way bounding boxes so Android reads only
  a small GPS neighborhood before exact geometry matching.
- Region `vn` and coordinate indices; source counts in
  `assets/osm/vietnam_stats.json` to verify the build extracted real
  data rather than using empty or sample features.

## On-device behavior

`BundledVietnamOsm` transparently decompresses the SQLite asset on
first regional query, verifies SHA-256 and reads it in read-only mode.
Existing user-imported `RegionalOsmStore` and personal sign reports
remain separate. The nationwide OSM sign points are merged with user
regional OSM markers in `NativeMap` with an upper bound per GPS area.

For speed limits, nearby nationwide ways are loaded in a background worker.
`OfflineSpeedLimitResolver` reuses `SpeedWayMatcher` with filtered GPS,
heading and the full way geometry. Fresh live Overpass data wins; the bundled
SQLite is a conservative offline fallback. GPS must be recent and <=12 m
accuracy, known road names must agree, unnamed roads require a very close
geometry match, and ambiguous parallel/crossing roads fail closed.

This is an **APK-bundled offline sign/speed database**, not a complete
offline map basemap (MapLibre's vector style may still require network)
or a complete offline routable road graph. It currently contains only
speed-tagged motor-road ways, so the stricter name/distance gate is required
to reduce false matches to nearby roads without speed tags. OSM coverage
cannot be assumed complete, and physical signs/current law always take
precedence.

## Important current scope

The CI-hosted PBF processing actually runs only when the special
`bundled_vietnam_osm` job completes successfully; standard
`AA-Taxi-Driver-debug` APK has no countrywide bundle. Never
mistake green standard unit tests for successful PBF download/extraction.

Downloading a 300+ MB PBF and processing national way references
takes significant runner resources; download errors, no speed ways,
no signs or an oversized generated asset fail the job. The data are
not committed to Git, and no supplier other than OSM/Geofabrik is
used. Generated counts and download checksum are only trustworthy
after a successful workflow.

Android Auto remains debug-only per Phase 11A/11B. CPU/RAM/latency
device profiling for GPS matching and road graph is still deferred.
