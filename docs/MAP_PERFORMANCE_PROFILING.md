# Phase 2 — Debug performance profiling

The Android Auto map in `NativeMap` now has a small, lifecycle-aware
`MapPerformanceMonitor` used **only for debuggable APKs**.

## What the instrumentation measures

- `delivered_frame_fps`: number of frames successfully copied from
  MapLibre's `ImageReader` per elapsed wall-clock window (10 seconds).
  **This is not physical screen refresh rate, host FPS, nor proof of smooth rendering.**
- `copy_avg_ms`, `copy_max_ms`: time to copy the received image buffer.
- `gps_avg_ms`, `gps_max_ms`: time spent processing a location update,
  including Kalman update and local road matching (when executed).
- `step_avg_ms`, `step_max_ms`: periodic camera/map tick elapsed time.
- `java_heap_mb`: Java/Kotlin heap usage at the report instant, **not** total
  Android process memory or GPU memory.
- `initial_map_style_ms`: duration from starting a virtual display to the
  first successful style callback. A fresh run is measured independently.

Logcat tag: `CarTaxiPerf`. Aggregates are recorded once per 10 seconds;
no latitude, longitude, destination, road name, or trip history is logged.
Each run resets the counters and map-load timer. The release APK does not
create profiler samples or log metrics.

## Collect data on hardware

```bash
adb logcat -s CarTaxiPerf:D *:S
```

Collect runs with a stationary vehicle, driving in open sky, dense urban
roads, bridge/tunnel transitions, pan/zoom/rotate interactions and Android
Auto reconnect. Compare an equivalent route/device/Android Auto host with
the prior commit. Record CPU/GPU profiling and Android Studio Memory
Profiler separately, because these aggregates cannot measure them.

## Status

Unit tests confirm window boundaries, rates, averages, no synthetic FPS
when there are no frames, map-load duration, counter resets and a privacy-safe
log format. No device, Android Auto head unit, GPS replay or real-world
performance benchmarks have been executed by this change.
