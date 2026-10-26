package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.signs.RoadSigns
import Thanh.TaxiDriver.AALauncher.signs.SignItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Grounded OSM tags; no fabricated coordinates or commercial datasets. */
class ExpandedRoadSignTagsTest {
    private fun classify(tags: String): SignItem? {
        val instance = RoadSigns(File.createTempFile("osm-tag-test", ".json").apply { delete() })
        val method = RoadSigns::class.java.getDeclaredMethod(
            "classify", JSONObject::class.java,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        return try { method.invoke(instance, JSONObject(tags), 21.0, 105.0) as SignItem? }
        finally { instance.close() }
    }

    @Test fun confirmedOsmSpeedCameraIsDistinctFromSurveillance() {
        assertEquals("camera", classify("""{"highway":"speed_camera"}""")?.type)
        assertEquals("redlight_camera", classify("""{"enforcement":"traffic_signals"}""")?.type)
        assertNull(classify("""{"man_made":"surveillance"}"""))
    }

    @Test fun vietnameseTrafficSignsGetDistinctMarkers() {
        assertEquals("no_entry", classify("""{"traffic_sign":"VN:P.102"}""")?.type)
        assertEquals("no_parking", classify("""{"traffic_sign":"VN:P.131a"}""")?.type)
        assertEquals("school", classify("""{"traffic_sign":"VN:W.225"}""")?.type)
        assertEquals("curve", classify("""{"traffic_sign":"VN:W.201"}""")?.type)
    }

    @Test fun noMadeUpMaxSpeed() {
        val height = classify("""{"traffic_sign":"maxheight"}""")!!
        assertEquals("height", height.type)
        assertEquals(0, height.value)
        assertEquals("speed", classify("""{"maxspeed":"80"}""")?.type)
    }
}
