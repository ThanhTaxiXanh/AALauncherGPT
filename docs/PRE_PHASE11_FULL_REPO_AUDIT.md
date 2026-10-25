# Pre-Phase-11 repository audit — 2026-10-25

Source reviewed at `c16ace9` (Phase 10 CI success) with follow-up
hardening committed separately. This is a **source and CI audit**, not an
instrumented Android Auto or real-phone certification.

## Coverage

- All main Kotlin components inventoried across Phases 0–10:
  CarService, Surface state, Dashboard renderer/phone UI/settings/layout,
  NativeMap, GPS filters, OSM matching/road graph, restrictions and signs,
  offline caches, traffic reports, lunar/calendar reminders, QR store and
  Media3 service
- AndroidManifest, Gradle dependencies, GitHub Actions/release signing,
  previous JVM test resources and project documents
- Latest `main` GitHub Actions verify result before this audit: success;
  does not imply real-device behavior or release-signing verification

## Confirmed findings and status

| Priority | Area | Finding | Status |
|---|---|---|---|
| P0 | Map data licence | NativeMap disabled MapLibre attribution despite OpenFreeMap/OpenStreetMap requiring source credits | FIXED in hardening: restore MapLibre attribution and render visible OpenMapTiles/OSM credit on Dashboard frame |
| P1 | Overpass networking | JSON response size checked **after** `readText()`: oversized reply could allocate excessive memory; connection not reliably disconnected after parse failure | FIXED: bounded streaming reader and finally-disconnect |
| P1 | Events persistence | `Events.parse()` turned any malformed event item into an empty list, while `save()` only validated outer JSON array. A syntactically valid array containing one broken record could therefore be overwritten by subsequent edits | FIXED: strict parser required for existing content before save, while read-only calendar displays remain tolerant |
| P1 | QR consistency | `index.json` and `qr.png` were updated independently; crashes/write failures could leave the displayed QR inconsistent with the selected catalog item | IMPROVED: reconciliation on first validated catalog read after restart and rollback attempts after write errors |
| P1 | Road graph/GPS/UI workload | NativeMap executes GPS filtering, tile-road queries, multiple sign operations, and map callbacks on the main thread; creates frames through VirtualDisplay/ImageReader | DEFERRED: on-device profiling and optimization were explicitly postponed |
| P1 | Android Auto UX | Several custom calendar/QR/sign widgets are painted inside the navigation surface; compliance and driver-distraction acceptance cannot be demonstrated by unit tests | RELEASE GATE: validate with Android Auto DHU/host and current permitted templates; do not assume certification |
| P1 | Release signing | Publish job is opt-in and requires secrets, approved fingerprint, and a signed release; main CI currently verifies only debug APK | RELEASE GATE: verify approved signer and install/upgrade path before Phase 11 release |
| P2 | Architecture | MainActivity, NativeMap, LauncherRenderer, RoadSigns and CarService remain large modules with mixed responsibilities | PLANNED: isolate repositories/view controllers and external/network interfaces incrementally |
| P2 | Test coverage | JVM tests cover parsing, GPS replay and policy logic, not actual MapLibre canvas, Media3 playback, notifications, Android Auto host lifecycle, phone touch events | PLANNED: instrumentation tests, DHU/real-device matrix and lifecycle checks |
| P2 | UI response | QR bitmap decoding/PNG re-encoding and some filesystem operations execute on main thread; large 2600-pixel images could cause visible jank | PLANNED: move to bounded worker and return result to main thread |
| P2 | Media | Phase 8 supports single selected audio file, not browse/playlists or a complete AA media app | NOT IN SCOPE of Phase 8 V1; define separate product requirements if desired |
| P2 | QR validation | Store header/dimension checks do not independently verify PNG CRC/full decode (phone import decodes and re-encodes first) | PLANNED: add checksum/full-image validation for externally corrupted legacy files |
| P2 | Reproducible pipeline | GitHub workflow builds and tests; no separate lint/static analysis, instrumentation tests, dependency advisory scan or signed-release smoke test on every PR | PLANNED: add quality gates to Phase 11 |

## Explicitly verified positive safeguards

- Fine-location runtime permission is checked before registering listeners;
  new local reports do not contain GPS without user opt-in.
- Local traffic reports are not submitted publicly; export defaults to
  omitting GPS.
- Road-sign matcher abstains when ambiguity or poor GPS accuracy is detected,
  and turn-tracker waits for observed GPS transitions.
- Dashboard presets reject overlapping tiles, preserve left icon rail and
  enforce minimum map size. Phase 10 uses separate renderer instances.
- Release host validation is stricter than debug and uses the official
  Car App Library sample host allowlist.
- GitHub workflows do not check in keystores and require an approved
  certificate fingerprint to publish a signed release.

## Phase 11 entry criteria

1. CI green on audited hardening commit, with regression tests and debug APK.
2. Protect QR/calendar/report backward compatibility with a backup-and-restore
   smoke test using real upgraded installations.
3. DHU/head-unit review of CarService templates, surface lifecycle and menu.
4. Physical-phone checks: MapLibre attribution is legible, QR remains scannable,
   Media3 notification/audio focus works, background reminders behave with
   permissions/Doze.
5. Run Android lint and manifest/security checks; review target/compile SDK
   and library dependency versions for a release.
6. Verify production keystore certificate fingerprints and upgrade behavior.
7. Hold on-device GPS matching/road graph CPU/RAM/latency measurements for
   the later window previously requested by the user.

This document is a triaged backlog, not a claim that all P1/P2 items are
fixed or that the APK has passed automotive certification.
