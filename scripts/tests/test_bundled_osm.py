"""Offline classifier regression without fetching the PBF."""
import ast, pathlib, unittest
SCRIPT=pathlib.Path(__file__).resolve().parents[1]/"build_vietnam_osm_bundle.py"
ROOT=pathlib.Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/".github/workflows/build.yml"
BUNDLE=ROOT/"app/src/main/java/Thanh/TaxiDriver/AALauncher/signs/BundledVietnamOsm.kt"
class BundledOsmStaticTests(unittest.TestCase):
    def test_builder_is_parseable_and_has_both_node_and_way_handlers(self):
        text=SCRIPT.read_text(encoding="utf-8")
        ast.parse(text)
        self.assertIn("def node(self,node):",text)
        self.assertIn("def way(self,way):",text)
        self.assertIn('CREATE TABLE road_speeds(',text)
        self.assertIn('CREATE TABLE markers(',text)
        self.assertIn('locations=True',text)
    def test_compressed_asset_name_is_aapt_safe_and_consistent(self):
        asset="vietnam_signs.sqlite.gzbin"
        workflow=WORKFLOW.read_text(encoding="utf-8")
        bundle=BUNDLE.read_text(encoding="utf-8")
        self.assertIn(asset,workflow)
        self.assertIn(f'ctx.assets.open("osm/{asset}")',bundle)
        self.assertNotIn('vietnam_signs.sqlite.gz"',bundle)
    def test_never_invents_road_speed_from_highway_only(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn('if not (all_speed or fwd or bwd or cond): return',text)
if __name__ == '__main__': unittest.main()
