# Phase 5 — Phone Configuration Center V1

## Goal and scope

The Android phone now owns a **central, versioned dashboard configuration**.
This increment deliberately does **not** implement the Phase 6 layout tree or
8 dashboard presets; the existing wide/tall panel geometry remains unchanged.

Phone controls:
- Show/hide calendar/events panel
- Show/hide the QR panel
- Show/hide the advisory speed/sign panel
- Show/hide the road name overlay on the map
- Map theme: automatic day/night, always light, always dark
- Opt in/out of drawing *personal, unverified* traffic report markers
- Explicit confirmation to restore default display settings

Hidden dashboard cards retain their original space in Phase 5 and display a
small placeholder. This avoids changing map interaction and template safety
before Phase 6. The navigation map itself cannot be hidden through this UI.

## Persistence, migration and compatibility

The model `DashboardSettings` is immutable. `DashboardSettingsCodec`
uses a versioned JSON value under private Android SharedPreferences
`dashboard_settings/payload_v1`; the repository always reads the most
recent settings before merging a single control change.

If no Phase 5 configuration exists yet, the repository imports the existing
`traffic_report_privacy/show_map` preference *on read* as the initial
report-overlay choice. Once a Phase 5 value is written, that newer choice
takes precedence. The legacy privacy preference is **not deleted**.

The existing `cfg/events` JSON, `filesDir/qr.png`, and
`filesDir/traffic_reports_v1.json` are **neither migrated nor rewritten**:
they continue to be accessed through their previous code paths. This avoids
losing calendar or QR content. Unsupported/corrupt future configuration
versions are not overwritten by a normal edit; the phone offers a separate,
confirmed reset while car rendering uses safe in-memory defaults.

## Runtime integration

`LauncherScreen` refreshes a cached settings snapshot on the existing
1-second ticker and supplies it to `LauncherRenderer` through `UiState`.
`NativeMap.update` applies the map theme when needed. The personal-report
worker reads the centralized setting before loading local report markers.

No new background GPS collection, network endpoint, or Android Auto freeform
settings form was added. Phone settings use standard Android widgets; the
car app retains its current supported navigation template.

## Tests and deferred tasks

JVM tests verify default settings, legacy privacy migration, full round-trip,
partial schema compatibility, rejection of invalid/newer versions, and
independence of settings from legacy events and QR content. GitHub Actions
runs the same APK build and Kotlin unit-test steps.

Phase 6: 8 presets, editable layout tree, preview, saving/restoring layouts.
Phase 11: device/Android Auto validation and the intentionally postponed
GPS-road graph CPU, RAM and latency measurements.
