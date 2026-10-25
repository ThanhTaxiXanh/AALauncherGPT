# Phase 3: multi-region Overpass sign archive and query coalescing

## Storage

`MultiRegionSignCache` preserves the existing `OfflineSignCache`
checksummed/time-stamped format, and also reads the historical single-file
`signs_cache.json` as an unverified hint. One cache file is maintained per
~0.006° lat/lon bucket. The archive is bounded to **12 files**, each already
limited to **4 MB**, with expiry after **7 days**. Expired, corrupt and old
files are pruned on worker access. A maximum of **four nearby snapshots**
within **900 m** are loaded in the worker, not in the render loop.

The loaded snapshots expose archived OSM nodes only, rendered as neutral grey
`?` hints. Supplemental verified GeoJSON has precedence and remains
available offline, and a fresh Overpass response supersedes overlapping
archived hints. Adjacent regions are deduplicated by proximity.

## Query coalescing

`OverpassRequestGate` permits only one Overpass request at a time;
prevents repeat requests within **350 m** of a fresh successful query for
15 minutes; enforces a **30-second** global minimum start interval; and
applies per-neighborhood exponential failure retry from 30 seconds to a
five-minute cap. It has a bounded retry history and uses Android's monotonic
clock, so device wall-clock changes cannot cause a retry storm.

The gate has only in-memory state. After restart it can query once more:
disk cache is not trusted as a live speed-limit source.

## Road-safety constraints

Offline responses never provide `SignsState.limit`, a matched OSM way or
live upcoming warnings. Only data from a recent successful Overpass response
is eligible for road matching and speed limits. The offline archive is
informational; a grey `?` is not a confirmed road sign. The map still needs
internet for live Overpass lookups and map tiles.

## Validation

Unit tests cover multiple regions, geographic bucket replacement, eviction,
expired/corrupt files, legacy compatibility, overlapping requests, recent
success deduplication, global throttling, GPS drift and retry escalation.
Real vehicle GPS and Android Auto hardware tests are not included.
