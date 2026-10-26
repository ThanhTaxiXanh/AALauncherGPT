#!/usr/bin/env python3
"""Stream an authorized OSM .pbf file to compact per-region sign GeoJSON.

Requires pip install osmium. Input can be Geofabrik Vietnam OSM PBF.
Use --bbox west,south,east,north to keep the generated file regional.
No data are retrieved automatically; do not use vendor-owned data.
"""
import argparse
import json
import pathlib
import re
import sys

ALLOWED = {"speed","camera","redlight_camera","stop","yield","no_entry",
           "no_overtaking","no_parking","school","bump","signals","railway","curve","other"}


def convert(tags):
    hw = tags.get("highway","")
    ts = (tags.get("traffic_sign") or tags.get("traffic_sign:forward")
          or tags.get("traffic_sign:backward") or "").lower()
    if hw == "speed_camera" or tags.get("enforcement") == "maxspeed":
        return "camera", 0
    if tags.get("enforcement") == "traffic_signals":
        return "redlight_camera", 0
    if hw == "stop": return "stop", 0
    if hw == "give_way": return "yield", 0
    if hw == "traffic_signals": return "signals", 0
    if "traffic_calming" in tags: return "bump", 0
    if "no_entry" in ts or "p.102" in ts: return "no_entry", 0
    if "no_overtaking" in ts or "p.125" in ts: return "no_overtaking", 0
    if "no_parking" in ts or "p.131a" in ts: return "no_parking", 0
    if "school" in ts or "children" in ts or "w.225" in ts:
        return "school", 0
    if "railway_crossing" in ts: return "railway", 0
    if "dangerous_curve" in ts or "w.201" in ts: return "curve", 0
    speed = tags.get("maxspeed","")
    if not speed:
        result = re.search(r"(?:maxspeed|p\\.127).*?\\[(\\d{1,3})\\]", ts)
        speed = result.group(1) if result else ""
    match = re.fullmatch(r"\\s*(\\d{1,3})(?:\\s*km/h)?\\s*", speed)
    if match and 5 <= int(match.group(1)) <= 160:
        return "speed", int(match.group(1))
    if ts: return "other", 0
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf", type=pathlib.Path)
    parser.add_argument("--bbox", required=True, help="west,south,east,north")
    parser.add_argument("--region", required=True,
                        help="lowercase id, e.g. bac_ninh (2..45 chars)")
    parser.add_argument("--out-dir", type=pathlib.Path, required=True)
    parser.add_argument("--max-features", type=int, default=4000)
    a = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9_-]{2,45}", a.region):
        parser.error("invalid region key")
    coords = [float(x) for x in a.bbox.split(",")]
    if len(coords) != 4 or not (coords[0] < coords[2] and
         coords[1] < coords[3] and -180 <= coords[0] <= 180 and
         -180 <= coords[2] <= 180 and -90 <= coords[1] <= 90 and
         -90 <= coords[3] <= 90):
        parser.error("invalid bbox")
    if not 1 <= a.max_features <= 20000:
        parser.error("max-features out of range")
    try:
        import osmium
    except ImportError:
        parser.error("Install pyosmium: pip install osmium")
    a.out_dir.mkdir(parents=True, exist_ok=True)

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.part = 0
            self.features = []
            self.total = 0

        def flush(self):
            if not self.features:
                return
            # Split large provinces to safely fit import caps.
            key = f"{a.region}_{self.part:03d}"
            path = a.out_dir / f"{key}.geojson"
            result = {"type":"FeatureCollection","features":self.features}
            path.write_text(json.dumps(result, ensure_ascii=False, separators=(",",":")),
                            encoding="utf-8")
            print(f"{path}: {len(self.features)} features")
            self.features = []
            self.part += 1

        def node(self, node):
            if not node.location.valid():
                return
            lon, lat = node.location.lon, node.location.lat
            if not (coords[0] <= lon <= coords[2] and
                    coords[1] <= lat <= coords[3]):
                return
            tags = dict(node.tags)
            val = convert(tags)
            if val is None:
                return
            kind, speed = val
            feature = {"type":"Feature",
                       "geometry":{"type":"Point","coordinates":[lon,lat]},
                       "properties":{"osm_id":f"node/{node.id}",
                                     "source":"OpenStreetMap","type":kind}}
            if kind == "speed":
                feature["properties"]["value"] = speed
            self.features.append(feature)
            self.total += 1
            if len(self.features) == a.max_features:
                self.flush()

    handler = Handler()
    handler.apply_file(str(a.pbf), locations=False)
    handler.flush()
    print("Total OSM sign/camera points:", handler.total)
    print("Output data copyright OpenStreetMap contributors, ODbL.")


if __name__ == "__main__":
    main()
