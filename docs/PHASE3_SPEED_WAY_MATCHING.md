# Phase 3 — Speed limits by OSM way

This increment preserves the existing Overpass data source, signs and
`ConditionalSpeedLimit` but replaces closest-way selection for speed limits.

- Read each OSM way ID and `layer` tag, or explicit bridge/tunnel hint.
- Match using the **filtered GPS fix** and reported horizontal accuracy, rather
  than the map marker position that may already have snapped to a road.
- Decline ambiguous parallel, intersecting or overlapping layered roads.
- Track previous matched way ID only for minor continuity; it never overrides
  ambiguity. Clear the previous speed immediately on a way ID change.
- Interpret forward/backward maxspeed and conditional tags only after matching
  a way with sufficiently reliable direction data.
- Expose `SignsState.roadWayId` as an internal OSM way identifier. It is not
  lane-level positioning or an authoritative legal speed sign.

Expired offline cache is not used for speed limits. Cached signs and fresh
verified-sign offline display remain separate follow-up work.

Unit tests cover reversed way direction, junctions, parallel roads, bridges/
tunnels, weak GPS, prior matching, and adjacent segments. Tests use synthetic
geometry: field validation on a car head unit and recorded GPS traces remains
essential. An unknown limit does not mean unlimited speed.
