#!/usr/bin/env python3
"""Build actual nationwide OSM traffic-sign and speed-way SQLite from Geofabrik PBF.

Requires pyosmium. Never fabricate speed limits, cameras, or sign positions.
Way speed records are separately labelled as ROAD attributes, NOT roadside signs.
"""
import argparse
import collections
import datetime
import json
import pathlib
import re
import sqlite3

import osmium

KINDS = ("speed", "camera", "redlight_camera", "stop", "yield", "no_entry",
         "no_overtaking", "no_parking", "school", "bump", "signals",
         "railway", "curve", "other")
NUM_SPEED = re.compile(r"^\s*(\d{1,3})(?:\s*km/h)?\s*$", re.I)
ROAD_TYPES = {"motorway","trunk","primary","secondary","tertiary",
              "unclassified","residential","living_street","service",
              "motorway_link","trunk_link","primary_link","secondary_link",
              "tertiary_link"}

def speed_value(text):
    m = NUM_SPEED.fullmatch((text or "").strip())
    return int(m.group(1)) if m and 5 <= int(m.group(1)) <= 160 else 0

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
    # A way's maxspeed is NOT a physical sign; only explicit node tags here.
    sp = speed_value(tags.get("maxspeed", ""))
    if sp and (sign or tags.get("traffic_sign") or tags.get("highway") == "speed_limit"):
        return "speed", sp
    if sign:
        m = re.search(r"(?:p\.127|speed)[^0-9]*(\d{2,3})", sign)
        if m and 5 <= int(m.group(1)) <= 160: return "speed", int(m.group(1))
        return "other", 0
    return None

def create_db(path):
    if path.exists(): path.unlink()
    db = sqlite3.connect(path)
    db.executescript("""
    PRAGMA journal_mode=DELETE;
    PRAGMA synchronous=OFF;
    CREATE TABLE regions(region TEXT PRIMARY KEY,updated_ms INTEGER NOT NULL,feature_count INTEGER NOT NULL);
    CREATE TABLE markers(region TEXT NOT NULL,id TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,
        type TEXT NOT NULL,label TEXT NOT NULL,speed_value INTEGER NOT NULL,
        source TEXT NOT NULL,osm_id TEXT NOT NULL,PRIMARY KEY(region,id));
    CREATE INDEX idx_region_lat_lon ON markers(lat,lon);
    CREATE TABLE road_speeds(osm_id TEXT PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL,
        highway TEXT NOT NULL,name TEXT NOT NULL,maxspeed INTEGER NOT NULL,
        forward_speed INTEGER NOT NULL,backward_speed INTEGER NOT NULL,
        conditional TEXT NOT NULL,direction TEXT NOT NULL);
    CREATE INDEX idx_road_speeds_coords ON road_speeds(lat,lon);
    """)
    return db

def main():
    p = argparse.ArgumentParser()
    p.add_argument("pbf", type=pathlib.Path)
    p.add_argument("--output", required=True, type=pathlib.Path)
    p.add_argument("--stats", required=True, type=pathlib.Path)
    args = p.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    db = create_db(args.output)
    counts = collections.Counter()
    seen_nodes = set()

    class Extract(osmium.SimpleHandler):
        def node(self,node):
            if not node.location.valid(): return
            tags = dict(node.tags)
            classified = sign_type(tags)
            if not classified: return
            kind, speed = classified
            id = f"node/{node.id}"
            if id in seen_nodes: return
            seen_nodes.add(id)
            label = str(speed) if speed else {"camera":"CAM","redlight_camera":"CAM",
                "stop":"STOP","yield":"NHƯỜNG","signals":"ĐÈN","school":"HS",
                "no_entry":"CẤM"}.get(kind, "!")
            db.execute("INSERT INTO markers VALUES (?,?,?,?,?,?,?,?,?)",
                       ("vn",id,node.location.lat,node.location.lon,kind,label,
                        speed,"OpenStreetMap",id))
            counts["sign_"+kind] += 1

        def way(self,way):
            tags = dict(way.tags)
            hw = tags.get("highway","")
            if hw not in ROAD_TYPES: return
            all_speed = speed_value(tags.get("maxspeed",""))
            fwd = speed_value(tags.get("maxspeed:forward",""))
            bwd = speed_value(tags.get("maxspeed:backward",""))
            cond = tags.get("maxspeed:conditional","")
            if not (all_speed or fwd or bwd or cond): return
            valid = [n for n in way.nodes if n.location.valid()]
            if not valid: return
            # Coordinates represent an OSM WAY anchor, not a mapped road sign.
            mid = valid[len(valid)//2].location
            db.execute("INSERT OR REPLACE INTO road_speeds VALUES (?,?,?,?,?,?,?,?,?,?)",
                       (f"way/{way.id}",mid.lat,mid.lon,hw,tags.get("name","")[:140],
                        all_speed,fwd,bwd,cond[:240],
                        tags.get("oneway","")[:16]))
            counts["ways_speed_tagged"] += 1

    extract = Extract()
    # locations=True resolves way node coordinates; cache resides only on CI.
    extract.apply_file(str(args.pbf),locations=True,idx="sparse_mem_array")
    db.execute("INSERT INTO regions VALUES (?,?,?)",
               ("vn",int(datetime.datetime.now(datetime.timezone.utc).timestamp()*1000),
                sum(v for k,v in counts.items() if k.startswith("sign_"))))
    db.commit()
    db.execute("PRAGMA optimize")
    db.close()
    stats = {"source":"OpenStreetMap / Geofabrik Vietnam",
             "license":"ODbL-1.0","attribution":"© OpenStreetMap contributors",
             "input_filename":args.pbf.name,
             "sign_nodes":sum(v for k,v in counts.items() if k.startswith("sign_")),
             "road_speed_ways":counts["ways_speed_tagged"],
             "classes":dict(sorted(counts.items())),
             "sqlite_bytes":args.output.stat().st_size}
    args.stats.parent.mkdir(parents=True,exist_ok=True)
    args.stats.write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(stats,ensure_ascii=False))
    assert stats["sign_nodes"] > 0 and stats["road_speed_ways"] > 0, (
        "Fail closed: OSM input yielded no real signs or no speed-tagged ways")

if __name__ == "__main__":
    main()
