# Phase 10 — Standalone Android Multi-Tile Custom Dashboard V1

## Scope: native phone dashboard, not a custom Android Auto template

The Phase 10 "Custom Dashboard" is an **explicitly launched internal
Android Activity** named `PhoneDashboardActivity`. It is opened from
`MainActivity` on the Android phone and prefers landscape orientation.
It is **not** a new Android Auto Screen, host overlay or bypass of
Car App Library driver-distraction rules.

The view renders five existing tiles — MapLibre map, icon rail, QR image,
lunar/event calendar and speed/sign guidance — with the same validated
12×12 grid and eight presets from Phase 6. The phone provides toolbar
actions to return to configuration, switch between saved presets/custom
layout, and recenter the map. Users edit positions and tile sizes only
inside the existing Phase 6 Layout Builder on the phone.

The live map image is produced by the current `NativeMap` engine through
its virtual display; map pan, tap and pinch gestures are routed back
through the same engine. Without precise-location permission, the map
can be browsed manually; a vehicle speed, street name and road-sign
warnings are never asserted as live until a recent valid GPS fix exists.
This Activity does not request location permission automatically.

## Lifecycle/resource isolation

`LauncherRenderer` is now **instantiable per surface**: CarService and
PhoneDashboardActivity each get separate cached bitmaps and hit rectangles.
This prevents on-phone rendering from overriding tap areas on the car
host. The car's existing NavigationTemplate and menu remain unchanged.

A pure `PhoneDashboardLifecycle` gate allows map tick/GPS work only when
the phone Activity is active with a measured layout. OnStop stops GPS and
GNSS listeners, cancels the tick, drops image frame callbacks and stops
MapLibre VirtualDisplay; onDestroy additionally shuts down the owned
report and road-sign worker executors. The same cleanup is now used
for the final destruction of the car's map, without disrupting
stop/resume semantics.

Map VirtualDisplay size is recalculated when the active preset/custom
tile geometry changes. Renderer sizing and touch normalization share
the same inset and grid formula, tested across phone aspect ratios.

## Validation and limitations

Unit tests compare hit regions on eight presets and common screen sizes,
reject non-map/out-of-bounds touches, check custom layouts and test
Activity lifecycle state transitions. Regular CI builds the debug APK
and runs the complete Phase 0–9 regression suite.

This is not a certified Android Auto multi-window dashboard, a complete
launcher for third-party applications, or a live GPS proof on a phone.
Media continues through the separate Phase 8 Media3 service and is
not injected into the navigation canvas. In particular, safe on-road
interaction, touch targets, virtual-display lifecycle and map stability
require human testing on Android phones and supported car hosts.

The intentionally postponed on-device GPS matching and road-graph
CPU/RAM/latency measurements remain deferred to Phase 11.
