#!/usr/bin/env python3
"""Phase 11A: fail-closed Android Auto release compliance review gate.

This is a static *triage* gate, not Google certification. A release also
requires a documented, manual DHU/head-unit review. No PR/CI checkbox can
automatically establish NF-1/NF-2 regulatory compliance.
"""
import argparse
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


def audit(manifest: str, car: str, renderer: str) -> dict[str, bool]:
    root = ET.fromstring(manifest)
    nav = False
    for service in root.findall(".//service"):
        for cat in service.findall(".//category"):
            if cat.get(ANDROID + "name") == "androidx.car.app.category.NAVIGATION":
                nav = True

    # Conservative source guard: APIs are only indicators, not a proof of
    # functional routing, safe driving UX or Google Play acceptance.
    routing_indicators = all(needle in car for needle in (
        "NavigationManager", "navigationStarted(", "Trip.Builder"
    ))
    car_uses_map_surface = "renderer.renderNavigationSurface(" in car
    map_only = False
    if "fun renderNavigationSurface(" in renderer:
        area = renderer.split("fun renderNavigationSurface(", 1)[1]
        area = area.split("// ----------", 1)[0]
        map_only = all(needle not in area for needle in (
            "drawCalendar(", "drawQr(", "drawIcons(", "drawSigns("
        ))
    host_restricted = ("FLAG_DEBUGGABLE" in car and
                       "ALLOW_ALL_HOSTS_VALIDATOR" in car)
    return {
        "navigation_category": nav,
        "nf1_routing_indicators_present": routing_indicators,
        "nf2_map_only_surface": car_uses_map_surface and map_only,
        "release_host_validator_restricted": host_restricted,
    }


def evaluate(root: pathlib.Path) -> dict[str, bool]:
    source = root / "app/src/main"
    manifest = (source / "AndroidManifest.xml").read_text(encoding="utf-8")
    car = (source / "java/Thanh/TaxiDriver/AALauncher/car/CarService.kt").read_text(encoding="utf-8")
    renderer = (source / "java/Thanh/TaxiDriver/AALauncher/dashboard/LauncherRenderer.kt").read_text(encoding="utf-8")
    return audit(manifest, car, renderer)


def main() -> int:
    args = argparse.ArgumentParser()
    mode = args.add_mutually_exclusive_group(required=True)
    mode.add_argument("--audit", action="store_true")
    mode.add_argument("--release", action="store_true")
    flags = args.parse_args()
    found = evaluate(ROOT)
    print(json.dumps(found, indent=2, ensure_ascii=False, sort_keys=True))
    compliant_sources = (
        found["nf2_map_only_surface"]
        and found["release_host_validator_restricted"]
        and (not found["navigation_category"] or found["nf1_routing_indicators_present"])
    )
    print("Phase 11A code audit:", "no known source flags" if compliant_sources else
          "RELEASE NOT READY — Navigation NF-1, NF-2 or host issue remains")
    if flags.audit:
        print("Debug verification continues. Manual car-host review is outstanding.")
        return 0

    # No automatic permission to publish from a passing source heuristic.
    # Require formal documented human review and an explicit gate revision.
    # Currently blocked even if the source heuristic passes.
    print("BLOCKED: Android Auto publication requires a compliant category, "
          "real navigation where applicable, DHU/host testing and approval. "
          "Phase 11A release gate is intentionally closed.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
