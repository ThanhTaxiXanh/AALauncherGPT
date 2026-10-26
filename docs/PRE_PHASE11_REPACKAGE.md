# Pre-Phase-11 package refactor and application identity

## Requested identity

- App label: **AA Taxi Driver**
- Exact Android application ID: `Thanh.TaxiDriver.AALauncher` (case-sensitive)
- Gradle namespace: `Thanh.TaxiDriver.AALauncher`
- Kotlin packages: `Thanh.TaxiDriver.AALauncher.car`, `.dashboard`, `.ui`,
  `.map`, `.gps`, `.road`, `.signs`, `.calendar`,
  `.reports`, `.qr`, `.media`, `.core`
- Source path: `app/src/main/java/Thanh/TaxiDriver/AALauncher/`
- Unit tests: `app/src/test/java/Thanh/TaxiDriver/AALauncher/`
- Artifact: `AA-Taxi-Driver-debug`

## Backward-compatibility warning (critical)

Changing `applicationId` from `com.cartaxi.launcher` is **not** an
in-place rename or an upgrade. Android treats this as a separate package
with a separate UID and private data sandbox. The old app may coexist
with AA Taxi Driver. Local preferences, QR originals and mirrors,
traffic reports, event notifications, media URIs and offline road/sign
cache cannot be read by the new app simply by renaming the package.
Signing with the same key **does not** automatically move private data.

Do not uninstall the previous application until any needed personal
events, QR and report data have been exported/saved via user-authorized
means. Full legacy calendar/settings migration is not implemented by
this refactor. Use no filesystem permission or automatic access to
the previous application's sandbox.

Existing `KEYTHANHTAXIXANH` and certificate names in CI refer to
historical signing configuration. Changing application ID does not
require the original signing key for side-by-side installation, but a
published AA Taxi Driver app should consistently use an approved
release signer for future updates.

## Refactor scope and checks

- Moved production and JVM Kotlin sources from
  `com/cartaxi/launcher` into functional packages under the new
  namespace, with explicit import dependencies.
- Rewired component class names in AndroidManifest to new packages.
- Kept runtime workflows for QR/events/reports and map feature logic
  unchanged; the new app starts with new private storage.
- CI enforces both new APK package identity and launcher label with
  Android SDK `aapt` (in addition to build, unit tests and artifact).
- GitHub repository name remains AALauncherGPT and its historical audit
  references are not renamed, to preserve previous commit/release links.
- No code from legacy package is bundled inside AA Taxi Driver.

## Phase 11 gates remain

Android Auto navigation template compliance (NF-1/NF-2),
instrumentation, real host tests, signed release and on-device stability
still require independent validation. GPS matcher/road-graph CPU/RAM
profiling remains deferred as specifically requested.
