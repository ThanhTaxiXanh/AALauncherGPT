package Thanh.TaxiDriver.AALauncher

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import android.util.Base64

/** Verify real Android BitmapFactory bounds probe behavior for JPEG/PNG imports. */
@RunWith(AndroidJUnit4::class)
class QrImageImportSmokeTest {
    private val png = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nmVoAAAAASUVORK5CYII=",
        Base64.DEFAULT
    )

    @Test fun boundsProbeReturnsNullButReportsImageDimensions() {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val result = BitmapFactory.decodeStream(ByteArrayInputStream(png), null, opts)
        assertNull(result) // Normal documented behavior: no bitmap allocation.
        assertEquals(1, opts.outWidth)
        assertEquals(1, opts.outHeight)
    }

    @Test fun normalProbeCanDecodeImageAfterBoundsRead() {
        val bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(png))
        assertNotNull(bitmap)
        bitmap?.recycle()
    }
}
