package Thanh.TaxiDriver.AALauncher

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compiled in CI. Run on an Android device/emulator in Phase 11B.
 * It does not certify Android Auto host acceptance.
 */
@RunWith(AndroidJUnit4::class)
class AppComponentsSmokeTest {
    @Test fun installedPackageAndVisibleAppName() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Thanh.TaxiDriver.AALauncher", ctx.packageName)
        assertEquals("AA Taxi Driver",
            ctx.getString(Thanh.TaxiDriver.AALauncher.R.string.app_name))
    }

    @Suppress("DEPRECATION")
    @Test fun declaredComponentsAndPrivateDashboard() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = ctx.packageManager.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES
        )
        val services = packageInfo.services.orEmpty().associateBy { it.name }
        assertTrue(services.containsKey("Thanh.TaxiDriver.AALauncher.car.CarService"))
        assertTrue(services.containsKey("Thanh.TaxiDriver.AALauncher.media.LocalAudioService"))
        val activities = packageInfo.activities.orEmpty().associateBy { it.name }
        val phone = activities["Thanh.TaxiDriver.AALauncher.dashboard.PhoneDashboardActivity"]
        assertTrue(phone != null)
        assertFalse(phone!!.exported)
        assertTrue(activities["Thanh.TaxiDriver.AALauncher.ui.MainActivity"]?.exported == true)
    }
}
