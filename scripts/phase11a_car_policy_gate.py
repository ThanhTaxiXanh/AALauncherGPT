#!/usr/bin/env python3
"""Fail-closed review of Android Auto variants, not certification."""
import argparse
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
NAV = "androidx.car.app.category.NAVIGATION"


def declared_navigation(manifest: str) -> bool:
    root = ET.fromstring(manifest)
    return any(
        node.get(ANDROID + "name") == NAV
        for node in root.findall(".//service/intent-filter/category")
    )


def audit(manifest: str, car: str, renderer: str) -> dict[str, bool]:
    nav = declared_navigation(manifest)
    routing = all(x in car for x in (
        "NavigationManager", "navigationStarted(", "Trip.Builder"))
    render_nav = "renderer.renderNavigationSurface(" in car
    map_only = False
    if "fun renderNavigationSurface(" in renderer:
        segment = renderer.split("fun renderNavigationSurface(", 1)[1]
        segment = segment.split("// ----------", 1)[0]
        map_only = not any(x in segment for x in (
            "drawCalendar(", "drawQr(", "drawIcons(", "drawSigns("))
    restricted = ("FLAG_DEBUGGABLE" in car and
                  "ALLOW_ALL_HOSTS_VALIDATOR" in car)
    return {
        "navigation_category": nav,
        "nf1_routing_indicators_present": routing,
        "nf2_map_only_surface": render_nav and map_only,
        "release_host_validator_restricted": restricted
    }


def evaluate(root: pathlib.Path) -> dict[str, object]:
    path = root / "app/src"
    main = (path / "main/AndroidManifest.xml").read_text(encoding="utf-8")
    debug = (path / "debug/AndroidManifest.xml").read_text(encoding="utf-8")
    car = (path / "main/java/Thanh/TaxiDriver/AALauncher/car/CarService.kt").read_text(encoding="utf-8")
    renderer = (path / "main/java/Thanh/TaxiDriver/AALauncher/dashboard/LauncherRenderer.kt").read_text(encoding="utf-8")
    info = audit(debug, car, renderer)
    info["production_navigation_declared"] = declared_navigation(main)
    info["debug_navigation_declared"] = info["navigation_category"]
    info["debug_only_car_service"] = (
        not declared_navigation(main)
        and "android:name=\".car.CarService\"" not in main
        and "android:name=\".car.CarService\"" in debug
    )
    return info


def main() -> int:
    parser = argparse.ArgumentParser()
    flags = parser.add_mutually_exclusive_group(required=True)
    flags.add_argument("--audit", action="store_true")
    flags.add_argument("--release", action="store_true")
    args = parser.parse_args()
    result = evaluate(ROOT)
    print(json.dumps(result, indent=2, sort_keys=True))
    isolated = bool(
        result["debug_only_car_service"] and
        result["nf2_map_only_surface"] and
        result["release_host_validator_restricted"])
    if args.audit:
        if not isolated:
            print("ERROR: development-only Navigation isolation regressed", file=sys.stderr)
            return 1
        print("PASS: development-only CarService isolated. NF-1 remains unimplemented.")
        return 0

    # Automatic official publication still requires Phase 11C signing,
    # host policy review and explicit human approval. Do not auto-publish.
    print("BLOCKED: Phase 11C release approval and physical device tests "
          "are not complete. Official Android Auto Navigation is NOT included "
          "in release manifest.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
