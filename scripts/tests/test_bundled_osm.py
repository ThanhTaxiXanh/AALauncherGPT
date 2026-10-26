"""Offline OSM V3.1 bundle regression without fetching the national PBF."""
import ast, pathlib, unittest
SCRIPT=pathlib.Path(__file__).resolve().parents[1]/"build_vietnam_osm_bundle.py"
ROOT=pathlib.Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/".github/workflows/build.yml"
BUNDLE=ROOT/"app/src/main/java/Thanh/TaxiDriver/AALauncher/signs/BundledVietnamOsm.kt"

class BundledOsmStaticTests(unittest.TestCase):
    def test_builder_has_compact_road_context_corridor(self):
        text=SCRIPT.read_text(encoding="utf-8")
        ast.parse(text)
        self.assertIn("class SpeedCorridor",text)
        self.assertIn("speed_way_ids=set()",text)
        self.assertIn("corridor=set()",text)
        self.assertIn("way_intersects_corridor",text)
        self.assertIn("CREATE TABLE road_context(",text)
        self.assertIn("geometry_blob BLOB NOT NULL",text)
        self.assertIn("CREATE VIRTUAL TABLE road_context_rtree USING rtree",text)

    def test_geometry_uses_compact_varint_blob(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("def encode_geometry(points):",text)
        self.assertIn("_put_varint",text)
        self.assertIn("_zigzag",text)
        self.assertNotIn("geometry_json TEXT NOT NULL",text)

    def test_context_is_not_entire_national_network(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("if not speed_tagged and not way_intersects_corridor",text)
        self.assertIn('counts["context_only_ways"]+=1',text)
        self.assertIn('"road_context_only_ways"',text)

    def test_speed_ways_are_always_kept(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn("speed_tagged = way.id in speed_way_ids",text)
        self.assertIn('counts["ways_speed_tagged"]+=1',text)

    def test_supported_motor_types_exclude_non_driving_objects(self):
        text=SCRIPT.read_text(encoding="utf-8")
        road_types=text.split("ROAD_TYPES =",1)[1].split("}",1)[0]
        for bad in ('"construction"','"proposed"','"track"','"path"'):
            self.assertNotIn(bad,road_types)

    def test_workflow_prefilters_motor_road_context(self):
        workflow=WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("w/highway=motorway,trunk,primary,secondary,tertiary",workflow)

    def test_runtime_reads_context_rtree(self):
        text=BUNDLE.read_text(encoding="utf-8")
        self.assertIn("FROM road_context_rtree AS idx",text)
        self.assertIn("JOIN road_context AS r",text)
        self.assertIn("BundledRoadContext",text)
        self.assertIn("r.geometry_blob",text)
        self.assertIn("RoadGeometryCodec.decode",text)

if __name__=="__main__":
    unittest.main()
