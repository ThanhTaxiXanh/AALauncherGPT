# Phase 11A decision and 11B handoff

Decision: **do not mislabel the current map/signs Dashboard as a
turn-by-turn Navigation app in production**. Android Auto Navigation
requires NF-1 through NF-7; real routing and navigation lifecycle
have not been implemented or verified.

Implementation:
- `app/src/main/AndroidManifest.xml`: **phone-only** components and
  services; no CarAppService, Navigation category, car meta-data or
  Navigation Surface permissions.
- `app/src/debug/AndroidManifest.xml`: CarAppService, Navigation
  category and car meta-data **only for developer DHU/host testing**.
- `PhoneDashboardActivity` and all QR, lunar calendar, media,
  report, GPS and road-matching code remain available on phone.
- `scripts/check_merged_manifests.py` validates final merged
  debug/release manifests in CI; source `scripts/phase11a_car_policy_gate.py`
  checks render separation and holds release.
- No silent replacement of Navigation with an unrelated car category.
- **No false claim** to implement turn-by-turn or to have earned Android
  Auto approval. Returning to a production car Navigation app will
  require destination/route calculation, turn instructions, ETA,
  `NavigationManager` trip/cluster metadata, notifications, intents,
  host stop, autodrive simulation and live host validation.

Phase 11B is prepared via `docs/PHASE11B_DEVICE_TEST_MATRIX.md`.
Instrumented tests can compile in CI; physical Android devices and
DHU sessions are **not available inside GitHub-hosted CI**, so do not
mark Phase 11B as complete without field evidence.

On-device GPS matcher/road graph profiling is deferred per request.
