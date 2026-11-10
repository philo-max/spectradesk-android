package com.spectradesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the measurement state machine.
 *
 * The camera itself needs a device, but everything that decides *what a number
 * means* - calibration gating, the GC switch invalidating references, verdict
 * thresholds - is pure Kotlin and is tested here.  These are the paths where a
 * bug produces a plausible but wrong reading rather than a crash.
 */
class MeasurementStateTest {

    private fun capture(mean: DoubleArray, gamma: Boolean = true) = Capture(
        linearRgb = DoubleArray(0),
        width = 10,
        height = 10,
        bitmap = null,
        timestampMs = 0L,
        gammaCorrected = gamma,
        meanLinear = mean,
    )

    // ---------------------------------------------------------------- gating

    @Test
    fun `uncalibrated measurement is refused`() {
        val vm = CameraViewModel()
        val result = vm.measure(capture(doubleArrayOf(0.5, 0.5, 0.5)))
        assertNull("an uncalibrated camera must not produce a reading", result)
        assertTrue(vm.state.value.message!!.contains("白参考"))
    }

    @Test
    fun `calibration becomes ready after a white reference`() {
        val vm = CameraViewModel()
        assertFalse(vm.state.value.calibration.isReady)
        vm.takeWhiteReference(capture(doubleArrayOf(0.8, 0.7, 0.6)))
        assertTrue(vm.state.value.calibration.isReady)
    }

    @Test
    fun `a black white reference is not usable`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.0, 0.0, 0.0)))
        assertFalse(vm.state.value.calibration.isReady)
    }

    @Test
    fun `measurement is accepted once calibrated`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.8, 0.8, 0.8)))
        val m = vm.measure(capture(doubleArrayOf(0.8, 0.8, 0.8)))
        assertNotNull(m)
        assertEquals(100.0, m!!.lab[0], 0.5)
    }

    // -------------------------------------------------------------- GC switch

    @Test
    fun `switching gc invalidates the calibration`() {
        // A reference taken in the sRGB domain must not be used to divide a
        // linear reading: that is a ~2.3x error that looks like a real colour.
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.8, 0.7, 0.6)))
        assertTrue(vm.state.value.calibration.isReady)

        vm.setGammaCorrected(false)

        assertFalse("GC change must clear the reference", vm.state.value.calibration.isReady)
        assertFalse(vm.state.value.settings.gammaCorrected)
        assertTrue(vm.state.value.message!!.contains("标定已清除"))
    }

    @Test
    fun `setting the same gc value keeps the calibration`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.8, 0.7, 0.6)))
        vm.setGammaCorrected(true)  // already true
        assertTrue(vm.state.value.calibration.isReady)
    }

    @Test
    fun `gc switch is reflected in the label`() {
        val vm = CameraViewModel()
        assertTrue(vm.state.value.settings.gammaLabel().contains("GC 开"))
        vm.setGammaCorrected(false)
        assertTrue(vm.state.value.settings.gammaLabel().contains("GC 关"))
    }

    // ---------------------------------------------------------------- verdicts

    @Test
    fun `no standard means no verdict`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.8, 0.8, 0.8)))
        val m = vm.measure(capture(doubleArrayOf(0.8, 0.8, 0.8)))!!
        assertNull(m.deltaE)
        assertNull(m.passed)
        assertEquals("未判定", m.verdictText())
    }

    @Test
    fun `matching the standard passes`() {
        val vm = CameraViewModel()
        vm.setStandard("灰 50")
        val white = 0.85
        vm.takeWhiteReference(capture(doubleArrayOf(white, white, white)))

        // `meanLinear` is a camera reading, and `measure()` divides it by the
        // white reference.  So to present the app with a sample of reflectance
        // `y`, the synthetic reading must be `y * white` - feeding `y` directly
        // (as an earlier version did) asks the app to measure a sample 1/0.85
        // times brighter than intended and produced a spurious dE 3.6.
        //
        // `y` itself comes from the standard's own Lab: L* is a cube root of
        // luminance, so the linear reflectance for a given L* is not L*/100.
        val standard = Standards.byName("灰 50")!!
        val y = Colorimetry.labToXyz(standard.lab)[1] / 100.0
        val reading = y * white
        val m = vm.measure(capture(doubleArrayOf(reading, reading, reading)))!!

        assertNotNull(m.deltaE)
        assertTrue("dE was ${m.deltaE}", m.deltaE!! < 1.0)
        assertEquals(true, m.passed)
    }

    @Test
    fun `a clearly different sample fails`() {
        val vm = CameraViewModel()
        vm.setStandard("灰 90")
        vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        val m = vm.measure(capture(doubleArrayOf(0.2, 0.2, 0.2)))!!
        assertNotNull(m.passed)
        assertFalse("a dark sample must fail a 90% grey standard", m.passed!!)
    }

    @Test
    fun `tolerance is honoured`() {
        val vm = CameraViewModel()
        vm.setStandard("灰 50")
        vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        val m = vm.measure(capture(doubleArrayOf(0.5, 0.5, 0.5)))!!
        val standard = Standards.byName("灰 50")!!
        assertEquals(m.deltaE!! <= standard.tolerance, m.passed)
    }

    // ----------------------------------------------------------------- history

    @Test
    fun `history accumulates in order`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        vm.measure(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        vm.measure(capture(doubleArrayOf(0.5, 0.5, 0.5)))
        assertEquals(2, vm.state.value.measurements.size)
        assertEquals("M1", vm.state.value.measurements[0].label)
        assertEquals("M2", vm.state.value.measurements[1].label)
    }

    @Test
    fun `clearing history resets the index`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        vm.measure(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        vm.clearHistory()
        assertEquals(0, vm.state.value.measurements.size)
        val m = vm.measure(capture(doubleArrayOf(0.85, 0.85, 0.85)))!!
        assertEquals("M1", m.label)
    }

    // ---------------------------------------------------------------- standards

    @Test
    fun `every built in standard is a valid colour`() {
        assertTrue(Standards.all.isNotEmpty())
        for (std in Standards.all) {
            assertTrue("${std.name} L* out of range", std.lab[0] in 0.0..100.0)
            assertTrue("${std.name} tolerance must be positive", std.tolerance > 0)
        }
    }

    @Test
    fun `grey standards have neutral chroma`() {
        for (name in listOf("灰 50", "灰 90")) {
            val std = Standards.byName(name)!!
            assertEquals("$name a*", 0.0, std.lab[1], 0.5)
            assertEquals("$name b*", 0.0, std.lab[2], 0.5)
        }
    }

    @Test
    fun `unknown standard name is null not a crash`() {
        assertNull(Standards.byName("不存在"))
    }
}
