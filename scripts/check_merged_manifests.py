#!/usr/bin/env python3
"""Inspect the *merged* Android manifest, not only source XML."""
import pathlib
import sys
import xml.etree.ElementTree as ET

def check(path: str, variant: str) -> bool:
    doc = ET.parse(path).getroot()
    a = "{http://schemas.android.com/apk/res/android}"
    services = doc.findall(".//application/service")
    has_nav = any(cat.get(a + "name") == "androidx.car.app.category.NAVIGATION"
                  for srv in services for cat in
                  srv.findall("intent-filter/category"))
    has_car = any((srv.get(a + "name") or "").endswith(".car.CarService")
                  for srv in services)
    metadata = doc.findall(".//application/meta-data")
    has_automotive = any(x.get(a + "name") == "com.google.android.gms.car.application"
                         for x in metadata)
    if variant == "--release":
        return not (has_nav or has_car or has_automotive)
    return has_nav and has_car and has_automotive

if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[2] not in ("--release", "--debug"):
        raise SystemExit("Usage: check_merged_manifests.py path --release|--debug")
    ok = check(sys.argv[1], sys.argv[2])
    print(f"{sys.argv[2][2:]} Android Auto manifest segregation: {'PASS' if ok else 'FAIL'}")
    raise SystemExit(0 if ok else 1)
