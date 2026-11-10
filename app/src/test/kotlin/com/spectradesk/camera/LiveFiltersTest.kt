package com.spectradesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the live filters and the LUT sampler.
 *
 * The GPU does the real work on screen, but these cover the two things that can
 * silently produce a wrong-looking picture: the linear-light convention (a filter
 * applied in the sRGB domain shifts neutrals) and the trilinear interpolation
 * (the algorithm every shipping camera app's LUT shader implements).
 */
class LiveFiltersTest {

    // ------------------------------------------------------------ invariants

    @Test
    fun `only the identity filter is measurement safe`() {
        // If this ever fails, the UI would claim a colour-altering filter is safe
        // to measure through.
        val safe = LiveFilter.entries.filter { it.measurementSafe }
        assertEquals(listOf(LiveFilter.NONE), safe)
    }

    @Test
    fun `every filter has a label and description`() {
        for (f in LiveFilter.entries) {
            assertTrue("${f.name} has no label", f.label.isNotBlank())
            assertTrue("${f.name} has no description", f.description.isNotBlank())
        }
    }

    @Test
    fun `unknown name falls back to none`() {
        assertEquals(LiveFilter.NONE, LiveFilter.fromName("nope"))
        assertEquals(LiveFilter.NONE, LiveFilter.fromName(null))
        assertEquals(LiveFilter.WARM, LiveFilter.fromName("WARM"))
    }

    @Test
    fun `enum ordinals match the shader branch indices`() {
        // The fragment shader switches on the ordinal, so reordering this enum
        // without editing the shader would silently remap every filter.
        assertEquals(0, LiveFilter.NONE.ordinal)
        assertEquals(1, LiveFilter.MONO.ordinal)
        assertEquals(2, LiveFilter.VIVID.ordinal)
        assertEquals(3, LiveFilter.COOL.ordinal)
        assertEquals(4, LiveFilter.WARM.ordinal)
        assertEquals(5, LiveFilter.CONTRAST.ordinal)
        assertEquals(6, LiveFilter.INVERT.ordinal)
        assertEquals(7, LiveFilter.CLIP.ordinal)
    }

    // ------------------------------------------------------------- behaviour

    @Test
    fun `none is identity`() {
        val rgb = doubleArrayOf(0.2, 0.5, 0.8)
        val out = LiveFilters.apply(LiveFilter.NONE, rgb)
        assertTrue(rgb.contentEquals(out))
    }

    @Test
    fun `mono produces equal channels`() {
        val out = LiveFilters.apply(LiveFilter.MONO, doubleArrayOf(0.8, 0.3, 0.1))
        assertEquals(out[0], out[1], 1e-12)
        assertEquals(out[1], out[2], 1e-12)
    }

    @Test
    fun `mono of a neutral colour is unchanged`() {
        val out = LiveFilters.apply(LiveFilter.MONO, doubleArrayOf(0.4, 0.4, 0.4))
        assertEquals(0.4, out[0], 1e-9)
    }

    @Test
    fun `luma uses rec 709 weights`() {
        assertEquals(0.2126, LiveFilters.luma(1.0, 0.0, 0.0), 1e-9)
        assertEquals(0.7152, LiveFilters.luma(0.0, 1.0, 0.0), 1e-9)
        assertEquals(0.0722, LiveFilters.luma(0.0, 0.0, 1.0), 1e-9)
    }

    @Test
    fun `s curve fixes the endpoints and the midpoint`() {
        assertEquals(0.0, LiveFilters.sCurve(0.0), 1e-12)
        assertEquals(0.5, LiveFilters.sCurve(0.5), 1e-12)
        assertEquals(1.0, LiveFilters.sCurve(1.0), 1e-12)
    }

    @Test
    fun `s curve is monotonic`() {
        var prev = -1.0
        for (i in 0..100) {
            val v = LiveFilters.sCurve(i / 100.0)
            assertTrue("not monotonic at $i", v >= prev)
            prev = v
        }
    }

    @Test
    fun `warm shifts toward red and cool toward blue`() {
        val base = doubleArrayOf(0.5, 0.5, 0.5)
        val warm = LiveFilters.apply(LiveFilter.WARM, base)
        val cool = LiveFilters.apply(LiveFilter.COOL, base)
        assertTrue("warm should raise red", warm[0] > warm[2])
        assertTrue("cool should raise blue", cool[2] > cool[0])
    }

    @Test
    fun `invert keeps hue by inverting only lightness`() {
        // A saturated colour must stay saturated-same-hue; a per-channel 1-x would
        // flip the hue and make coloured defects disappear.
        val rgb = doubleArrayOf(0.6, 0.25, 0.15)
        val out = LiveFilters.apply(LiveFilter.INVERT, rgb)
        // Ratios between channels are preserved.
        assertEquals(rgb[0] / rgb[2], out[0] / out[2], 1e-6)
        // The luma is approximately inverted.
        val yIn = LiveFilters.luma(rgb[0], rgb[1], rgb[2])
        val yOut = LiveFilters.luma(out[0], out[1], out[2])
        assertEquals(1.0 - yIn, yOut, 0.05)
    }

    @Test
    fun `contrast pulls values away from the midpoint`() {
        val dark = LiveFilters.apply(LiveFilter.CONTRAST, doubleArrayOf(0.2, 0.2, 0.2))
        val bright = LiveFilters.apply(LiveFilter.CONTRAST, doubleArrayOf(0.8, 0.8, 0.8))
        assertTrue("dark should get darker", dark[0] < 0.2)
        assertTrue("bright should get brighter", bright[0] > 0.8)
    }

    @Test
    fun `contrast leaves mid grey alone`() {
        val out = LiveFilters.apply(LiveFilter.CONTRAST, doubleArrayOf(0.5, 0.5, 0.5))
        assertEquals(0.5, out[0], 1e-9)
    }

    // ------------------------------------------------------------ LUT sampler

    private fun identityLut(size: Int): DoubleArray {
        val lut = DoubleArray(size * size * size * 3)
        var i = 0
        val max = (size - 1).toDouble()
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    lut[i++] = ri / max
                    lut[i++] = gi / max
                    lut[i++] = bi / max
                }
            }
        }
        return lut
    }

    @Test
    fun `identity lut reproduces its input`() {
        val size = 5
        val lut = identityLut(size)
        for (v in listOf(0.0, 0.25, 0.5, 0.9, 1.0)) {
            val out = LiveFilters.sampleLut(lut, size, doubleArrayOf(v, v, v))
            assertEquals(v, out[0], 1e-9)
            assertEquals(v, out[1], 1e-9)
            assertEquals(v, out[2], 1e-9)
        }
    }

    @Test
    fun `lut interpolates between grid points`() {
        // A 2-point cube is the coarsest possible; the midpoint must be the average.
        val lut = identityLut(2)
        val out = LiveFilters.sampleLut(lut, 2, doubleArrayOf(0.5, 0.5, 0.5))
        assertEquals(0.5, out[0], 1e-9)
    }

    @Test
    fun `lut clamps out of range input`() {
        val lut = identityLut(3)
        val out = LiveFilters.sampleLut(lut, 3, doubleArrayOf(-1.0, 2.0, 0.5))
        assertEquals(0.0, out[0], 1e-9)
        assertEquals(1.0, out[1], 1e-9)
    }

    @Test
    fun `lut rejects a wrong sized array`() {
        try {
            LiveFilters.sampleLut(DoubleArray(10), 3, doubleArrayOf(0.5, 0.5, 0.5))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `lut sampler is deterministic`() {
        val lut = identityLut(4)
        val a = LiveFilters.sampleLut(lut, 4, doubleArrayOf(0.31, 0.62, 0.93))
        val b = LiveFilters.sampleLut(lut, 4, doubleArrayOf(0.31, 0.62, 0.93))
        assertTrue(a.contentEquals(b))
    }
}
