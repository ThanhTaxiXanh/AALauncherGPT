# Phase 6 — Dashboard layout presets and phone Layout Builder V1

## Eight layouts, one shared renderer

The car Dashboard's existing MapLibre Surface + six-button rail + calendar,
QR and sign panels now use a **12×12 validated layout model** instead of
the old fixed wide/tall percentage branches. The same tile rectangles
drive rendering, map hit-testing and icon hit-testing. Existing QR image,
events, reports, speed/sign checks and Maps sources are untouched.

Eight selectable layouts:

1. Classic, matching the familiar map-left + calendar/QR/sign arrangement
2. Three stacked utility panels
3. Signs first
4. QR first
5. Wide map
6. Wide utility panels
7. Bottom signs
8. Bottom QR

All layouts require exactly one map, icon rail, calendar, QR and signs tile.
The icon rail stays fixed at x=0 with width=1, height=12. The map has a
minimum 4×5 grid footprint. Utility tiles must be at least 2×2 and
may not overlap or exceed the grid. Invalid edits are rejected before
the selected layout changes; no layout can remove map interactivity.

## Editor on the Android phone

The phone Configuration Center includes a live colored preview and preset
selector. Tap to select a tile, drag to move, drag its bottom-right corner
to resize, or use the move/resize buttons. The preview does not commit
changes automatically. **Save custom layout** activates the validated
custom snapshot; **Restore saved custom layout** recovers that snapshot.
Switching to a preset preserves the last saved custom layout. An explicit
discard button returns the preview to the currently selected active layout.

## Persistence and compatibility

Phase 6 extends existing Phase 5 JSON `dashboard_settings/payload_v1`
with optional `layoutPresetId`, `useCustomLayout` and `customLayout`
keys, preserving the JSON version and previously stored settings.
Old Phase 5 data with none of these fields loads as the Classic preset.
The settings repository rejects corrupt, overlapping or unknown layout
definitions instead of silently overwriting them. The explicit Phase 5
reset clears layout choices as part of returning to defaults.

The Android Auto navigation template is not changed. Editing takes place
on the phone; the car reads the configuration through the existing safe
settings snapshot. **This is not certification that all freeform surface
layouts are accepted by every Android Auto head unit.**

## Tests and deferred items

Local Kotlin/JUnit tests cover all eight presets, round-tripping,
migration from Phase 5, custom save/restore, safe bounds, overlap and
corruption guards, and preservation of existing visibility preferences.
The regular CI builds the debug APK and runs these alongside all
existing map, GPS, report and OSM tests.

The Android-device CPU/RAM/latency profiling requested to be deferred
is **not included**. Real device and car-host touch / screen geometry
validation remain to be completed later.
