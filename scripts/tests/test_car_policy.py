"""Offline tests for the CI policy gate. No network, secrets or emulator."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from phase11a_car_policy_gate import audit

MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application><service android:name=".car.CarService"><intent-filter>
  <category android:name="androidx.car.app.category.NAVIGATION"/>
  </intent-filter></service></application>
</manifest>'''

RENDERER = '''class LauncherRenderer {
 fun renderNavigationSurface(c: Canvas) { drawBitmap() }
 // ----------
 fun render() { drawCalendar(); drawQr(); drawIcons(); }
}'''

CAR = '''val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
if (debug) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR else strictValidator
renderer.renderNavigationSurface(canvas, width, height, state)
'''


class CarPolicyAuditTests(unittest.TestCase):
    def test_current_design_flags_missing_turn_by_turn(self):
        result = audit(MANIFEST, CAR, RENDERER)
        self.assertTrue(result["navigation_category"])
        self.assertFalse(result["nf1_routing_indicators_present"])
        self.assertTrue(result["nf2_map_only_surface"])

    def test_dashboard_widgets_on_navigation_surface_are_flagged(self):
        renderer = RENDERER.replace("drawBitmap()", "drawQr()")
        result = audit(MANIFEST, CAR, renderer)
        self.assertFalse(result["nf2_map_only_surface"])

    def test_car_using_full_renderer_is_flagged(self):
        car = CAR.replace("renderer.renderNavigationSurface(", "renderer.render(")
        self.assertFalse(audit(MANIFEST, car, RENDERER)["nf2_map_only_surface"])

    def test_release_host_allow_all_not_accepted(self):
        car = CAR.replace("FLAG_DEBUGGABLE", "DEBUG_ALWAYS")
        self.assertFalse(audit(MANIFEST, car, RENDERER)["release_host_validator_restricted"])

    def test_non_navigation_category_has_no_nf1_applicability(self):
        manifest = MANIFEST.replace("androidx.car.app.category.NAVIGATION",
                                    "androidx.car.app.category.POI")
        self.assertFalse(audit(manifest, CAR, RENDERER)["navigation_category"])

    def test_indicators_do_not_overstate_certification(self):
        car = CAR + "NavigationManager navigationStarted( Trip.Builder"
        result = audit(MANIFEST, car, RENDERER)
        self.assertTrue(result["nf1_routing_indicators_present"])
        # Passing indicators is deliberately not proof of a usable route.
        self.assertTrue(result["nf2_map_only_surface"])


if __name__ == "__main__":
    unittest.main()
