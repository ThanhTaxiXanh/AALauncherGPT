"""Offline OSM bundle regression without fetching the national PBF."""
import ast
import pathlib
import unittest

SCRIPT=pathlib.Path(__file__).resolve().parents[1]/"build_vietnam_osm_bundle.py"
ROOT=pathlib.Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/".github/workflows/build.yml"
BUNDLE=ROOT/"app/src/main/java/Thanh/TaxiDriver/AALauncher/signs/BundledVietnamOsm.kt"

class BundledOsmStaticTests(unittest.TestCase):
    def test_builder_is_parseable_and_has_full_way_geometry_rtree(self):
        text=SCRIPT.read_text(encoding="utf-8")
        ast.parse(text)
        self.assertIn("def node(self,node):",text)
        self.assertIn("def way(self,way):",text)
        self.assertIn("geometry_json TEXT NOT NULL",text)
        self.assertIn("CREATE VIRTUAL TABLE road_speeds_rtree USING rtree",text)
        self.assertIn('{"type":"LineString","coordinates":points}',text)
        self.assertIn('locations=True',text)

    def test_speed_ways_are_filtered_to_supported_motor_roads(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn('if hw not in ROAD_TYPES:',text)
        road_types=text.split("ROAD_TYPES =",1)[1].split("}",1)[0]
        self.assertNotIn('"construction"',road_types)
        self.assertNotIn('"proposed"',road_types)
        self.assertIn('if not (all_speed or fwd or bwd or cond or fcond or bcond):',text)

    def test_directional_and_conditional_speed_tags_are_preserved(self):
        text=SCRIPT.read_text(encoding="utf-8")
        for field in (
            "maxspeed:forward", "maxspeed:backward", "maxspeed:conditional",
            "maxspeed:forward:conditional", "maxspeed:backward:conditional"
        ):
            self.assertIn(field,text)

    def test_compressed_asset_name_is_aapt_safe_and_consistent(self):
        asset="vietnam_signs.sqlite.gzbin"
        workflow=WORKFLOW.read_text(encoding="utf-8")
        bundle=BUNDLE.read_text(encoding="utf-8")
        self.assertIn(asset,workflow)
        self.assertIn(f'ctx.assets.open("osm/{asset}")',bundle)
        self.assertNotIn('vietnam_signs.sqlite.gz"',bundle)

    def test_runtime_reads_rtree_and_full_geometry(self):
        text=BUNDLE.read_text(encoding="utf-8")
        self.assertIn("FROM road_speeds_rtree AS idx",text)
        self.assertIn("r.geometry_json",text)
        self.assertIn("SpeedWayGeometry",text)

if __name__ == '__main__':
    unittest.main()
