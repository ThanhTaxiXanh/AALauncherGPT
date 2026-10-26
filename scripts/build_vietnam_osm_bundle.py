#!/usr/bin/env python3
"""Build nationwide OSM traffic-sign and compact road-context SQLite.

V3 keeps every supported road with a speed tag plus nearby competing motor
roads. Context-only roads exist solely to prevent a speed-tagged parallel road
from lending its limit to the vehicle's actual road. No speed is fabricated.
"""
import argparse
import collections
import datetime
import json
import math
import pathlib
import re
import sqlite3

import osmium

KINDS = ("speed", "camera", "redlight_camera", "stop", "yield", "no_entry",
         "no_overtaking", "no_parking", "school", "bump", "signals",
         "railway", "curve", "other")
NUM_SPEED = re.compile(r"^\s*(\d{1,3})(?:\s*(mph|km/h|kmh|kph))?\s*$", re.I)
ROAD_TYPES = {"motorway","trunk","primary","secondary","tertiary",
              "unclassified","residential","living_street","service",
              "motorway_link","trunk_link","primary_link","secondary_link",
              "tertiary_link"}
SPEED_TAGS = ("maxspeed","maxspeed:forward","maxspeed:backward",
              "maxspeed:conditional","maxspeed:forward:conditional",
              "maxspeed:backward:conditional")

# Vietnam is roughly 8..24 N. 0.001 degrees is about 102..111 m there.
# SpeedWayMatcher's GPS gate is <=25 m; this larger halo preserves nearby
# frontage/service/parallel roads without embedding the entire road network.
CONTEXT_HALO_DEG = 0.001
GRID_DEG = 0.02

def speed_value(text):
    raw = (text or "").strip().lower()
    if raw == "walk":
        return 7
    m = NUM_SPEED.fullmatch(raw)
    if not m:
        return 0
    value = int(m.group(1))
    if (m.group(2) or "").lower() == "mph":
        value = round(value * 1.609344)
    return value if 5 <= value <= 160 else 0

def has_speed_tag(tags):
    return any((tags.get(k) or "").strip() for k in SPEED_TAGS)

def sign_type(tags):
    hw = tags.get("highway", "")
    sign = (";".join(tags.get(k, "") for k in
            ("traffic_sign","traffic_sign:forward","traffic_sign:backward"))).lower()
    if hw == "speed_camera" or tags.get("enforcement") == "maxspeed":
        return "camera", 0
    if tags.get("enforcement") == "traffic_signals": return "redlight_camera", 0
    if hw == "stop": return "stop", 0
    if hw == "give_way": return "yield", 0
    if hw == "traffic_signals": return "signals", 0
    if tags.get("traffic_calming"): return "bump", 0
    for match, kind in (("p.102","no_entry"),("no_entry","no_entry"),
                        ("p.125","no_overtaking"),("no_overtaking","no_overtaking"),
                        ("p.131","no_parking"),("no_parking","no_parking"),
                        ("w.225","school"),("children","school"),
                        ("school","school"),("w.201","curve"),
                        ("dangerous_curve","curve"),("railway_crossing","railway")):
        if match in sign: return kind, 0
    sp = speed_value(tags.get("maxspeed", ""))
    if sp and (sign or tags.get("traffic_sign") or tags.get("highway") == "speed_limit"):
        return "speed", sp
    if sign:
        m = re.search(r"(?:p\.127|speed)[^0-9]*(\d{2,3})", sign)
        if m and 5 <= int(m.group(1)) <= 160: return "speed", int(m.group(1))
        return "other", 0
    return None

def oneway_value(tags):
    raw = (tags.get("oneway") or "").strip().lower()
    if raw in ("yes", "true", "1"): return 1
    if raw == "-1": return -1
    if raw in ("no", "false", "0"): return 0
    if not raw and tags.get("junction") == "roundabout": return 1
    return 0 if not raw else 2

def level_value(tags):
    try:
        return max(-20, min(20, int(tags.get("layer", "0"))))
    except ValueError:
        if tags.get("bridge") and tags.get("bridge") != "no": return 1
        if tags.get("tunnel") and tags.get("tunnel") != "no": return -1
        return 0

def bbox(points):
    lons = [p[0] for p in points]
    lats = [p[1] for p in points]
    return min(lons), max(lons), min(lats), max(lats)

def grid_cells(box):
    min_lon,max_lon,min_lat,max_lat = box
    x0 = math.floor(min_lon / GRID_DEG)
    x1 = math.floor(max_lon / GRID_DEG)
    y0 = math.floor(min_lat / GRID_DEG)
    y1 = math.floor(max_lat / GRID_DEG)
    for x in range(x0,x1+1):
        for y in range(y0,y1+1):
            yield x,y

def overlaps(a,b):
    return not (a[1] < b[0] or a[0] > b[1] or
                a[3] < b[2] or a[2] > b[3])

def create_db(path):
    if path.exists(): path.unlink()
    db = sqlite3.connect(path)
    db.executescript("""
    PRAGMA journal_mode=DELETE;
    PRAGMA synchronous=OFF;
    PRAGMA user_version=3;
    CREATE TABLE regions(region TEXT PRIMARY KEY,updated_ms INTEGER NOT NULL,feature_count INTEGER NOT NULL);
    CREATE TABLE markers(region TEXT NOT NULL,id TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,
        type TEXT NOT NULL,label TEXT NOT NULL,speed_value INTEGER NOT NULL,
        source TEXT NOT NULL,osm_id TEXT NOT NULL,PRIMARY KEY(region,id));
    CREATE INDEX idx_region_lat_lon ON markers(lat,lon);

    CREATE TABLE road_context(
        id INTEGER PRIMARY KEY,
        osm_way_id INTEGER NOT NULL UNIQUE,
        highway TEXT NOT NULL,
        name TEXT NOT NULL,
        maxspeed INTEGER NOT NULL,
        forward_speed INTEGER NOT NULL,
        backward_speed INTEGER NOT NULL,
        conditional TEXT NOT NULL,
        forward_conditional TEXT NOT NULL,
        backward_conditional TEXT NOT NULL,
        has_speed_tag INTEGER NOT NULL,
        oneway INTEGER NOT NULL,
        level INTEGER NOT NULL,
        geometry_json TEXT NOT NULL,
        min_lon REAL NOT NULL,
        max_lon REAL NOT NULL,
        min_lat REAL NOT NULL,
        max_lat REAL NOT NULL
    );
    CREATE INDEX idx_road_context_way ON road_context(osm_way_id);
    CREATE INDEX idx_road_context_highway ON road_context(highway);
    CREATE INDEX idx_road_context_speed_tag ON road_context(has_speed_tag);
    CREATE VIRTUAL TABLE road_context_rtree USING rtree(
        id,min_lon,max_lon,min_lat,max_lat
    );
    """)
    return db

def main():
    p = argparse.ArgumentParser()
    p.add_argument("pbf", type=pathlib.Path)
    p.add_argument("--output", required=True, type=pathlib.Path)
    p.add_argument("--stats", required=True, type=pathlib.Path)
    args = p.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True)

    # Pass 1: index only areas around roads that actually carry a speed tag.
    speed_boxes = []
    speed_grid = {}

    class SpeedAreaCollector(osmium.SimpleHandler):
        def way(self,way):
            tags = dict(way.tags)
            if tags.get("highway","") not in ROAD_TYPES or not has_speed_tag(tags):
                return
            points = [(n.location.lon,n.location.lat)
                      for n in way.nodes if n.location.valid()]
            if len(points) < 2:
                return
            min_lon,max_lon,min_lat,max_lat = bbox(points)
            expanded = (
                min_lon-CONTEXT_HALO_DEG, max_lon+CONTEXT_HALO_DEG,
                min_lat-CONTEXT_HALO_DEG, max_lat+CONTEXT_HALO_DEG
            )
            index = len(speed_boxes)
            speed_boxes.append(expanded)
            for cell in grid_cells(expanded):
                speed_grid.setdefault(cell, []).append(index)

    collector = SpeedAreaCollector()
    collector.apply_file(str(args.pbf),locations=True,idx="sparse_mem_array")
    if not speed_boxes:
        raise RuntimeError("No speed-tagged motor roads found in OSM input")

    def near_speed_area(box):
        seen = set()
        for cell in grid_cells(box):
            for index in speed_grid.get(cell, ()):
                if index in seen:
                    continue
                seen.add(index)
                if overlaps(box, speed_boxes[index]):
                    return True
        return False

    db = create_db(args.output)
    counts = collections.Counter()
    seen_nodes = set()

    # Pass 2: store all speed-tagged roads plus only nearby context competitors.
    class Extract(osmium.SimpleHandler):
        def node(self,node):
            if not node.location.valid() or len(node.tags) == 0: return
            tags = dict(node.tags)
            classified = sign_type(tags)
            if not classified: return
            kind, speed = classified
            oid = f"node/{node.id}"
            if oid in seen_nodes: return
            seen_nodes.add(oid)
            label = str(speed) if speed else {"camera":"CAM","redlight_camera":"CAM",
                "stop":"STOP","yield":"NHƯỜNG","signals":"ĐÈN","school":"HS",
                "no_entry":"CẤM"}.get(kind, "!")
            db.execute("INSERT INTO markers VALUES (?,?,?,?,?,?,?,?,?)",
                       ("vn",oid,node.location.lat,node.location.lon,kind,label,
                        speed,"OpenStreetMap",oid))
            counts["sign_"+kind] += 1

        def way(self,way):
            tags = dict(way.tags)
            hw = tags.get("highway","")
            if hw not in ROAD_TYPES:
                return
            points = [(n.location.lon,n.location.lat)
                      for n in way.nodes if n.location.valid()]
            if len(points) < 2:
                return
            box = bbox(points)
            speed_tag = has_speed_tag(tags)
            if not speed_tag and not near_speed_area(box):
                return

            all_speed = speed_value(tags.get("maxspeed",""))
            fwd = speed_value(tags.get("maxspeed:forward",""))
            bwd = speed_value(tags.get("maxspeed:backward",""))
            cond = (tags.get("maxspeed:conditional","") or "")[:240]
            fcond = (tags.get("maxspeed:forward:conditional","") or "")[:240]
            bcond = (tags.get("maxspeed:backward:conditional","") or "")[:240]
            min_lon,max_lon,min_lat,max_lat = box
            geometry = json.dumps(
                {"type":"LineString","coordinates":points},
                ensure_ascii=False,separators=(",",":")
            )
            cur = db.execute(
                """INSERT INTO road_context(
                    osm_way_id,highway,name,maxspeed,forward_speed,backward_speed,
                    conditional,forward_conditional,backward_conditional,
                    has_speed_tag,oneway,level,geometry_json,
                    min_lon,max_lon,min_lat,max_lat)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (way.id,hw,(tags.get("name","") or "")[:140],
                 all_speed,fwd,bwd,cond,fcond,bcond,1 if speed_tag else 0,
                 oneway_value(tags),level_value(tags),geometry,
                 min_lon,max_lon,min_lat,max_lat)
            )
            rid = cur.lastrowid
            db.execute("INSERT INTO road_context_rtree VALUES (?,?,?,?,?)",
                       (rid,min_lon,max_lon,min_lat,max_lat))
            counts["road_context_ways"] += 1
            counts["road_geometry_points"] += len(points)
            if speed_tag:
                counts["ways_speed_tagged"] += 1
            else:
                counts["context_only_ways"] += 1
            if all_speed or fwd or bwd:
                counts["ways_numeric_speed"] += 1

    extract = Extract()
    extract.apply_file(str(args.pbf),locations=True,idx="sparse_mem_array")
    db.execute("INSERT INTO regions VALUES (?,?,?)",
               ("vn",int(datetime.datetime.now(datetime.timezone.utc).timestamp()*1000),
                sum(v for k,v in counts.items() if k.startswith("sign_"))))
    db.commit()
    rtree_rows = db.execute("SELECT COUNT(*) FROM road_context_rtree").fetchone()[0]
    db.execute("PRAGMA optimize")
    db.close()

    stats = {
        "source":"OpenStreetMap / Geofabrik Vietnam",
        "license":"ODbL-1.0",
        "attribution":"© OpenStreetMap contributors",
        "input_filename":args.pbf.name,
        "context_halo_degrees":CONTEXT_HALO_DEG,
        "speed_area_count":len(speed_boxes),
        "sign_nodes":sum(v for k,v in counts.items() if k.startswith("sign_")),
        "road_context_ways":counts["road_context_ways"],
        "road_context_only_ways":counts["context_only_ways"],
        "road_speed_ways":counts["ways_speed_tagged"],
        "road_numeric_speed_ways":counts["ways_numeric_speed"],
        "road_geometry_points":counts["road_geometry_points"],
        "road_rtree_rows":rtree_rows,
        "classes":dict(sorted(counts.items())),
        "sqlite_bytes":args.output.stat().st_size
    }
    args.stats.parent.mkdir(parents=True,exist_ok=True)
    args.stats.write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(stats,ensure_ascii=False))

    assert stats["sign_nodes"] > 0
    assert stats["road_context_ways"] > stats["road_speed_ways"] > 0
    assert stats["road_context_only_ways"] > 0
    assert stats["road_rtree_rows"] == stats["road_context_ways"]
    assert stats["road_geometry_points"] >= stats["road_context_ways"] * 2
    # A guard against accidentally reverting to the 1.5 GB whole-network V3.
    assert stats["road_context_ways"] < 1_000_000

if __name__ == "__main__":
    main()
