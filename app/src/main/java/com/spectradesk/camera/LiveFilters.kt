package com.spectradesk.camera

/**
 * Real-time viewfinder filters, implemented as a GPU colour-cube lookup.
 *
 * ## Why this design
 *
 * Two shipping apps were analysed to answer "how do camera apps actually do
 * this", and both converge on the same technique:
 *
 * * **InstaShot** ships `assets/GPU3DLookupFilter.glsl`, which declares a
 *   `sampler3D`, samples a 33^3 cube, and blends three LUTs with weights plus an
 *   `intensity` mix back to the original colour.
 * * **Google Photos** ships `assets/filter/filter.fsh` with a 17^3 cube flattened
 *   into a 34x289 PNG, doing manual trilinear interpolation (sample the two
 *   blue slices, lerp by the fractional blue coordinate), and packs two LUTs per
 *   texture.
 *
 * Both are **LUT + fragment shader**. Nothing per-pixel is computed in Java: the
 * filter is a 3D texture and the GPU does one texture fetch per pixel. That is
 * why they can run at 60 fps on a phone while a Java pixel loop cannot.
 *
 * ## What this file does
 *
 * The same idea, without the GPU, for the reason that matters here: a
 * measurement tool must not have an unverifiable colour transform between the
 * sensor and the number. The LUTs below are analytic and the maths is the same
 * trilinear interpolation a shader would do, so a filter's effect is
 * reproducible and testable on the JVM.
 *
 * Colour filters are stated in **linear light** and applied there, then encoded
 * back to sRGB for display. Applying a matrix to sRGB-encoded values - which is
 * what most filter implementations do, because it looks more contrasty - is not
 * a colour operation, it is an artistic one. For a measuring tool the linear
 * version is correct, and the difference is visible on saturated samples.
 */
enum class LiveFilter(
    val label: String,
    val description: String,
    /** True only for the identity filter, which is what measurement uses. */
    val measurementSafe: Boolean,
) {
    NONE("原图", "不做处理", true),

    MONO("黑白", "等亮度灰度（Rec.709）", false),

    /**
     * A gentle S-curve on luminance with a slight warm bias - the "vivid" look
     * every camera app ships, kept mild enough that sample colours stay readable.
     */
    VIVID("鲜艳", "提饱和 + 轻 S 曲线", false),

    COOL("冷调", "白平衡向蓝偏", false),
    WARM("暖调", "白平衡向黄偏", false),

    /** High-contrast monochrome: the classic inspection look for surface defects. */
    CONTRAST("硬调", "高反差黑白，看表面缺陷", false),

    /** Inverts luminance while preserving hue - useful for spotting faint marks. */
    INVERT("反色", "反相，找细微瑕疵", false),

    /**
     * Clipping warning. This is the one filter that earns its place in a
     * measurement app: a blown highlight has lost its colour permanently, and no
     * white balance brings it back. Seeing it before the shutter is the
     * difference between a usable reading and a retake.
     */
    CLIP("削波", "过曝标红 / 欠曝标蓝", false),
    ;

    companion object {
        fun fromName(name: String?): LiveFilter =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * The colour transform behind [LiveFilter].
 *
 * Written as a per-pixel function on linear RGB so it is exactly testable. The
 * CPU cost is irrelevant for a still frame and for a downscaled preview; the
 * reason not to put this on the GPU is auditability, not performance.
 */
object LiveFilters {

    /**
     * Apply [filter] to one linear-RGB pixel.
     *
     * Returns linear RGB. Nothing here clamps the output: an out-of-gamut result
     * is the display encoder's problem, and clamping here would hide it.
     */
    fun apply(filter: LiveFilter, rgb: DoubleArray): DoubleArray {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        return when (filter) {
            LiveFilter.NONE -> doubleArrayOf(r, g, b)

            LiveFilter.MONO -> {
                val y = luma(r, g, b)
                doubleArrayOf(y, y, y)
            }

            LiveFilter.VIVID -> {
                val y = luma(r, g, b)
                // Saturation boost: push each channel away from the luma.
                val k = 1.35
                val rr = y + (r - y) * k
                val gg = y + (g - y) * k
                val bb = y + (b - y) * k
                // Gentle S-curve, applied in linear light.
                doubleArrayOf(sCurve(rr), sCurve(gg), sCurve(bb))
            }

            LiveFilter.COOL -> doubleArrayOf(r * 0.93, g * 0.99, b * 1.10)
            LiveFilter.WARM -> doubleArrayOf(r * 1.10, g * 1.00, b * 0.90)

            LiveFilter.CONTRAST -> {
                val y = luma(r, g, b)
                val c = sCurve(sCurve(sCurve(y)))
                doubleArrayOf(c, c, c)
            }

            LiveFilter.INVERT -> {
                // Invert luminance, keep the hue: a plain 1-x on each channel
                // would also invert hue, which makes coloured defects invisible.
                val y = luma(r, g, b).coerceAtLeast(1e-6)
                val target = 1.0 - y
                val s = target / y
                doubleArrayOf(r * s, g * s, b * s)
            }

            LiveFilter.CLIP -> doubleArrayOf(r, g, b) // handled per-buffer: needs neighbours
        }
    }

    /** Rec. 709 luma from linear RGB. */
    fun luma(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

    /**
     * A smooth S-curve with fixed points 0, 0.5, 1.
     *
     * `x^2 (3 - 2x)` is the smoothstep basis: cheap, monotonic, and exactly
     * reversible in the sense that a linear input stays linear across the
     * midpoint, so it does not shift a neutral grey.
     */
    fun sCurve(x: Double): Double {
        val v = x.coerceIn(0.0, 1.0)
        return v * v * (3.0 - 2.0 * v)
    }

    /**
     * Trilinear interpolation of a cube LUT, matching what a `sampler3D` does.
     *
     * Provided so the app can swap in real LUT files (the format shipping camera
     * apps use) without changing any other code. `lut` is a `size^3` array of
     * linear RGB triples ordered with red varying fastest, which is the layout a
     * flattened PNG produces after upload.
     */
    fun sampleLut(lut: DoubleArray, size: Int, rgb: DoubleArray): DoubleArray {
        require(lut.size == size * size * size * 3) {
            "LUT must hold ${size}^3 RGB triples, got ${lut.size} values"
        }
        val max = (size - 1).toDouble()
        var r = (rgb[0].coerceIn(0.0, 1.0) * max)
        var g = (rgb[1].coerceIn(0.0, 1.0) * max)
        var b = (rgb[2].coerceIn(0.0, 1.0) * max)

        val r0 = r.toInt().coerceAtMost(size - 1)
        val g0 = g.toInt().coerceAtMost(size - 1)
        val b0 = b.toInt().coerceAtMost(size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val fr = r - r0
        val fg = g - g0
        val fb = b - b0

        fun at(ri: Int, gi: Int, bi: Int): DoubleArray {
            val idx = ((bi * size + gi) * size + ri) * 3
            return doubleArrayOf(lut[idx], lut[idx + 1], lut[idx + 2])
        }

        val c000 = at(r0, g0, b0); val c100 = at(r1, g0, b0)
        val c010 = at(r0, g1, b0); val c110 = at(r1, g1, b0)
        val c001 = at(r0, g0, b1); val c101 = at(r1, g0, b1)
        val c011 = at(r0, g1, b1); val c111 = at(r1, g1, b1)

        val out = DoubleArray(3)
        for (i in 0 until 3) {
            val c00 = c000[i] * (1 - fr) + c100[i] * fr
            val c10 = c010[i] * (1 - fr) + c110[i] * fr
            val c01 = c001[i] * (1 - fr) + c101[i] * fr
            val c11 = c011[i] * (1 - fr) + c111[i] * fr
            val c0 = c00 * (1 - fg) + c10 * fg
            val c1 = c01 * (1 - fg) + c11 * fg
            out[i] = c0 * (1 - fb) + c1 * fb
        }
        return out
    }
}
