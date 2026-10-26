package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.map.DrivingCameraController
import Thanh.TaxiDriver.AALauncher.map.DrivingCameraMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingCameraControllerTest {
    @Test fun defaultIsFollow3dAndTiltToggleChangesFollowMode() {
        val camera = DrivingCameraController()
        assertEquals(DrivingCameraMode.FOLLOW_3D, camera.mode)
        assertEquals(60.0, camera.tiltDegrees, 0.001)
        camera.toggleTilt()
        assertEquals(DrivingCameraMode.FOLLOW_2D, camera.mode)
        assertEquals(0.0, camera.tiltDegrees, 0.001)
        camera.toggleTilt()
        assertEquals(DrivingCameraMode.FOLLOW_3D, camera.mode)
    }

    @Test fun panningTimesOutIntoThePreferredFollowMode() {
        val camera = DrivingCameraController()
        camera.leaveFollow(100L)
        assertEquals(DrivingCameraMode.FREE_PAN, camera.mode)
        assertFalse(camera.shouldRecenter(12_100L))
        assertTrue(camera.shouldRecenter(12_101L))
        camera.recenter()
        assertTrue(camera.following)
        assertEquals(DrivingCameraMode.FOLLOW_3D, camera.mode)
    }

    @Test fun tiltChangeInFreePanDoesNotUnexpectedlyFollowVehicle() {
        val camera = DrivingCameraController()
        camera.leaveFollow(1_000L)
        camera.toggleTilt()
        assertEquals(DrivingCameraMode.FREE_PAN, camera.mode)
        assertEquals(0.0, camera.tiltDegrees, 0.001)
        camera.recenter()
        assertEquals(DrivingCameraMode.FOLLOW_2D, camera.mode)
    }

    @Test fun recentGesturesExtendFreePanBeforeRecenter() {
        val camera = DrivingCameraController()
        camera.leaveFollow(100L)
        camera.touched(10_000L)
        assertFalse(camera.shouldRecenter(22_000L))
        assertTrue(camera.shouldRecenter(22_001L))
    }

    @Test fun pinchGuardsScrollAndFlingForTheirRespectiveWindows() {
        val camera = DrivingCameraController()
        camera.scaled(100L)
        assertFalse(camera.canScroll(449L))
        assertTrue(camera.canScroll(450L))
        assertFalse(camera.canFling(699L))
        assertTrue(camera.canFling(700L))
    }

    @Test fun temporaryZoomExpiresAndRecenteringClearsIt() {
        val camera = DrivingCameraController()
        camera.setTemporaryZoom(19.0, 100L)
        assertEquals(19.0, camera.temporaryZoom(15_100L)!!, 0.001)
        assertNull(camera.temporaryZoom(15_101L))
        camera.setTemporaryZoom(20.5, 16_000L)
        assertEquals(20.0, camera.temporaryZoom(16_001L)!!, 0.001)
        camera.recenter()
        assertNull(camera.temporaryZoom(16_002L))
    }

    @Test fun rotateGestureRemainsFreePanUntilExplicitRecentering() {
        val camera = DrivingCameraController()
        camera.toggleSwipeRotate(100L)
        assertTrue(camera.swipeRotateMode)
        assertFalse(camera.following)
        assertFalse(camera.canFling(1_000L))
        camera.toggleSwipeRotate(200L)
        assertFalse(camera.swipeRotateMode)
        assertEquals(DrivingCameraMode.FREE_PAN, camera.mode)
        camera.recenter()
        assertTrue(camera.following)
    }

    @Test fun surfaceRestartResumesPreferredFollowMode() {
        val camera = DrivingCameraController()
        camera.toggleTilt()
        camera.toggleSwipeRotate(100L)
        camera.setTemporaryZoom(19.0, 200L)
        camera.onSurfaceStarted()
        assertEquals(DrivingCameraMode.FOLLOW_2D, camera.mode)
        assertFalse(camera.swipeRotateMode)
        assertNull(camera.temporaryZoom(300L))
    }
}
