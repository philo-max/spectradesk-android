package com.spectradesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the viewfinder filters.
 *
 * The critical invariant is that a filter never changes a measurement.  That is
 * enforced structurally - `Filters.apply` works on a preview copy, and the
 * measurement path reads the untouched capture - but the enum's
 * `measurementSafe` flag is what the UI uses to warn, so it is tested too.
 */
class FiltersTest {

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    // ------------------------------------------------------------ properties

    @Test
    fun `only the identity filter is measurement safe`() {
        // If this ever fails, the UI would be telling the operator that a
        // colour-altering filter is safe to measure through.
        val safe = FilterKind.entries.filter { it.measurementSafe }
        assertEquals(listOf(FilterKind.NONE, FilterKind.GRID), safe)
    }

    @Test
    fun `every filter has a label and description`() {
        for (k in FilterKind.entries) {
            assertTrue("${k.name} has no label", k.label.isNotBlank())
            assertTrue("${k.name} has no description", k.description.isNotBlank())
        }
    }

    @Test
    fun `unknown name falls back to none`() {
        assertEquals(FilterKind.NONE, FilterKind.fromName("nope"))
        assertEquals(FilterKind.NONE, FilterKind.fromName(null))
        assertEquals(FilterKind.EDGE, FilterKind.fromName("EDGE"))
    }

    // ----------------------------------------------------------------- none

    @Test
    fun `none leaves pixels untouched`() {
        val pixels = intArrayOf(argb(10, 20, 30), argb(200, 100, 50))
        val copy = pixels.copyOf()
        Filters.apply(FilterKind.NONE, pixels, 2, 1)
        assertEquals(copy.toList(), pixels.toList())
    }

    @Test
    fun `grid leaves pixels untouched`() {
        // The grid is drawn as an overlay, not baked into the buffer, so the
        // pixel data must be identical.
        val pixels = intArrayOf(argb(1, 2, 3))
        val copy = pixels.copyOf()
        Filters.apply(FilterKind.GRID, pixels, 1, 1)
        assertEquals(copy.toList(), pixels.toList())
    }

    // ----------------------------------------------------------------- mono

    @Test
    fun `mono makes channels equal`() {
        val pixels = intArrayOf(argb(200, 40, 90))
        Filters.apply(FilterKind.MONO, pixels, 1, 1)
        assertEquals(red(pixels[0]), green(pixels[0]))
        assertEquals(green(pixels[0]), blue(pixels[0]))
    }

    @Test
    fun `mono preserves relative brightness`() {
        val dark = intArrayOf(argb(20, 20, 20))
        val bright = intArrayOf(argb(200, 200, 200))
        Filters.apply(FilterKind.MONO, dark, 1, 1)
        Filters.apply(FilterKind.MONO, bright, 1, 1)
        assertTrue(red(bright[0]) > red(dark[0]))
    }

    @Test
    fun `mono of a neutral colour is nearly identity`() {
        val pixels = intArrayOf(argb(128, 128, 128))
        Filters.apply(FilterKind.MONO, pixels, 1, 1)
        assertEquals(128.0, red(pixels[0]).toDouble(), 2.0)
    }

    // ----------------------------------------------------------------- edge

    @Test
    fun `edge of a flat image is black`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { argb(120, 120, 120) }
        Filters.apply(FilterKind.EDGE, pixels, w, h)
        // Interior pixels have no gradient; the border is left as-is by design.
        assertEquals(0.0, red(pixels[4 * w + 4]).toDouble(), 2.0)
    }

    @Test
    fun `edge responds to a step`() {
        val w = 9
        val h = 5
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) argb(0, 0, 0) else argb(255, 255, 255)
        }
        Filters.apply(FilterKind.EDGE, pixels, w, h)
        // The column at the boundary must be brighter than a flat interior one.
        val boundary = red(pixels[2 * w + (w / 2)])
        val interior = red(pixels[2 * w + 1])
        assertTrue("boundary=$boundary interior=$interior", boundary > interior)
    }

    @Test
    fun `edge handles tiny buffers without crashing`() {
        val pixels = IntArray(4) { argb(100, 100, 100) }
        Filters.apply(FilterKind.EDGE, pixels, 2, 2)
    }

    // ---------------------------------------------------------- false colour

    @Test
    fun `false colour maps dark to blue and bright to red`() {
        val dark = intArrayOf(argb(0, 0, 0))
        val bright = intArrayOf(argb(255, 255, 255))
        Filters.apply(FilterKind.FALSE_COLOR, dark, 1, 1)
        Filters.apply(FilterKind.FALSE_COLOR, bright, 1, 1)
        // Dark end of the ramp is blue-dominant, bright end is red-dominant.
        assertTrue(blue(dark[0]) > red(dark[0]))
        assertTrue(red(bright[0]) > blue(bright[0]))
    }

    @Test
    fun `false colour is deterministic`() {
        val a = intArrayOf(argb(90, 110, 130))
        val b = a.copyOf()
        Filters.apply(FilterKind.FALSE_COLOR, a, 1, 1)
        Filters.apply(FilterKind.FALSE_COLOR, b, 1, 1)
        assertEquals(a[0], b[0])
    }

    // ----------------------------------------------------------------- clip

    @Test
    fun `clip marks a blown highlight red`() {
        val pixels = intArrayOf(argb(255, 255, 255))
        Filters.apply(FilterKind.CLIP, pixels, 1, 1)
        assertTrue(red(pixels[0]) > 200)
        assertTrue(green(pixels[0]) < 100)
    }

    @Test
    fun `clip marks deep shadow blue`() {
        val pixels = intArrayOf(argb(2, 2, 2))
        Filters.apply(FilterKind.CLIP, pixels, 1, 1)
        assertTrue(blue(pixels[0]) > 200)
        assertTrue(red(pixels[0]) < 100)
    }

    @Test
    fun `clip leaves mid tones as dimmed versions`() {
        val pixels = intArrayOf(argb(120, 120, 120))
        Filters.apply(FilterKind.CLIP, pixels, 1, 1)
        assertEquals(72.0, red(pixels[0]).toDouble(), 2.0)
    }

    @Test
    fun `clip does not confuse a saturated but unclipped colour`() {
        // Pure red at 240 is bright but not clipped; it must not be marked.
        val pixels = intArrayOf(argb(240, 20, 20))
        Filters.apply(FilterKind.CLIP, pixels, 1, 1)
        assertNotEquals(255, red(pixels[0]))
    }
}
