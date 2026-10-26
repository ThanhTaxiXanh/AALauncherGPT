# Phase 11B — Device, DHU and upgrade verification protocol

## Release-category decision (from Phase 11A)

**Official Android Auto Navigation is not shipped in the release variant.**
The existing Navigation `CarService` is installed **only in debug** for
DHU/head-unit engineering tests. The release variant is phone-only until
real routing and NF-1–NF-7 are implemented and externally reviewed.
Do not mistake debug host acceptance for eligibility for Google Play Cars.

This resolves *the false Navigation-category claim in the production
manifest*, **not** missing turn-by-turn navigation. The release workflow
remains fail-closed pending Phase 11C signing and approval.

## Test matrix — record PASS/FAIL, Android version and evidence

| ID | Device / host | Procedure | Pass criteria |
|---|---|---|---|
| P01 | Android phone 11–16 | Install debug; open settings / standalone dashboard | App launches; settings and presets persist |
| P02 | Phone, cold start + rotate | Pan, pinch, close/reopen map repeatedly | No black frames, crash or stale touch geometry |
| P03 | Phone, GPS disabled/revoked | Revoke FINE_LOCATION while map is open | Speed/signs immediately become unavailable, app remains responsive |
| P04 | Phone, offline | Disable network, open cached map/report view | Graceful fallback; no invented traffic warnings |
| P05 | Phone QR library | Import two QR codes, rename, select, export PNG, restart | Both original images intact; last selection preserved |
| P06 | Phone migration | Keep old `com.cartaxi.launcher` installed, install new app | Side-by-side install; no implied access to old app private data |
| P07 | Phone events | Add solar/lunar/leap event, restart, edit and delete | Correct dates, no silent event loss |
| P08 | Phone notifications | Grant/deny then revoke POST_NOTIFICATIONS | No reminders by default or without consent |
| P09 | Phone Media3 | Select local audio, play/pause, unplug/replug headset | Playback controllable; correct audio focus/notification |
| A01 | DHU or real AA host, debug | Connect app in developer mode | Debug Navigation service visible; map-only surface |
| A02 | DHU, template menu | Open menu and return to map | No blocked taps, host exceptions, unsafe templates |
| A03 | DHU, disconnect/reconnect | Unplug while map active, reconnect, switch screens | No leaked GPS listener or stale Surface |
| A04 | DHU, multiple aspect ratios | 800×480, 1280×720 and ultrawide | Attribution legible, map covers intended area |
| A05 | DHU, denied location | Run without GPS, then grant on phone | Permission UX safe; map displays without fake speed |
| R01 | Un-signed local release **manifest** | Inspect merged manifest/APK | No CarService, Navigation category or automotive meta-data |
| R02 | Future approved release | Inspect signer SHA-256 and install upgrade | Matches approved cert; package/versionCode coherent |

## Repeatable commands

Run after downloading the debug APK from GitHub Actions:

```bash
adb install -r app-debug.apk
adb shell am start -n Thanh.TaxiDriver.AALauncher/.ui.MainActivity
adb shell pm list packages | grep -F Thanh.TaxiDriver.AALauncher
adb shell dumpsys package Thanh.TaxiDriver.AALauncher
adb logcat -c
# Reproduce scenario, then:
adb logcat -d -v time > phase11b-logcat.txt
```

With an attached emulator/phone (outside GitHub-hosted CI):

```bash
gradle :app:connectedDebugAndroidTest
```

The `scripts/check_merged_manifests.py` gate runs in CI to
distinguish debug Navigation from phone-only release. Run the
Desktop Head Unit according to Android documentation:
https://developer.android.com/training/cars/testing/dhu

## Evidence checklist

For every test ID record date/time, device model, OS version, AA/DHU
version, app SHA/versionCode, expected and actual result, screenshot or
anonymized log, issue link and reproducibility. Do not upload raw GPS
traces, QR payment credentials or other private files to public issues.

**Deferred:** on-device CPU/RAM/latency benchmarks for GPS road matching
and graph, as requested. Functional map/GPS reliability must still be
tested.

## Phase 11C entry criteria

Phone and debug DHU functional acceptance, R01 pass, no P0 data loss
or crashes; approved production release signer; chosen Android Auto
product category (phone-only release until NF-1..NF-7). A signed release
must not be published solely on the basis of a green debug CI.

## Requested screenshot-like layout — debug-only prototype

The phone Configuration Center exposes an opt-in experimental Android Auto
multi-tile preview in **debug builds only**. It uses Phase 6's same eight
presets, persisted layout tree, calendar and selected QR. The existing
Android Auto debug `NavigationTemplate` Surface may render the full
Dashboard when the switch is enabled. This is **NOT compliant with NF-2**,
not appropriate for driving, not eligible for Google Play Android Auto
publication, and is not included in production's CarService manifest.
The switch defaults OFF, so debug starts on the map-only surface.
