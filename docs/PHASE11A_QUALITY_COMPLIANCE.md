# Phase 11A — Quality gates and Android Auto compliance review

Date: 2026-10-25. Product: **AA Taxi Driver**,
`applicationId=Thanh.TaxiDriver.AALauncher`.

## Confirmed regulatory/quality status

**Android Auto public-release readiness: BLOCKED.** Android for Cars
[quality requirements](https://developer.android.com/docs/quality-guidelines/car-app-quality)
require Navigation apps to provide turn-by-turn routing (**NF-1**).
The current application is a live GPS/sign/map dashboard but has no
destination/route-calculation and turn-by-turn workflow.
The app continues to declare `androidx.car.app.category.NAVIGATION`
because silently switching the declared category to another unsupported
business purpose would not create compliance.

**NF-2 hardening implemented:** `LauncherScreen` now invokes
`LauncherRenderer.renderNavigationSurface` for the host
`NavigationTemplate`. This draws only the MapLibre frame, its loading
placeholder and required map attribution. It does not draw calendar,
QR, icon rail, personal report controls or the dashboard grid onto
the navigation surface. The existing multi-tile renderer is retained
in `PhoneDashboardActivity`. Full certification and real safe-area /
responsive host validation remain outstanding.

**Release is intentionally blocked in CI** by
`scripts/phase11a_car_policy_gate.py --release` before restoring any
release signing secret. This is not an accidental CI failure: there is
no responsible automated way to certify a Navigation app with no
turn-by-turn navigation. The script's `--audit` mode reports the
current code status but lets debug build/test continue.
Static heuristics cannot certify the app; future unblocking requires
real feature work, independent DHU/real host evidence, a category
decision, manual review and explicit changes to the fail-closed gate.

## Automated checks implemented

1. `:app:assembleDebug` remains compulsory.
2. `:app:testDebugUnitTest` and existing Kotlin/JUnit regressions.
3. `:app:lintDebug` now runs as a CI step with `abortOnError=true`,
   HTML and XML reports; no blanket baseline to suppress findings.
4. `python3 scripts/phase11a_car_policy_gate.py --audit` verifies
   category, routing API indicators, NavigationTemplate map-surface-only
   use and debug/release host-check separation.
5. Python `unittest` checks the policy script catches regression
   cases, including calendar/QR drawn on the car navigation surface.
6. `:app:assembleDebugAndroidTest` compiles instrumentation smoke
   tests for app identity and manifest component registration.
   **CI does not run those tests** because no emulator/DHU is attached.
7. `aapt dump badging` verifies exact package ID and app label.
8. `publish` is gated and cannot sign/release Android Auto until
   compliance work is complete.

## Work deliberately not marked complete

- NF-1 routing: implement real turn-by-turn route, instructions,
  reroutes, navigation intents, simulation and lifecycle/cluster
  integration; or pursue a genuinely suitable and supported app
  category/product scope. POI, Weather, Media etc. may not be used
  simply to bypass Navigation criteria.
- NF-2 visual validation: host SurfaceCallback visible/stable areas,
  MapLibre host themes/dark mode, overlay and attribution legibility.
- Android Auto DHU and vehicle UI acceptance tests.
- Instrumentation test execution, actual permission flows, Media3
  playback/background notification and renderer performance.
- Signed-release review and package upgrade checks.
- GPS matching/road graph CPU/RAM/latency instrumentation **remains
  deferred at the user's request**.

Sources:
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://developer.android.com/training/cars/apps/navigation
- https://developer.android.com/training/cars/apps/library/draw-maps
- https://developer.android.com/training/cars/apps/library/set-up-project
