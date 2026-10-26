# Phase 9 — Android Auto CarService lifecycle and host-friendly controls

## Changes

* The existing NavigationTemplate action-strip **Menu** is wired to
  a native Car App Library `ListTemplate`, not an arbitrary custom
  settings canvas. It offers "Back to map", read-only GPS status,
  map status, "Refresh map" and instructions to change the full Dashboard
  configuration on the phone. A refresh is deferred until the map screen
  resumes, so no GPS/map engine is run for the menu.
* The `LauncherScreen` lifecycle observer suspends the 1-second
  ticker, location and GNSS listeners, MapLibre VirtualDisplay and
  rendering upon `ON_STOP`, and resumes only when both the host
  screen and valid surface are active. `ON_DESTROY` releases the
  current surface and clears callbacks. Callback delivery from a
  previously replaced surface cannot destroy a newer one.
* Location registration is idempotent and is re-checked after permission
  changes. The host UI does not request driving-time permissions.
  GPS readouts display "waiting", "permission required" or "stale"
  instead of retaining old speed/road-sign warnings after a fix expires.
  Invalid/out-of-order last-known locations are ignored.
* The map VirtualDisplay is recreated when layout/preset dimensions
  change, and only while the map screen is active. Host surface
  dimension changes are tracked independently of the map framebuffer.

## Host security

The old `ALLOW_ALL_HOSTS_VALIDATOR` is now used **only for debug**
builds. Release builds allow only trusted Car App Library hosts using
its built-in `hosts_allowlist_sample`, plus platform-authorized
renderer hosts. There is no hardcoded host package signature in
app code.

Navigation rendering remains based on the existing host-supported
`NavigationTemplate`. Non-map content in a navigation surface can
still be subject to head-unit and driver-distraction restrictions;
Phase 9 does not claim Android Auto certification.

## Tests and limitations

`CarSurfaceStateTest` exercises screen/surface ordering, resizes,
stale destroy callbacks, idempotent destruction, permission states and
GPS age bounds in local JUnit. The existing CI builds the debug APK and
runs all previous Phase 1–8 tests.

Real device and DHU testing is still needed for surface replacement,
lifecycle on phone/head-unit disconnect, GPS permission revocation,
navigation host gesture differences and production host certificates.
The explicitly deferred Android CPU/RAM/latency measurements of
GPS matching and road graph are not part of this increment.
