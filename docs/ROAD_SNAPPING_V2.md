# Road Snapping V2 — Conservative matching

This increment extracts road matching from `NativeMap` into `RoadSnapMatcher`.
It retains MapLibre/OpenFreeMap and the existing Kalman GPS filter.

## Behavior

- Input: filtered GPS latitude/longitude, reported GPS horizontal accuracy, travel heading (when reliable), and nearby transportation line segments.
- Candidate score: perpendicular distance + heading disagreement, with a modest continuity bonus for the previously matched segment.
- Decline to snap when reported accuracy is missing/poor (>25 m), no nearby candidate is plausible, heading conflicts, or comparably scored roads are spatially separated.
- Decline to snap when two competing roads overlap horizontally but have conflicting road level metadata, or when crossing roads are indistinguishable without a heading.
- On decline, `NativeMap` uses the filtered GPS point rather than inventing a lane or forcing an uncertain road.
- Unlike the previous implementation, there is **no 1.6 m lateral lane offset**.

## Limitations

- This is **not** turn-by-turn route map matching or lane-level positioning.
- Tile feature `layer` / `brunnel` metadata may be missing; GPS altitude normally cannot resolve stacked roads accurately.
- Matching is based on local geometry, heading and simple continuity, not a full route graph or HMM.
- A null match means insufficient evidence for a snap, not proof that the GPS fix is wrong.
- Unit tests use synthetic geometries. Android Auto and real GPS replay remain required before treating this as fully field validated.

## Regression tests

`RoadSnapMatcherTest.kt` covers straight roads, reverse heading, crossing roads with/without heading, parallel roads, stacked levels, duplicate features, weak accuracy and distance rejection.

## Field verification checklist

1. Parallel frontage/main roads and divided carriageways (do not jump between roads).
2. Crossroads, forks and U-turns (correct heading, no persistent wrong-road stickiness).
3. Bridges, tunnels and stacked urban roads (abstain if vertical road level cannot be resolved).
4. Poor GPS/urban canyon and first fix (raw filtered GPS when unreliable).
5. Loss/recovery of GPS and Android Auto reconnects.
6. Compare latency/FPS and marker trajectory against the source commit with actual recorded trips.

No simulated or third-party data is presented as verified real-world traffic data.
