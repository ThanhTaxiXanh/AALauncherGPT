# Phase 3 — OSM conditional speed limit parsing (increment 1)

`RoadSigns.kt` still owns the Overpass query, OSM way selection and
speed-limit widget data. `ConditionalSpeedLimit.kt` adds a pure Kotlin parser
for a **strict and limited** subset of `maxspeed:conditional`:
- A numeric km/h or mph speed and a weekday + time window.
- Daily time windows such as `50 @ (07:00-09:00)`.
- Day ranges `Mo-Fr` and lists `Sa,Su`.
- Multiple non-conflicting semicolon-separated rules.
- Vietnam civil timezone `Asia/Ho_Chi_Minh`, regardless of device settings.
- Directional tags `maxspeed:forward:conditional` and
  `maxspeed:backward:conditional` take precedence over the shared condition
  for the known direction. Without a direction, directional conditional tags
  are not guessed.

**Fail-closed rule:** an unsupported, incomplete or contradictory conditional
expression resolves to unknown `null`, **not** the unconditional speed. In
particular, wet-weather, holidays, school hours, sunset, calendar dates,
overnight windows and wrapping-week ranges are not supported. If all supported
rules are inactive, the explicitly tagged base speed may apply.

The parser does not infer legal limits from road classifications. Road speed
selection still uses the existing local OSM-way heuristic (distance, heading,
continuity); it has **not** been tied to a route-level road segment ID yet.
An OSM value remains informational and must not be treated as authoritative
legal advice.

Unit tests exercise timezone/day boundaries, unit conversion, concurrent
rules, conflicts and unsupported conditions. Device-based GPS and Android Auto
tests still need to be performed.
