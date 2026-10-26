# Phase 3 — Road Graph V2: observed GPS turns

## Purpose

Prevent sign warnings from selecting the wrong OSM road after a GPS jump at
a junction. A short history of **new, accepted filtered GPS fixes** is used
to confirm a road match before producing forward-looking sign warnings.

## State machine

`ObservedRoadTurnTracker` starts unconfirmed. Two moving GPS fixes
separated by ≥750 ms and ≥8 m establish the initial way and travel
direction; a repeated UI render with the same GPS timestamp is not a new
sample.

On an observed way/direction change, alerts are withheld until multiple
fixes on the new way provide motion evidence. If the previous and next
ways are connected by the same OSM node, directed incoming/outgoing
edges, matching layers and GPS near the junction, a second new-way
fix may confirm the change. Without these signals, four fixes
covering ≥25 m and ≥2 s independently reacquire the new road.
This does **not** claim that an otherwise illegal turn is permitted.

A gap >8 s, poor accuracy >25 m, stopped car, unknown one-way direction
or discontinuous GPS data clears the tracker. Road graph data updates
also clear previous observations. Memory is bounded to two fixes and a
candidate counter; no GPS location or movement history is written to
disk or sent to a server.

## UI and data usage

`NativeMap` passes the timestamp of its most recently accepted GPS fix
to `RoadSigns.state()`. Speed limits remain governed by the existing
conservative `SpeedWayMatcher`, but **upcoming sign warnings** are held
until the observed way is confirmed. As in V1, lookahead never guesses
a branch to take at a future fork and never uses offline cached signs
as live warnings.

## Deliberate scope limits

The graph validates direction, topology and layers but **does not**
parse `type=restriction` turn-restriction relations or conditional
vehicle access. GPS observations confirm what the car appears to have
done, not a legal right to make the turn. A graph transition may be
unknown on incomplete OSM data, so the tracker permits delayed
standalone reacquisition after sustained motion.

Nine deterministic tests use synthetic GPS tracks for actual turns,
GPS jitter, duplicate timestamps, disconnections, stale fixes,
one-way roads, stacked bridges and stopped vehicles. Real-head-unit
and recorded GPS playback tests are still required.
