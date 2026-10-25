# Phase 3 — Road-relative upcoming sign warnings

The preceding implementation checked a straight-line 350 m cone ahead of the
vehicle. That could include signs across blocks, on parallel roads, or on
crossing roads and displayed Euclidean distances instead of road distance.

## New behavior

- Requires **live Overpass data**, an unambiguous `SpeedWayMatch`, moving GPS,
  valid travel heading and horizontal accuracy ≤25 m.
- Projects the filtered GPS position and each candidate sign onto the
  matched OSM way geometry. `UpcomingSign.distM` is the traveled **arc length
  along that way**, not Euclidean distance.
- Respects OSM way node IDs when Overpass returns the `nodes` array. A sign
  explicitly belonging to another way is never pulled onto our road.
- Rejects geometry-only sign associations when a different nearby OSM way is
  also plausible; shared intersection nodes with no direction are ambiguous.
- Validates `direction` (physical facing, opposite to vehicle approach)
  and `traffic_sign:forward/backward` tags when present.
- Directional signs without either a reliable facing tag or an explicit way
  direction are not eligible for ahead **alerts**. Signals and speed bumps may
  apply both directions on a uniquely identified way.
- Rejects signs behind the car, closer than 10 m or farther than 350 m along
  the matched way. Sorts by road distance and suppresses only duplicate
  node IDs or equivalent signs within 5 m.
- The display contract `UpcomingSign(item, distM)` is unchanged. All existing
  Kotlin/MapLibre/Android Auto components are retained.

## Scope and caveats

**This is not a route graph or a planned navigation route.** When a sign lies
on a *different* OSM way after a junction, this increment does not guess
which turn the driver will take and intentionally omits that warning.
The chosen OSM way can have curves, but there is no attempt to join ways
across junctions without route topology or known turns.

Way membership and sign applicability remain dependent on OSM completeness
and GPS accuracy. The parser intentionally favors missing a warning over
showing a potentially false directional warning. Road signage and applicable
regulations remain authoritative over any app display.

Offline cached OSM signs are still grey '?' map hints only; they do not
become active upcoming alerts, just as they cannot become live speed limits.

## Tests

Synthetic Kotlin tests cover curved-road arc length, signs behind, reverse
direction, parallel roads, OSM node membership, shared junctions, facing,
unknown direction, poor GPS, deduplication and range limits.
Hardware Android Auto testing and real recorded GPS journeys remain necessary.
