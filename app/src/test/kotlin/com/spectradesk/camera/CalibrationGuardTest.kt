package com.spectradesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the calibration gates and the stale-frame guards.
 *
 * These cover the failure modes a code review flagged: a white reference that is
 * too dark, clipped, or tinted; a capture that arrives after the GC switch moved;
 * and a colour temperature invented for a black sample.  Each of those used to
 * produce a confident, wrong number rather than an error.
 */
class CalibrationGuardTest {

    private fun capture(mean: DoubleArray, gamma: Boolean = true) = Capture(
        linearRgb = DoubleArray(0),
        width = 10,
        height = 10,
        bitmap = null,
        timestampMs = 0L,
        gammaCorrected = gamma,
        meanLinear = mean,
    )

    // ------------------------------------------------------- white reference

    @Test
    fun `a good white reference is accepted`() {
        val vm = CameraViewModel()
        val ok = vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        assertTrue(ok)
        assertTrue(vm.state.value.calibration.isReady)
    }

    @Test
    fun `a nearly black white reference is rejected`() {
        // sRGB 1/255 decodes to ~0.0003; the old gate (>1e-4) let this through
        // and the whole measurement was then divided by noise.
        val vm = CameraViewModel()
        val ok = vm.takeWhiteReference(capture(doubleArrayOf(0.001, 0.001, 0.001)))
        assertFalse(ok)
        assertFalse(vm.state.value.calibration.isReady)
        assertTrue(vm.state.value.message!!.contains("太暗"))
    }

    @Test
    fun `a clipped white reference is rejected`() {
        val vm = CameraViewModel()
        val ok = vm.takeWhiteReference(capture(doubleArrayOf(1.0, 1.0, 1.0)))
        assertFalse(ok)
        assertTrue(vm.state.value.message!!.contains("过曝"))
    }

    @Test
    fun `a tinted white reference is rejected`() {
        // A strongly coloured card is not a white reference.
        val vm = CameraViewModel()
        val ok = vm.takeWhiteReference(capture(doubleArrayOf(0.8, 0.4, 0.2)))
        assertFalse(ok)
        assertTrue(vm.state.value.message!!.contains("偏色"))
    }

    @Test
    fun `a mildly warm reference is still accepted`() {
        // Real indoor light is not perfectly neutral; a 10% spread must pass.
        val vm = CameraViewModel()
        val ok = vm.takeWhiteReference(capture(doubleArrayOf(0.80, 0.76, 0.72)))
        assertTrue(ok)
    }

    @Test
    fun `rejection leaves a previous calibration untouched`() {
        val vm = CameraViewModel()
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85))))
        val before = vm.state.value.calibration.white!!.copyOf()

        // A bad retry must not destroy a good reference that is still in use.
        assertFalse(vm.takeWhiteReference(capture(doubleArrayOf(1.0, 1.0, 1.0))))
        assertTrue(vm.state.value.calibration.isReady)
        assertTrue(before.contentEquals(vm.state.value.calibration.white!!))
    }

    // -------------------------------------------------------- stale captures

    @Test
    fun `a capture from the old gamma mode is rejected`() {
        val vm = CameraViewModel()
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85), gamma = true)))

        // Flip GC: the calibration is cleared, so re-establish it in the new mode.
        vm.setGammaCorrected(false)
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.5, 0.5, 0.5), gamma = false)))

        // A frame still tagged with the old mode must not be measured against the
        // new reference - that is a silent ~2.3x error.
        val stale = vm.measure(capture(doubleArrayOf(0.4, 0.4, 0.4), gamma = true))
        assertNull(stale)
        assertTrue(vm.state.value.message!!.contains("伽马模式已改变"))
    }

    @Test
    fun `a capture in the current gamma mode is accepted`() {
        val vm = CameraViewModel()
        vm.setGammaCorrected(false)
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.5, 0.5, 0.5), gamma = false)))
        val m = vm.measure(capture(doubleArrayOf(0.25, 0.25, 0.25), gamma = false))
        assertNotNull(m)
    }

    // --------------------------------------------------------- colour temp

    @Test
    fun `a black sample reports no colour temperature`() {
        val vm = CameraViewModel()
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85))))
        val m = vm.measure(capture(doubleArrayOf(0.0, 0.0, 0.0)))!!
        // McCamy would happily return ~2021 K for (x,y)=(0,0); a black sample has
        // no chromaticity at all, so NaN is the only honest answer.
        assertTrue("cct was ${m.cct}", m.cct.isNaN())
    }

    @Test
    fun `a normal sample reports a colour temperature`() {
        val vm = CameraViewModel()
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85))))
        val m = vm.measure(capture(doubleArrayOf(0.6, 0.6, 0.6)))!!
        assertTrue("cct was ${m.cct}", m.cct.isFinite())
    }

    // ------------------------------------------------------ per-record state

    @Test
    fun `exposure lock is recorded per measurement`() {
        val vm = CameraViewModel()
        vm.setExposureLocked(true)
        assertTrue(vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)), locked = true))
        val locked = vm.measure(capture(doubleArrayOf(0.5, 0.5, 0.5)))!!
        assertTrue(locked.exposureLocked)

        // Unlock, then measure again: the earlier record must keep its own flag,
        // otherwise an export would relabel history.
        vm.setExposureLocked(false)
        val unlocked = vm.measure(capture(doubleArrayOf(0.5, 0.5, 0.5)))!!
        assertFalse(unlocked.exposureLocked)
        assertTrue(vm.state.value.measurements[0].exposureLocked)
    }

    @Test
    fun `shot counter advances on every capture`() {
        val vm = CameraViewModel()
        vm.takeWhiteReference(capture(doubleArrayOf(0.85, 0.85, 0.85)))
        val afterWhite = vm.state.value.shotCounter
        vm.measure(capture(doubleArrayOf(0.5, 0.5, 0.5)))
        // The filtered preview caches on this value, so it must change per capture
        // or the overlay would keep showing the previous sample.
        assertTrue(vm.state.value.shotCounter > afterWhite)
    }
}
