"""Offline OSM V3 bundle regression without fetching the national PBF."""
import ast
import pathlib
import unittest

SCRIPT=pathlib.Path(__file__).resolve().parents[1]/"build_vietnam_osm_bundle.py"
ROOT=pathlib.Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/".github/workflows/build.yml"
BUNDLE=ROOT/"app/src/main/java/Thanh/TaxiDriver/AALauncher/signs/BundledVietnamOsm.kt"

class BundledOsmStaticTests(unittest.TestCase):
    def test_builder_has_full_road_context_geometry_rtree(self):
        text=SCRIPT.read_text(encoding="utf-8")
        ast.parse(text)
        self.assertIn("CREATE TABLE road_context(",text)
        self.assertIn("CREATE VIRTUAL TABLE road_context_rtree USING rtree",text)
        self.assertIn("has_speed_tag INTEGER NOT NULL",text)
        self.assertIn('{"type":"LineString","coordinates":points}',text)
        self.assertIn('locations=True',text)

    def test_context_keeps_supported_motor_roads_without_requiring_speed(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn('if hw not in ROAD_TYPES:',text)
        self.assertNotIn('if not (all_speed or fwd or bwd or cond or fcond or bcond):',text)
        road_types=text.split("ROAD_TYPES =",1)[1].split("}",1)[0]
        self.assertNotIn('"construction"',road_types)
        self.assertNotIn('"proposed"',road_types)
        self.assertNotIn('"track"',road_types)
        self.assertNotIn('"path"',road_types)

    def test_speed_tag_presence_is_separate_from_numeric_speed(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("has_speed_tag = 1 if any",text)
        self.assertIn('counts["road_context_ways"] += 1',text)
        self.assertIn('counts["ways_speed_tagged"] += 1',text)

    def test_directional_and_conditional_speed_tags_are_preserved(self):
        text=SCRIPT.read_text(encoding="utf-8")
        for field in (
            "maxspeed:forward", "maxspeed:backward", "maxspeed:conditional",
            "maxspeed:forward:conditional", "maxspeed:backward:conditional"
        ):
            self.assertIn(field,text)

    def test_workflow_prefilters_all_supported_motor_roads(self):
        workflow=WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("w/highway=motorway,trunk,primary,secondary,tertiary",workflow)

    def test_compressed_asset_name_is_aapt_safe_and_consistent(self):
        asset="vietnam_signs.sqlite.gzbin"
        workflow=WORKFLOW.read_text(encoding="utf-8")
        bundle=BUNDLE.read_text(encoding="utf-8")
        self.assertIn(asset,workflow)
        self.assertIn(f'ctx.assets.open("osm/{asset}")',bundle)

    def test_runtime_reads_context_rtree_and_full_geometry(self):
        text=BUNDLE.read_text(encoding="utf-8")
        self.assertIn("FROM road_context_rtree AS idx",text)
        self.assertIn("JOIN road_context AS r",text)
        self.assertIn("r.geometry_json",text)
        self.assertIn("BundledRoadContext",text)

if __name__ == '__main__':
    unittest.main()
