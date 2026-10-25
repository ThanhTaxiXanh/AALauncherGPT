import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from phase11a_car_policy_gate import audit, declared_navigation

MAIN = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application><service android:name=".media.LocalAudioService"/></application></manifest>"""
DEBUG = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application><service android:name=".car.CarService"><intent-filter>
<category android:name="androidx.car.app.category.NAVIGATION"/>
</intent-filter></service></application></manifest>"""
CAR = """if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
 HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
renderer.renderNavigationSurface(canvas, width, height, state)
"""
RENDERER = """fun renderNavigationSurface(c: Canvas) { drawBitmap() }
 // ----------
fun render(c: Canvas) { drawCalendar(); drawQr(); drawSigns(); drawIcons(); }"""


class CarPolicyTests(unittest.TestCase):
    def test_navigation_debug_only(self):
        self.assertFalse(declared_navigation(MAIN))
        self.assertTrue(declared_navigation(DEBUG))

    def test_source_map_only(self):
        check = audit(DEBUG, CAR, RENDERER)
        self.assertTrue(check["navigation_category"])
        self.assertTrue(check["nf2_map_only_surface"])
        self.assertFalse(check["nf1_routing_indicators_present"])

    def test_full_dashboard_on_car_detected(self):
        self.assertFalse(audit(DEBUG, CAR.replace(
            "renderNavigationSurface", "render"), RENDERER)["nf2_map_only_surface"])

    def test_qr_in_nav_surface_detected(self):
        altered = RENDERER.replace("drawBitmap()", "drawQr()")
        self.assertFalse(audit(DEBUG, CAR, altered)["nf2_map_only_surface"])

    def test_unrestricted_host_debug_flag_missing(self):
        altered = CAR.replace("FLAG_DEBUGGABLE", "ALWAYS_TRUE")
        self.assertFalse(audit(DEBUG, altered, RENDERER)["release_host_validator_restricted"])

    def test_navigation_cannot_disappear_from_debug_unnoticed(self):
        self.assertFalse(audit(MAIN, CAR, RENDERER)["navigation_category"])


if __name__ == "__main__":
    unittest.main()
