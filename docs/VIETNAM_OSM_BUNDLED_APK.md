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
- V3 stores **all supported motor-road ways**, including roads with no
  `maxspeed`. Every row has full OSM LineString geometry, way ID, highway
  class, road name, layer/bridge/tunnel level and oneway. Speed fields remain
  empty/zero when OSM does not provide them; no limit is inferred from road
  class. Railway, construction, proposed, path and track objects are excluded.
- `road_context_rtree` indexes all supported road bounding boxes. Roads with
  no speed are context competitors for GPS matching, preventing a nearby
  speed-tagged road from lending its limit to the current road.
- Region `vn` and coordinate indices; source counts in
  `assets/osm/vietnam_stats.json` to verify the build extracted real
  data rather than using empty or sample features.

## On-device behavior

`BundledVietnamOsm` transparently decompresses the SQLite asset on
first regional query, verifies SHA-256 and reads it in read-only mode.
Existing user-imported `RegionalOsmStore` and personal sign reports
remain separate. The nationwide OSM sign points are merged with user
regional OSM markers in `NativeMap` with an upper bound per GPS area.

For speed limits, V3 loads a small R-Tree road-context neighborhood in a
background worker. `OfflineSpeedLimitResolver` matches GPS + heading against
**all nearby supported roads**, not only roads that have a speed tag. A speed
is shown only if the selected way itself contains a supported explicit OSM
speed/conditional rule. Fresh live Overpass data still wins. GPS must be
recent and <=12 m accuracy, known road names must agree, unnamed roads require
a very close geometry match, and ambiguous parallel/crossing roads fail closed.

This is an **APK-bundled offline sign/speed database**, not a complete
offline map basemap (MapLibre's vector style may still require network)
or a complete offline routable road graph. V3 includes road context even when
speed is unknown specifically to reduce false matches. It still does not infer
a legal limit from `highway=*`, and unsupported/symbolic speed conditions
remain unknown. OSM coverage cannot be assumed complete, and physical
signs/current law always take precedence.

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
