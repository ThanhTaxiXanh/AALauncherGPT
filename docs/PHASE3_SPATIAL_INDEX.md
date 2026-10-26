# Phase 3 — Local spatial indexing (dense OSM)

## Implemented

`RoadSpatialIndex` uses an immutable 64 m local metric grid of road
segments and sign nodes, and a hash lookup of OSM node ID to way membership.
It is constructed once from a successful Overpass response, on the existing
worker thread, alongside `OsmRoadGraph`.

`SpeedWayMatcher` evaluates only candidate road segments near filtered GPS
(the exact distance, heading and competing-road checks are unchanged).
`RoadGraphUpcomingSigns` and the single-way fallback search nearby signs,
query competing segments and OSM node membership through this index.

The index is deliberately bounded to a compact region (±3 km from the
first node, latitude within ±65°, away from the antimeridian), up to
240,000 cell insertions and at most 128 cells per segment dimension.
Queries include a 16 m safety margin, then use the *existing exact*
road-distance rules. For unsupported regions, malformed geometry or
over-capacity grids, candidate queries return null and all affected
callers fall back to exhaustive scans; a missing candidate must not
cause a false road choice or hide a competing parallel way.

No spatial structures are persisted; stale/offline signs remain map-only
grey '?' hints and never become a live speed limit or an upcoming warning.

## Validation

JUnit compares indexed vs baseline matching at city-grid sampling points,
parallel lanes, stacked roads, curved signs, node intersections and grid
boundaries. The stress test creates 5,000 road segments and 5,000 sign nodes
without a brittle wall-clock threshold, and logs index construction time
and local candidate counts. These tests run in `:app:testDebugUnitTest`.

## Limitations

This is not an R-tree, tile engine or driving route planner. Its
performance was not profiled on Android Auto hardware. The local metric
projection does not support pole regions or cross-dateline datasets:
for such inputs the implementation intentionally uses the prior
full-scan path, preserving safety at the cost of speed.
