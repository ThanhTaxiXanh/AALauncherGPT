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

    def test_context_is_halo_bounded_not_whole_national_network(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("class SpeedAreaCollector",text)
        self.assertIn("CONTEXT_HALO_DEG = 0.001",text)
        self.assertIn("def near_speed_area(box):",text)
        self.assertIn("if not speed_tag and not near_speed_area(box):",text)
        self.assertIn('assert stats["road_context_ways"] < 1_000_000',text)

    def test_supported_road_classes_exclude_non_driving_objects(self):
        text=SCRIPT.read_text(encoding="utf-8")
        road_types=text.split("ROAD_TYPES =",1)[1].split("}",1)[0]
        for excluded in ('"construction"','"proposed"','"track"','"path"'):
            self.assertNotIn(excluded,road_types)

    def test_speed_tag_presence_is_separate_from_numeric_speed(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("def has_speed_tag(tags):",text)
        self.assertIn('counts["context_only_ways"] += 1',text)
        self.assertIn('counts["ways_speed_tagged"] += 1',text)

    def test_workflow_prefilters_all_supported_motor_roads(self):
        workflow=WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("w/highway=motorway,trunk,primary,secondary,tertiary",workflow)

    def test_runtime_reads_context_rtree(self):
        text=BUNDLE.read_text(encoding="utf-8")
        self.assertIn("FROM road_context_rtree AS idx",text)
        self.assertIn("JOIN road_context AS r",text)
        self.assertIn("BundledRoadContext",text)

if __name__ == '__main__':
    unittest.main()
