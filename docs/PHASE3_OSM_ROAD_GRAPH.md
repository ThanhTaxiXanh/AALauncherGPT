# Phase 3 — OSM road graph and safe multi-way sign distance

## Implementation

The Overpass way parser now captures the `nodes` sequence, `layer`,
`oneway` (including reverse `-1`) and implicit one-way roundabouts.
Unexpected `oneway` values are treated as unknown/nontraversable for the
graph, rather than assuming vehicle access.

`OsmRoadGraph` builds directed edges between each adjacent pair of OSM
nodes when loading a fresh Overpass response off the map UI thread.
Connectivity is by **shared node ID** with matching coordinates and level;
nearly touching coordinates are not considered a connection.

To avoid invented navigation decisions, `trace` advances along the
current way and potentially across multiple OSM ways only when **one**
forward exit exists at a node, excluding U-turns. It stops at forks and
roundabouts, sharp discontinuities, different levels, bad node ID metadata,
loops or a bounded horizon. It does not choose a turn based on preference
for a straight road.

`RoadGraphUpcomingSigns` projects signs onto the unique directed prefix
and computes distance along connected road segments (maximum 350 metres).
OSM sign node IDs, roadside offsets, and facing/forward/backward direction
are checked. Signs on branches beyond uncertain junctions are suppressed.
The old single-way matcher remains a fallback for geometries without full
OSM node sequences.

A constructed graph is part of each freshly parsed Overpass dataset and
is reused by `RoadSigns.state()`. No per-frame graph construction.
Offline cached signs remain grey '?' visual hints only; they cannot create
live upcoming warnings or speed-limit values.

## Deliberate limitations

This is *not* turn-by-turn route planning. The code does not interpret OSM
`restriction` relations, access:conditional, vehicle-specific rules,
traffic rules at unsignaled intersections, or planned turns. It does not
support offline tile navigation. If a route decision cannot be justified,
warnings beyond that junction are omitted rather than guessed.

Unit tests cover two-way connections, reversed geometry, one-way rules,
coordinate-only crossings, bridge/tunnel layers, inconsistent node
coordinates, internal way junctions, forks, and distances across ways.
Recorded GPS replay and hardware Android Auto testing remain outstanding.
