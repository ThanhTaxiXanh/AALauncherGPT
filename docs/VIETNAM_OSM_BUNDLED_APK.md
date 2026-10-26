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
- V3 stores every supported motor-road way that has a speed tag, plus a
  **~100 m road-context halo** of nearby supported roads without speed tags.
  This retains frontage/service/parallel competitors where speed leakage is
  possible without embedding the entire national road network. Every stored
  row has full OSM LineString geometry, way ID, highway class, road name,
  level and oneway. No limit is inferred from highway class.
- `road_context_rtree` indexes the compact halo bundle. Context-only roads
  can win GPS matching but can never produce a speed limit.
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
the speed-tagged road and its nearby context competitors. A speed is shown
only if the selected way itself contains a supported explicit OSM
speed/conditional rule. Fresh live Overpass data still wins. GPS must be
recent and <=12 m accuracy, known road names must agree, unnamed roads require
a very close geometry match, and ambiguous parallel/crossing roads fail closed.

This is an **APK-bundled offline sign/speed database**, not a complete
offline map basemap (MapLibre's vector style may still require network)
or a complete offline routable road graph. A first whole-network V3 prototype
was rejected because it produced ~2.78 million road ways and a ~1.49 GB
SQLite; the compact halo design keeps only context relevant to speed matching.
It still does not infer
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


## V3.1 size control

An attempted full-national context build on 2026-10-26 produced about 2.78
million road ways, 29.47 million geometry points and a 1.485 GB SQLite file.
That design was rejected for APK distribution. V3.1 uses a two-pass corridor:
first index the cells around explicit speed-tagged motor roads; then retain
only speed ways plus motor roads intersecting those cells. This keeps the
safety property needed for parallel-road matching without embedding unrelated
roads hundreds of kilometres away.
