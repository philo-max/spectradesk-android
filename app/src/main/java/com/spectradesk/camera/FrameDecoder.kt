package com.spectradesk.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

/**
 * One captured frame, decoded to linear RGB plus the metadata a measurement
 * needs.
 *
 * `linearRgb` is the only thing colourimetry is allowed to read.  The sRGB
 * bitmap is kept for the preview and for saving, never for a number: mixing the
 * two is the classic bug where the picture looks right and the reading is wrong.
 */
data class Capture(
    val linearRgb: DoubleArray,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?,
    val timestampMs: Long,
    /** True when the frame was decoded from sRGB; false when taken as linear. */
    val gammaCorrected: Boolean,
    /** Mean linear RGB over the sampled region, 0..1. */
    val meanLinear: DoubleArray,
    val whiteBalanceGains: FloatArray? = null,
    val iso: Int = 0,
    val exposureNs: Long = 0L,
) {
    /** Relative reflectance against a white reference, clipped to a sane range. */
    fun reflectance(white: DoubleArray, dark: DoubleArray? = null): DoubleArray {
        val out = DoubleArray(3)
        for (i in 0 until 3) {
            val d = dark?.get(i) ?: 0.0
            val span = white[i] - d
            out[i] = if (span <= 1e-6) 0.0 else ((meanLinear[i] - d) / span).coerceIn(0.0, 1.5)
        }
        return out
    }
}

/**
 * Decoding and sampling of camera frames.
 *
 * Two decisions live here and both are physical claims:
 *
 * 1. **Gamma.**  Android hands back an sRGB-encoded `ImageProxy`.  With gamma
 *    correction on, every channel is decoded through the sRGB curve before it is
 *    averaged; with it off the bytes are taken as linear.  Averaging in the
 *    encoded domain would weight dark pixels far too heavily - a 3x3 patch of a
 *    gradient gives a different answer depending on which domain you average in,
 *    and only one of those answers is light.
 *
 * 2. **Region.**  The sampled patch is the middle of the frame, sized as a
 *    fraction of the shorter edge, which is what the reticle on screen shows.
 *    Sampling the whole frame would include the background and produce a
 *    meaningless average.
 */
object FrameDecoder {

    /** Fraction of the shorter edge used by the centre patch. */
    const val PATCH_FRACTION = 0.18

    /**
     * Long-edge cap for the decoded bitmap.
     *
     * 1200 px is ~7x the reticle size on a 12 MP capture, so the patch is still
     * sampled from tens of thousands of pixels.  Anything larger only costs
     * memory and time: the reticle covers 18% of the short edge, which at this
     * cap is still 216 px per side - far more than the noise floor needs.
     */
    const val MAX_DECODE_EDGE = 1200

    /**
     * Sample the centre patch as a robust mean.
     *
     * A plain mean over the patch is sensitive to a single specular highlight or
     * a dust speck: one blown pixel drags the whole reading.  This trims the
     * darkest and brightest 10% of each channel before averaging, which is what
     * a handheld colorimeter does mechanically by averaging over a small aperture
     * and is the cheapest way to make a phone reading repeatable.
     */
    fun sampleCentrePatch(
        bitmap: Bitmap,
        patchFraction: Double = PATCH_FRACTION,
        gammaCorrected: Boolean = true,
        trim: Double = 0.10,
    ): DoubleArray {
        val shortEdge = minOf(bitmap.width, bitmap.height)
        val side = (shortEdge * patchFraction).roundToInt().coerceAtLeast(3)
        val left = ((bitmap.width - side) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - side) / 2).coerceAtLeast(0)
        val w = side.coerceAtMost(bitmap.width - left)
        val h = side.coerceAtMost(bitmap.height - top)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)

        val r = DoubleArray(pixels.size)
        val g = DoubleArray(pixels.size)
        val b = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            r[i] = decode(((p shr 16) and 0xFF) / 255.0, gammaCorrected)
            g[i] = decode(((p shr 8) and 0xFF) / 255.0, gammaCorrected)
            b[i] = decode((p and 0xFF) / 255.0, gammaCorrected)
        }
        return doubleArrayOf(trimmedMean(r, trim), trimmedMean(g, trim), trimmedMean(b, trim))
    }

    /** Mean after discarding the lowest and highest `trim` fraction. */
    fun trimmedMean(values: DoubleArray, trim: Double = 0.10): Double {
        if (values.isEmpty()) return 0.0
        val t = trim.coerceIn(0.0, 0.45)
        if (t <= 0.0) return values.average()
        val sorted = values.sortedArray()
        val cut = (sorted.size * t).toInt()
        if (cut <= 0 || sorted.size - 2 * cut < 1) return values.average()
        var sum = 0.0
        for (i in cut until sorted.size - cut) sum += sorted[i]
        return sum / (sorted.size - 2 * cut)
    }

    /** Decode one 0..1 channel value according to the gamma setting. */
    fun decode(value: Double, gammaCorrected: Boolean): Double =
        if (gammaCorrected) Colorimetry.srgbToLinear(value) else value

    /** sRGB bitmap -> linear RGB arrays (row-major, 3 doubles per pixel). */
    fun toLinearRgb(bitmap: Bitmap, gammaCorrected: Boolean): DoubleArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = DoubleArray(w * h * 3)
        var o = 0
        for (p in pixels) {
            out[o++] = decode(((p shr 16) and 0xFF) / 255.0, gammaCorrected)
            out[o++] = decode(((p shr 8) and 0xFF) / 255.0, gammaCorrected)
            out[o++] = decode((p and 0xFF) / 255.0, gammaCorrected)
        }
        return out
    }

    /**
     * Convert an `ImageProxy` to a bitmap.
     *
     * CameraX gives JPEG for still capture, which is already sRGB-encoded and
     * orientation-tagged; that is the frame the sensor actually produced, so it
     * is what gets measured.
     *
     * A 12 MP JPEG is ~48 MB as ARGB_8888.  Decoding at full resolution is slow
     * enough to stall the main thread and, on a low-memory phone, to be killed
     * outright.  The measurement only needs the centre patch, so the decode is
     * subsampled to at most [MAX_DECODE_EDGE] on the long edge: still far more
     * pixels than the reticle covers, at a quarter of the memory.
     */
    fun imageProxyToBitmap(image: ImageProxy, maxEdge: Int = MAX_DECODE_EDGE): Bitmap {
        require(image.format == android.graphics.ImageFormat.JPEG) {
            "expected a JPEG image, got format ${image.format}"
        }
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("cannot decode the captured JPEG")
        val rot = runCatching { image.imageInfo.rotationDegrees }.getOrDefault(0)
        if (rot % 360 == 0) return raw
        val matrix = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }.getOrDefault(raw)
    }

    /** Largest power-of-two subsample that keeps the long edge at or above `maxEdge`. */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /** Mean linear RGB of a saved bitmap, for re-analysis of a stored shot. */
    fun meanLinearOf(bitmap: Bitmap, gammaCorrected: Boolean): DoubleArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (p in pixels) {
            r += decode(((p shr 16) and 0xFF) / 255.0, gammaCorrected)
            g += decode(((p shr 8) and 0xFF) / 255.0, gammaCorrected)
            b += decode((p and 0xFF) / 255.0, gammaCorrected)
        }
        val n = pixels.size.toDouble()
        return doubleArrayOf(r / n, g / n, b / n)
    }
}
