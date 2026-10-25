# Phase 4 — Traffic Reports V1 (offline-first, phone UI)

## Current scope

A new **traffic report section on the Android phone** supports local creation,
listing and deletion. Available types: congestion, collision, roadworks,
closure, hazard, other; severity: low, medium, high. All entries are explicitly
**local, unpublished and unverified**. No report is sent to Overpass or to
an external server. No Android Auto report-creation action is introduced.

Users may **opt in** to attaching the latest *GPS_PROVIDER* location.
If selected, the application requires fine-location permission, accuracy
≤50 m and a location younger than 2 minutes. The user can see coordinates
and accuracy before saving. The default is **no GPS stored**.
Any report without coordinates remains a local phone note and cannot be
used for location-specific map alerts.

## Local persistence

`TrafficReportStore` saves a versioned JSON file
`filesDir/traffic_reports_v1.json`. Writes use a temp file, fsync and
atomic replacement when supported. A corrupt or unknown schema is not
silently treated as an empty list, preventing accidental overwrites.
The file is bounded to 512 kB and 300 entries. At capacity the user must
explicitly delete an entry; there is **no automatic retention cleanup**.
Data is isolated from existing QR and calendar storage. The app's original
package ID and release-signing requirements are unchanged.

Stored reports can be deleted individually after confirmation. No public
sharing, server submission, GPS background tracking, moderator workflow,
route-based aggregation or cross-device synchronization exists in V1.

## Verification

Local JVM tests use the real `org.json` parser and temporary files. Cases
include persistence across simulated app restarts, optional GPS, deletion,
corrupt/unknown schemas, invalid GPS, 300-entry capacity, and duplicate IDs.
The normal CI `:app:assembleDebug` and `:app:testDebugUnitTest` gates apply.

## Deferred work

The requested Android-device measurement of GPS matching and road graph
CPU/RAM/latency remains **deferred**. Phase 4 can be expanded with report
editing, privacy controls, an explicit export format, optional offline map
overlays for unverified user notes, and only later an approved distribution
service with abuse and privacy safeguards.
