# Phase 4 — Traffic Reports V2: edit, privacy, export and map overlay

## Phone UI

In `MainActivity`, each existing local report has a **Sửa** (edit)
action. Editing may change incident type, severity, note and whether
a location remains attached. The original report ID and creation
timestamp remain stable. Removing an existing location does not require
GPS permission. Adding new GPS to a report uses the same explicit
choice and accuracy/freshness checks as creation.

The privacy controls below the list are phone-only:
- **Show my reports on the map:** off by default, persisted in a private
  preference `traffic_report_privacy.show_map`.
- **Erase all stored GPS:** explicit confirmation; strips latitude,
  longitude and accuracy from every locally saved report in one atomic
  storage update. Notes, types and timestamps are preserved. The operation
  cannot erase files that users exported earlier.
- **Export reports JSON:** Android's system document picker
  (`ACTION_CREATE_DOCUMENT`) writes an explicitly chosen file. The export
  omits all GPS keys by default. Including GPS requires opting in for
  that particular export and the UI warns about location disclosure.
  No sharing intent, background export, server upload or permission to
  write public storage is used.

The local storage remains version 1 to preserve compatibility with V1
reports. Failed edits or corrupted files never reset the archive.
JSON exports carry `scope=personal_unverified` and
`verified=false` for individual reports.

## Personal map overlay

`NativeMap` has a separate `ct-personal-reports` GeoJSON source and
MapLibre layers. These display **CN?** in brown, explicitly distinct
from verified traffic signs and grey offline OSM hints. The overlay:
- is disabled by default and uses the opt-in phone setting;
- reads the local report JSON on a background executor, not the map
  rendering loop; changes are polled roughly every five seconds;
- shows only reports with opted-in saved GPS, younger than seven days,
  within 2 km of current GPS position, up to forty closest markers;
- does **not** show report note text, report ID or precise coordinates
  as map labels; the map source necessarily contains the geometry of
  visible reports;
- never affects road matching, the speed limit, or live upcoming sign
  warnings. Reports are private annotations, not authoritative incidents.

There is no remote syncing or public verification workflow in this phase.

## Tests and limits

Local unit tests cover edits and restarts, removal of GPS, bulk privacy
erasure, both export modes, data corruption, map opt-in, expiry, distance
and marker caps. GitHub Actions builds a debug APK and runs all unit
tests. Actual Android Auto hardware verification and detailed
GPS/road graph performance measurements remain deferred as requested.
