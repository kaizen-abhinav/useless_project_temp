package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

class DriverTargetKinematicsTest {

    @Test
    fun testHeadlightTriangulationAndKinematics() {
        // Vehicle headlights at (220, 260) and (380, 260) on a 640x480 frame
        val x1 = 220.0
        val y1 = 260.0
        val x2 = 380.0
        val y2 = 260.0

        val dx = x2 - x1
        val dy = y2 - y1
        val w = hypot(dx, dy)
        assertEquals(160.0, w, 0.001)

        val mx = (x1 + x2) / 2.0
        val my = (y1 + y2) / 2.0
        assertEquals(300.0, mx, 0.001)
        assertEquals(260.0, my, 0.001)

        // Windshield / Driver Head Height: Ydriver = My - (0.85 * W)
        val yDriver = my - (0.85 * w)
        assertEquals(124.0, yDriver, 0.001)

        // Driver Seat Lateral Offset (RHD traffic configuration):
        // Xdriver = Mx - (0.25 * W)
        val xDriver = mx - (0.25 * w)
        assertEquals(260.0, xDriver, 0.001)

        // Frame center: (320, 240)
        val cx = 640.0 / 2.0
        val cy = 480.0 / 2.0
        val normX = (xDriver - cx) / cx
        val normY = (yDriver - cy) / cy

        assertEquals(-0.1875, normX, 0.0001)
        assertEquals(-0.4833, normY, 0.001)

        // Pan: 90 - (normX * 34)
        val pan = (90.0 - (normX * 34.0)).roundToInt().coerceIn(0, 180)
        // Tilt: 90 + (normY * 25)
        val tilt = (90.0 + (normY * 25.0)).roundToInt().coerceIn(0, 180)

        assertEquals(96, pan)
        assertEquals(78, tilt)
        assertTrue(pan in 0..180)
        assertTrue(tilt in 0..180)
    }

    @Test
    fun testServoAngleClamping() {
        // Far left / extreme normX (-2.0)
        val extremePanLeft = (90.0 - (-2.0 * 34.0)).roundToInt().coerceIn(0, 180)
        assertEquals(158, extremePanLeft)

        // Far right / extreme normX (+3.0)
        val extremePanRight = (90.0 - (3.0 * 34.0)).roundToInt().coerceIn(0, 180)
        assertEquals(0, extremePanRight)

        // High tilt / extreme normY (-5.0)
        val extremeTiltUp = (90.0 + (-5.0 * 25.0)).roundToInt().coerceIn(0, 180)
        assertEquals(0, extremeTiltUp)

        // Low tilt / extreme normY (+5.0)
        val extremeTiltDown = (90.0 + (5.0 * 25.0)).roundToInt().coerceIn(0, 180)
        assertEquals(180, extremeTiltDown)
    }
}
