# Android Auto Map Camera — Phase 2

`DrivingCameraController` is a pure Kotlin state machine extracted from `NativeMap`.
MapLibre rendering and Android Auto Surface callbacks remain in the existing classes.

## Supported states

- `FOLLOW_3D`: follow vehicle, 60° map tilt (initial mode).
- `FOLLOW_2D`: follow vehicle, 0° map tilt.
- `FREE_PAN`: keep the user's manually moved/rotated view until recenter or idle timeout.
- `recenter()` is a transition back to the preferred follow mode, not a persistent rendering state.

A full `ROUTE_OVERVIEW` mode is intentionally deferred until a valid route geometry exists. No route is fabricated.

## Behavior preserved or corrected

- Manual scroll, fling or rotate enters `FREE_PAN`; after 12 seconds without another gesture the map recenters.
- Pinch temporarily blocks scroll for 350 ms and fling for 600 ms, preventing conflicting touch events.
- A manual follow zoom is held for 15 seconds, then smoothly returns to auto zoom.
- 2D/3D toggle during `FREE_PAN` changes tilt without unexpectedly jumping back to the car.
- Recenter clears temporary zoom and rotate gesture mode.
- On Android Auto Surface restart, the preferred 2D/3D follow mode is restored.
- Arrow rendering uses the actual camera tilt to avoid inconsistent marker projection during manual pan.

## Tests

`DrivingCameraControllerTest.kt` verifies follow mode transitions, tilt preferences,
manual pan timeout, gesture guards, temporary zoom expiry, rotate mode and reconnect state.

## Remaining verification

The state machine is unit-tested, but physical Android Auto host gestures, orientation
changes and MapLibre surface transitions require device validation. This increment does
not make an unmeasured FPS or latency improvement claim.
