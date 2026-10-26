# Phase 3 — Overpass fixture, extended GPS replay and road-graph scaling

## Reproducible integration regression

`overpass/synthetic_junction.json` is a **synthetic Overpass-compatible
response**, checked into test resources. It includes three OSM ways, node
geometries, a direction-tagged speed-limit sign, and a `no_right_turn`
relation. The JUnit integration test invokes the **production**
`RoadSigns.parse` JSON path, then checks its graph and sign warnings
without contacting an Overpass server. A standard `org.json` test-only
dependency enables Android JSON parsing in local JVM tests.

## Longer journeys

`gps_replay/long_two_way.csv` contains **960 deterministic GPS fixes** on
two opposite-direction journeys across 120 connected OSM ways. The replay
exercises the real `SpeedWayMatcher`, `ObservedRoadTurnTracker` and graph,
including 238 changes in matched OSM way. There is no user/location data
in these synthetic fixtures. The shorter five-scenario fixture remains.

## Scaling and safety

`OsmRoadGraph` now precomputes incoming/outgoing node sets indexed by
way and travel direction, making `allowsTravel` expected constant-time
and `observedTransition` proportional to nodes on the matched ways
rather than scanning all graph edges on every GPS update.

Graph traversal no longer allocates a filtered outgoing-edge list per
step. The explicit segment cap is 24,000 undirected geometry segments.
The stress regression creates 5,000 ways and checks indexing, one-way
behavior, directed transitions and bounded ahead traces. It logs graph
build time for reference **without flaky CI wall-clock assertions**.

These tests are synthetic: they do not measure actual car-head-unit
performance or validate live OSM completeness. The speed/turn policy
continues to omit warnings where source geometry or regulations are
uncertain.
