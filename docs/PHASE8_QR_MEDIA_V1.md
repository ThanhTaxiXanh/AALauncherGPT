# Phase 8 — QR Library and Media3 local audio V1

## QR library

The phone now imports, lists, renames, selects, deletes and exports up to
30 QR images. The index is private `filesDir/qr_library/index.json`,
each image is saved losslessly as an unscaled PNG file, and the
**selected QR is mirrored to the existing `filesDir/qr.png`** for the
unchanged Android Auto QR widget. Import checks decoded bitmap bounds
before loading, rejects dimensions above 2600×2600 and PNG payload
larger than 10 MB. No trimming, interpolation or resampling occurs
during import; rendering continues to use nearest-neighbor resizing
and white quiet zones.

On first use, an existing legacy `qr.png` is copied into the library as
the first selected entry, without deleting or modifying that original
image. The library metadata is versioned and atomically replaced with
fsync; corrupt or unsupported schema is **not reset silently**. Deleting
a QR requires confirmation; selecting another QR changes the dashboard
mirror while retaining the stored original. The user may export an
individual selected PNG with Android's document picker.

This is an image QR library, not a reader or generator of payment
credentials. Do not assume a QR contains safe content merely because
it is displayed.

## Media3 local player

A separate `LocalAudioService : MediaSessionService` owns
Media3 ExoPlayer and MediaSession. The phone can explicitly select a
local audio file through `ACTION_OPEN_DOCUMENT`, persist granted URI
read access, and issue play/pause/stop through MediaController.
Playback is user initiated; there is no automatic playback, remote
download, forced audio ducking or background microphone use.

The system's media controls, headset buttons and other authorized
MediaSession controllers may handle playback; the existing Android Auto
navigation CarAppService and NavigationTemplate are left unchanged.
This increment does **not** claim to provide a full Android Auto media
library, browse service, playlist queue or custom media buttons drawn
on the navigation canvas. Media3 1.4.1 is pinned to match this
project's compileSdk 34; upgrades can be evaluated separately.

The service declares foreground media playback permissions and the
mediaPlayback service type. It does not read or enumerate arbitrary
files: only the URI explicitly selected through the system file picker
is supplied to ExoPlayer. Revoked document permissions are handled
as an error and the user can choose the file again.

## CI and remaining testing

JVM tests cover legacy QR preservation, multiple entries, persistent
selection, rename, deletion, invalid metadata, limits and file-path
validation. All previous GPS, sign, calendar, report and layout tests
also run in GitHub Actions.

Device tests remain required for Media3 notification, audio focus,
Android Auto interoperability, QR scanning at real head-unit
resolutions, and document-provider permission persistence. The deferred
GPS matching/road graph CPU/RAM/latency measurement remains deferred.
