"""Offline classifier regression without fetching the PBF."""
import ast, pathlib, unittest
SCRIPT=pathlib.Path(__file__).resolve().parents[1]/"build_vietnam_osm_bundle.py"
class BundledOsmStaticTests(unittest.TestCase):
    def test_builder_is_parseable_and_has_both_node_and_way_handlers(self):
        text=SCRIPT.read_text(encoding="utf-8")
        ast.parse(text)
        self.assertIn("def node(self,node):",text)
        self.assertIn("def way(self,way):",text)
        self.assertIn('CREATE TABLE road_speeds(',text)
        self.assertIn('CREATE TABLE markers(',text)
        self.assertIn('locations=True',text)
    def test_never_invents_road_speed_from_highway_only(self):
        text=SCRIPT.read_text(encoding="utf-8")
        self.assertIn('if not (all_speed or fwd or bwd or cond): return',text)
if __name__ == '__main__': unittest.main()
