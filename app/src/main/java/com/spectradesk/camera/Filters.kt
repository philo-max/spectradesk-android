/**
 * CPU reference implementations of the same looks the GPU shader applies.
 *
 * Kept deliberately: [LiveFilterRenderer] draws the viewfinder on the GPU, but a
 * measuring tool needs a way to reproduce a filter's effect off-device - in a
 * test, in a report thumbnail, or when the GL context is unavailable. These
 * functions are the single source of truth for what each look *means*, and
 * FiltersTest pins them.
 *
 * The GPU path and this one share the same enum ordinals and the same linear-light
 * convention, so they cannot drift without a test failing.
 */
package com.spectradesk.camera

/**
 * Display filters applied to the viewfinder.
 *
 * These exist for **framing only**: they help the operator see edges, judge
 * evenness, or spot a highlight.  They never touch the measurement path - the
 * numbers always come from the untouched centre patch, which is why
 * [FilterKind.measurementSafe] is false for every filter that alters pixels.
 *
 * This separation matters more than it looks.  A filter that made a sample look
 * better while silently changing the reading would be a tool that lies; a filter
 * that only changes what the eye sees is a legitimate viewing aid.  The UI labels
 * the viewfinder as filtered whenever one is active.
 */
enum class FilterKind(
    val label: String,
    val description: String,
    /** True only for the identity filter. */
    val measurementSafe: Boolean,
) {
    NONE("原图", "不做任何处理", true),
    MONO("灰度", "去掉颜色，只看明暗层次", false),
    EDGE("边缘", "Sobel 边缘，判断平整度和边界", false),
    FALSE_COLOR("伪彩", "按亮度映射色阶，找亮度不均", false),
    CLIP("削波", "高亮标红、暗部标蓝，找过曝/欠曝", false),
    GRID("网格", "叠加九宫格与同心圆，辅助对位", true),
    ;

    companion object {
        fun fromName(name: String?): FilterKind =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * Pixel transforms for the viewfinder.
 *
 * All functions take and return ARGB ints so the caller can work on an
 * `IntArray` straight from `Bitmap.getPixels`, which keeps the preview loop
 * allocation-free.  Everything here runs on the capture thread at ~15 fps on a
 * downscaled preview, so simple integer math is the right trade-off.
 */
object Filters {

    /** Apply [kind] to an ARGB pixel buffer in place. */
    fun apply(kind: FilterKind, pixels: IntArray, width: Int, height: Int) {
        when (kind) {
            FilterKind.NONE, FilterKind.GRID -> Unit
            FilterKind.MONO -> mono(pixels)
            FilterKind.EDGE -> edge(pixels, width, height)
            FilterKind.FALSE_COLOR -> falseColor(pixels)
            FilterKind.CLIP -> clip(pixels)
        }
    }

    /** Rec. 709 luma, applied in place. */
    private fun mono(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 54 + g * 183 + b * 19) shr 8
            pixels[i] = (p and 0xFF000000.toInt()) or (y shl 16) or (y shl 8) or y
        }
    }

    /**
     * Sobel magnitude on luma.
     *
     * Reads from a copy because a Sobel operator that consumed its own output
     * would smear; the extra allocation is once per frame on a small preview and
     * is cheaper than getting the kernel wrong.
     */
    private fun edge(pixels: IntArray, width: Int, height: Int) {
        if (width < 3 || height < 3) return
        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (r * 54 + g * 183 + b * 19) shr 8
        }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val tl = luma[i - width - 1]; val tc = luma[i - width]; val tr = luma[i - width + 1]
                val ml = luma[i - 1]; val mr = luma[i + 1]
                val bl = luma[i + width - 1]; val bc = luma[i + width]; val br = luma[i + width + 1]
                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                val mag = (kotlin.math.abs(gx) + kotlin.math.abs(gy)).coerceAtMost(255)
                pixels[i] = (0xFF000000.toInt()) or (mag shl 16) or (mag shl 8) or mag
            }
        }
    }

    /**
     * Map luma onto a blue-cyan-yellow-red ramp.
     *
     * The same ramp a thermal camera uses, which operators already read
     * fluently: dark is blue, mid is cyan/green, bright is red.
     */
    private fun falseColor(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = ((r * 54 + g * 183 + b * 19) shr 8).coerceIn(0, 255)
            val t = y / 255.0
            val rr: Int
            val gg: Int
            val bb: Int
            if (t < 0.25) {
                // blue -> cyan
                val k = t / 0.25
                rr = 0
                gg = (k * 255).toInt()
                bb = 255
            } else if (t < 0.5) {
                // cyan -> green
                val k = (t - 0.25) / 0.25
                rr = 0
                gg = 255
                bb = ((1 - k) * 255).toInt()
            } else if (t < 0.75) {
                // green -> yellow
                val k = (t - 0.5) / 0.25
                rr = (k * 255).toInt()
                gg = 255
                bb = 0
            } else {
                // yellow -> red
                val k = (t - 0.75) / 0.25
                rr = 255
                gg = ((1 - k) * 255).toInt()
                bb = 0
            }
            pixels[i] = (0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /**
     * Mark clipping: pixels at or above 250 go red, at or below 5 go blue.
     *
     * This is the filter with the most practical value for a measurement tool:
     * a blown highlight has lost its colour information permanently, and no
     * amount of white balancing brings it back.  Seeing it before the shutter is
     * the difference between a usable reading and a retake.
     */
    private fun clip(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            pixels[i] = when {
                maxC >= 250 -> (0xFF000000.toInt()) or (0xFF shl 16) or (0x30 shl 8) or 0x30
                minC <= 5 && maxC <= 20 -> (0xFF000000.toInt()) or (0x30 shl 16) or (0x60 shl 8) or 0xFF
                else -> {
                    // Dim everything else so the markers stand out.
                    val rr = (r * 3) / 5
                    val gg = (g * 3) / 5
                    val bb = (b * 3) / 5
                    (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
    }
}
