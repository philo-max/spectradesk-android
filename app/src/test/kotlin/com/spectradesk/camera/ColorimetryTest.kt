package com.spectradesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Colour-science tests for the Android port.
 *
 * These run on the JVM with no device, which is the only way to keep the phone's
 * arithmetic under test in CI.  The reference values are the same published CIE
 * data the desktop suite uses, so a divergence between platforms fails here.
 */
class ColorimetryTest {

    @Test
    fun `white diffuser has L 100 and neutral chroma`() {
        val lab = Colorimetry.xyzToLab(Colorimetry.WHITE_XYZ)
        assertEquals(100.0, lab[0], 1e-6)
        assertEquals(0.0, lab[1], 1e-6)
        assertEquals(0.0, lab[2], 1e-6)
    }

    @Test
    fun `linear rgb white maps to L 100`() {
        // The phone's measurement path: white-balanced (1,1,1) must be L* 100.
        // Before this was routed through a fabricated reflectance curve it gave
        // L* 115, an impossible value that shipped as a visible defect.
        val lab = Colorimetry.linearRgbToLab(doubleArrayOf(1.0, 1.0, 1.0))
        assertEquals(100.0, lab[0], 0.01)
        assertEquals(0.0, lab[1], 0.05)
        assertEquals(0.0, lab[2], 0.05)
    }

    @Test
    fun `linear rgb mid grey maps to the textbook L 76`() {
        // Linear 0.5 is L* 76.07 for any neutral observer.  This pins the matrix
        // normalisation: a wrong scale factor shows up here immediately.
        val lab = Colorimetry.linearRgbToLab(doubleArrayOf(0.5, 0.5, 0.5))
        assertEquals(76.07, lab[0], 0.1)
    }

    @Test
    fun `linear rgb black is L 0`() {
        val lab = Colorimetry.linearRgbToLab(doubleArrayOf(0.0, 0.0, 0.0))
        assertEquals(0.0, lab[0], 1e-9)
    }

    @Test
    fun `red primary is positive a and negative b`() {
        val lab = Colorimetry.linearRgbToLab(doubleArrayOf(1.0, 0.0, 0.0))
        assertTrue("a* should be positive for red, was ${lab[1]}", lab[1] > 60)
        assertTrue("b* should be positive for red, was ${lab[2]}", lab[2] > 40)
    }

    @Test
    fun `blue primary has negative b`() {
        val lab = Colorimetry.linearRgbToLab(doubleArrayOf(0.0, 0.0, 1.0))
        assertTrue("b* should be strongly negative for blue, was ${lab[2]}", lab[2] < -90)
    }

    @Test
    fun `lab xyz round trip`() {
        val lab = doubleArrayOf(63.0, -35.0, -15.0)
        val back = Colorimetry.xyzToLab(Colorimetry.labToXyz(lab))
        assertEquals(lab[0], back[0], 1e-6)
        assertEquals(lab[1], back[1], 1e-6)
        assertEquals(lab[2], back[2], 1e-6)
    }

    @Test
    fun `lch round trip`() {
        val lab = doubleArrayOf(63.0, -35.0, -15.0)
        val lch = Colorimetry.labToLch(lab)
        val c = lch[1]
        val h = Math.toRadians(lch[2])
        val back = doubleArrayOf(lch[0], c * Math.cos(h), c * Math.sin(h))
        assertEquals(lab[0], back[0], 1e-9)
        assertEquals(lab[1], back[1], 1e-6)
        assertEquals(lab[2], back[2], 1e-6)
    }

    @Test
    fun `delta e is zero for identical colours`() {
        val lab = doubleArrayOf(55.0, 10.0, -20.0)
        assertEquals(0.0, Colorimetry.deltaE00(lab, lab), 1e-9)
    }

    @Test
    fun `delta e is symmetric`() {
        val a = doubleArrayOf(40.0, 12.0, -8.0)
        val b = doubleArrayOf(44.0, 9.0, -2.0)
        assertEquals(Colorimetry.deltaE00(a, b), Colorimetry.deltaE00(b, a), 1e-9)
    }

    @Test
    fun `ciede2000 matches published test pairs`() {
        // Sharma, Wu & Dalal (2005) reference data.  These are the same pairs the
        // desktop suite checks, so the two platforms cannot drift silently.
        val cases = listOf(
            Triple(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485), 2.0425),
            Triple(doubleArrayOf(50.0, 3.1571, -77.2803), doubleArrayOf(50.0, 0.0, -82.7485), 2.8615),
            Triple(doubleArrayOf(50.0, 2.8361, -74.0200), doubleArrayOf(50.0, 0.0, -82.7485), 3.4412),
            Triple(doubleArrayOf(50.0, -1.3802, -84.2814), doubleArrayOf(50.0, 0.0, -82.7485), 1.0000),
            Triple(doubleArrayOf(50.0, 0.0, 0.0), doubleArrayOf(50.0, -1.0, 2.0), 2.3669),
        )
        for ((a, b, expected) in cases) {
            assertEquals(expected, Colorimetry.deltaE00(a, b), 1e-4)
        }
    }

    @Test
    fun `srgb decode matches the standard curve`() {
        assertEquals(0.2159, Colorimetry.srgbToLinear(0.502), 0.002)
        assertEquals(0.0, Colorimetry.srgbToLinear(0.0), 1e-9)
        assertEquals(1.0, Colorimetry.srgbToLinear(1.0), 1e-6)
        // Linear toe below 0.04045
        assertEquals(0.02 / 12.92, Colorimetry.srgbToLinear(0.02), 1e-9)
    }

    @Test
    fun `srgb decode is monotonic`() {
        var previous = -1.0
        for (i in 0..100) {
            val v = Colorimetry.srgbToLinear(i / 100.0)
            assertTrue("not monotonic at $i", v > previous)
            previous = v
        }
    }

    @Test
    fun `cct of white is near d65`() {
        val xy = Colorimetry.xyzToXy(Colorimetry.WHITE_XYZ)
        val cct = Colorimetry.cctFromXy(xy)
        assertTrue("CCT was $cct", abs(cct - 6504.0) < 60.0)
    }

    @Test
    fun `spectrum to xyz of a perfect diffuser is the white point`() {
        val flat = DoubleArray(ColorTables.COUNT) { 1.0 }
        val xyz = Colorimetry.spectrumToXyz(flat)
        assertEquals(100.0, xyz[1], 0.5)
    }

    @Test
    fun `negative reflectance is clipped not propagated`() {
        val spiky = DoubleArray(ColorTables.COUNT) { -0.5 }
        val xyz = Colorimetry.spectrumToXyz(spiky)
        assertTrue(xyz.all { it >= 0.0 })
    }
}
