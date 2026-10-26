#!/usr/bin/env python3
"""Build nationwide OSM traffic signs + compact V3 road context.

V3.1 keeps:
1) every supported motor-road way that has an explicit maxspeed* tag; and
2) untagged motor-road ways only inside a conservative spatial corridor
   around those speed-tagged ways.

This preserves the roads that can compete during GPS matching without
embedding the entire Vietnamese road network in the APK.
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

NUM_SPEED = re.compile(r"^\s*(\d{1,3})(?:\s*(mph|km/h|kmh|kph))?\s*$", re.I)
ROAD_TYPES = {"motorway","trunk","primary","secondary","tertiary",
              "unclassified","residential","living_street","service",
              "motorway_link","trunk_link","primary_link","secondary_link",
              "tertiary_link"}
SPEED_TAGS = ("maxspeed","maxspeed:forward","maxspeed:backward",
              "maxspeed:conditional","maxspeed:forward:conditional",
              "maxspeed:backward:conditional")

# Approx. 89-111 m per cell in Vietnam. One-cell expansion around every
# speed-way segment gives a conservative ~100 m+ context corridor.
CELL_DEG = 0.001
CELL_EXPAND = 1

def speed_value(text):
    raw = (text or "").strip().lower()
    if raw == "walk": return 7
    m = NUM_SPEED.fullmatch(raw)
    if not m: return 0
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
                        ("w.225","school"),("children","school"),("school","school"),
                        ("w.201","curve"),("dangerous_curve","curve"),
                        ("railway_crossing","railway")):
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
    if raw in ("yes","true","1"): return 1
    if raw == "-1": return -1
    if raw in ("no","false","0"): return 0
    if not raw and tags.get("junction") == "roundabout": return 1
    return 0 if not raw else 2

def level_value(tags):
    try:
        return max(-20, min(20, int(tags.get("layer", "0"))))
    except ValueError:
        if tags.get("bridge") and tags.get("bridge") != "no": return 1
        if tags.get("tunnel") and tags.get("tunnel") != "no": return -1
        return 0

def cell_index(lon, lat):
    return math.floor(lon / CELL_DEG), math.floor(lat / CELL_DEG)

def segment_cells(a, b, expand=0):
    x1,y1 = cell_index(a[0],a[1])
    x2,y2 = cell_index(b[0],b[1])
    for x in range(min(x1,x2)-expand, max(x1,x2)+expand+1):
        for y in range(min(y1,y2)-expand, max(y1,y2)+expand+1):
            yield (x,y)

def way_intersects_corridor(points, corridor):
    for a,b in zip(points, points[1:]):
        if any(cell in corridor for cell in segment_cells(a,b,0)):
            return True
    return False

def _put_varint(out, value):
    while value >= 0x80:
        out.append((value & 0x7f) | 0x80)
        value >>= 7
    out.append(value)

def _zigzag(value):
    return (value << 1) ^ (value >> 63)

def encode_geometry(points):
    """Delta + ZigZag + unsigned varint, lon/lat quantized to 1e-6 degree."""
    out=bytearray()
    prev_lat=0
    prev_lon=0
    for lon,lat in points:
        lat_e6=round(lat*1_000_000)
        lon_e6=round(lon*1_000_000)
        _put_varint(out,_zigzag(lat_e6-prev_lat))
        _put_varint(out,_zigzag(lon_e6-prev_lon))
        prev_lat=lat_e6
        prev_lon=lon_e6
    return bytes(out)

def create_db(path):
    if path.exists(): path.unlink()
    db=sqlite3.connect(path)
    db.executescript("""
    PRAGMA journal_mode=DELETE;
    PRAGMA synchronous=OFF;
    PRAGMA user_version=32;
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
        geometry_blob BLOB NOT NULL,
        min_lon REAL NOT NULL,max_lon REAL NOT NULL,
        min_lat REAL NOT NULL,max_lat REAL NOT NULL
    );
    CREATE INDEX idx_road_context_way ON road_context(osm_way_id);
    CREATE INDEX idx_road_context_speed_tag ON road_context(has_speed_tag);
    CREATE VIRTUAL TABLE road_context_rtree USING rtree(
        id,min_lon,max_lon,min_lat,max_lat
    );
    """)
    return db

def points_for_way(way):
    return [(n.location.lon,n.location.lat)
            for n in way.nodes if n.location.valid()]

def main():
    p=argparse.ArgumentParser()
    p.add_argument("pbf",type=pathlib.Path)
    p.add_argument("--output",required=True,type=pathlib.Path)
    p.add_argument("--stats",required=True,type=pathlib.Path)
    args=p.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True)

    # Pass 1: index only corridors around explicit speed-tagged motor roads.
    corridor=set()
    speed_way_ids=set()
    class SpeedCorridor(osmium.SimpleHandler):
        def way(self,way):
            tags=dict(way.tags)
            if tags.get("highway","") not in ROAD_TYPES or not has_speed_tag(tags):
                return
            points=points_for_way(way)
            if len(points)<2: return
            speed_way_ids.add(way.id)
            for a,b in zip(points,points[1:]):
                corridor.update(segment_cells(a,b,CELL_EXPAND))

    SpeedCorridor().apply_file(str(args.pbf),locations=True,idx="sparse_mem_array")
    if not speed_way_ids or not corridor:
        raise RuntimeError("No speed-tagged motor-road corridor was extracted")

    db=create_db(args.output)
    counts=collections.Counter()
    seen_nodes=set()

    # Pass 2: signs + speed ways + only nearby competing road context.
    class Extract(osmium.SimpleHandler):
        def node(self,node):
            if not node.location.valid() or len(node.tags)==0: return
            tags=dict(node.tags)
            classified=sign_type(tags)
            if not classified: return
            kind,speed=classified
            oid=f"node/{node.id}"
            if oid in seen_nodes: return
            seen_nodes.add(oid)
            label=str(speed) if speed else {"camera":"CAM","redlight_camera":"CAM",
                "stop":"STOP","yield":"NHƯỜNG","signals":"ĐÈN","school":"HS",
                "no_entry":"CẤM"}.get(kind,"!")
            db.execute("INSERT INTO markers VALUES (?,?,?,?,?,?,?,?,?)",
                       ("vn",oid,node.location.lat,node.location.lon,kind,label,
                        speed,"OpenStreetMap",oid))
            counts["sign_"+kind]+=1

        def way(self,way):
            tags=dict(way.tags)
            hw=tags.get("highway","")
            if hw not in ROAD_TYPES: return
            points=points_for_way(way)
            if len(points)<2: return
            speed_tagged = way.id in speed_way_ids
            if not speed_tagged and not way_intersects_corridor(points,corridor):
                return

            all_speed=speed_value(tags.get("maxspeed",""))
            fwd=speed_value(tags.get("maxspeed:forward",""))
            bwd=speed_value(tags.get("maxspeed:backward",""))
            cond=(tags.get("maxspeed:conditional","") or "")[:240]
            fcond=(tags.get("maxspeed:forward:conditional","") or "")[:240]
            bcond=(tags.get("maxspeed:backward:conditional","") or "")[:240]
            lons=[p[0] for p in points]; lats=[p[1] for p in points]
            geometry=encode_geometry(points)
            cur=db.execute(
                """INSERT INTO road_context(
                    osm_way_id,highway,name,maxspeed,forward_speed,backward_speed,
                    conditional,forward_conditional,backward_conditional,
                    has_speed_tag,oneway,level,geometry_blob,
                    min_lon,max_lon,min_lat,max_lat)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (way.id,hw,(tags.get("name","") or "")[:140],
                 all_speed,fwd,bwd,cond,fcond,bcond,1 if speed_tagged else 0,
                 oneway_value(tags),level_value(tags),geometry,
                 min(lons),max(lons),min(lats),max(lats)))
            rid=cur.lastrowid
            db.execute("INSERT INTO road_context_rtree VALUES (?,?,?,?,?)",
                       (rid,min(lons),max(lons),min(lats),max(lats)))
            counts["road_context_ways"]+=1
            counts["road_geometry_points"]+=len(points)
            counts["road_geometry_encoded_bytes"]+=len(geometry)
            if speed_tagged: counts["ways_speed_tagged"]+=1
            else: counts["context_only_ways"]+=1
            if all_speed or fwd or bwd: counts["ways_numeric_speed"]+=1

    Extract().apply_file(str(args.pbf),locations=True,idx="sparse_mem_array")
    db.execute("INSERT INTO regions VALUES (?,?,?)",
               ("vn",int(datetime.datetime.now(datetime.timezone.utc).timestamp()*1000),
                sum(v for k,v in counts.items() if k.startswith("sign_"))))
    db.commit()
    rtree_rows=db.execute("SELECT COUNT(*) FROM road_context_rtree").fetchone()[0]
    db.execute("PRAGMA optimize")
    db.close()

    stats={
        "source":"OpenStreetMap / Geofabrik Vietnam",
        "license":"ODbL-1.0",
        "attribution":"© OpenStreetMap contributors",
        "input_filename":args.pbf.name,
        "sign_nodes":sum(v for k,v in counts.items() if k.startswith("sign_")),
        "speed_corridor_cells":len(corridor),
        "road_context_ways":counts["road_context_ways"],
        "road_context_only_ways":counts["context_only_ways"],
        "road_speed_ways":counts["ways_speed_tagged"],
        "road_numeric_speed_ways":counts["ways_numeric_speed"],
        "road_geometry_points":counts["road_geometry_points"],
        "road_geometry_encoded_bytes":counts["road_geometry_encoded_bytes"],
        "geometry_encoding":"delta-zigzag-varint-e6",
        "road_rtree_rows":rtree_rows,
        "classes":dict(sorted(counts.items())),
        "sqlite_bytes":args.output.stat().st_size
    }
    args.stats.parent.mkdir(parents=True,exist_ok=True)
    args.stats.write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(stats,ensure_ascii=False))
    assert stats["sign_nodes"]>0
    assert stats["road_context_ways"]>stats["road_speed_ways"]>0
    assert stats["road_context_only_ways"]>0
    assert stats["road_rtree_rows"]==stats["road_context_ways"]
    assert stats["road_geometry_points"]>=stats["road_context_ways"]*2

if __name__=="__main__":
    main()
