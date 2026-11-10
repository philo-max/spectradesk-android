package com.spectradesk.camera

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colorimetry for the phone, mirroring `spectradesk.core.colorimetry`.
 *
 * The formulas are the published CIE definitions and the constants match the
 * desktop implementation exactly (kappa = 24389/27, epsilon = 216/24389).  Any
 * divergence here would make a phone reading disagree with the workstation for
 * the same sample, which is the one thing a measurement tool must never do.
 *
 * Values are `Double` internally even though the sensor data is 8-bit: Lab is
 * sensitive enough that Float rounding is visible in the second decimal, and a
 * colour difference quoted to two decimals should be reproducible.
 */
object Colorimetry {

    const val KAPPA = 24389.0 / 27.0
    const val EPSILON = 216.0 / 24389.0

    /** Tristimulus of the D65 reference white, Y normalised to 100. */
    val WHITE_XYZ: DoubleArray by lazy {
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in 0 until ColorTables.COUNT) {
            val w = ColorTables.d65[i].toDouble()
            sx += w * ColorTables.xBar[i]
            sy += w * ColorTables.yBar[i]
            sz += w * ColorTables.zBar[i]
        }
        val k = 100.0 / sy
        doubleArrayOf(sx * k, sy * k, sz * k)
    }

    /** XYZ (Y of a perfect diffuser = 100) from a reflectance curve on the CIE grid. */
    fun spectrumToXyz(reflectance: DoubleArray): DoubleArray {
        require(reflectance.size == ColorTables.COUNT) {
            "expected ${ColorTables.COUNT} samples, got ${reflectance.size}"
        }
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in 0 until ColorTables.COUNT) {
            // Clipped, not rejected: a noisy camera can produce a negative
            // reflectance and an impossible chromaticity would poison the whole
            // downstream chain.
            val r = reflectance[i].coerceIn(0.0, 1.5)
            val w = ColorTables.d65[i].toDouble()
            sx += r * w * ColorTables.xBar[i]
            sy += r * w * ColorTables.yBar[i]
            sz += r * w * ColorTables.zBar[i]
        }
        val k = 100.0 / (0 until ColorTables.COUNT).sumOf { ColorTables.d65[it] * ColorTables.yBar[it].toDouble() }
        return doubleArrayOf(sx * k, sy * k, sz * k)
    }

    fun xyzToLab(xyz: DoubleArray, white: DoubleArray = WHITE_XYZ): DoubleArray {
        fun f(v: Double): Double = if (v > EPSILON) v.pow(1.0 / 3.0) else (KAPPA * v + 16.0) / 116.0
        val fx = f(xyz[0] / white[0])
        val fy = f(xyz[1] / white[1])
        val fz = f(xyz[2] / white[2])
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun labToXyz(lab: DoubleArray, white: DoubleArray = WHITE_XYZ): DoubleArray {
        val fy = (lab[0] + 16.0) / 116.0
        val fx = fy + lab[1] / 500.0
        val fz = fy - lab[2] / 200.0
        fun finv(t: Double): Double = if (t * t * t > EPSILON) t * t * t else (116.0 * t - 16.0) / KAPPA
        return doubleArrayOf(finv(fx) * white[0], finv(fy) * white[1], finv(fz) * white[2])
    }

    fun xyzToXy(xyz: DoubleArray): DoubleArray {
        val s = xyz[0] + xyz[1] + xyz[2]
        if (s <= 0.0) return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf(xyz[0] / s, xyz[1] / s)
    }

    fun labToLch(lab: DoubleArray): DoubleArray {
        val c = hypot(lab[1], lab[2])
        var h = Math.toDegrees(atan2(lab[2], lab[1]))
        if (h < 0) h += 360.0
        return doubleArrayOf(lab[0], c, h)
    }

    /**
     * CIEDE2000.  This is the number the whole app is judged on, so it is the
     * published algorithm verbatim rather than an approximation.
     */
    fun deltaE00(lab1: DoubleArray, lab2: DoubleArray): Double {
        val l1 = lab1[0]; val a1 = lab1[1]; val b1 = lab1[2]
        val l2 = lab2[0]; val a2 = lab2[1]; val b2 = lab2[2]

        val c1 = hypot(a1, b1)
        val c2 = hypot(a2, b2)
        val cBar = (c1 + c2) / 2.0
        val g = 0.5 * (1.0 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))

        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = hypot(a1p, b1)
        val c2p = hypot(a2p, b2)

        fun hue(a: Double, b: Double): Double {
            if (a == 0.0 && b == 0.0) return 0.0
            var h = Math.toDegrees(atan2(b, a))
            if (h < 0) h += 360.0
            return h
        }

        val h1p = hue(a1p, b1)
        val h2p = hue(a2p, b2)

        val dLp = l2 - l1
        val dCp = c2p - c1p

        var dhp: Double = if (c1p * c2p == 0.0) {
            0.0
        } else {
            var d = h2p - h1p
            if (d > 180.0) d -= 360.0
            else if (d < -180.0) d += 360.0
            d
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lBarP = (l1 + l2) / 2.0
        val cBarP = (c1p + c2p) / 2.0
        val hBarP = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }

        val t = 1.0 -
            0.17 * cos(Math.toRadians(hBarP - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hBarP)) +
            0.32 * cos(Math.toRadians(3.0 * hBarP + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hBarP - 63.0))

        val dTheta = 30.0 * kotlin.math.exp(-(((hBarP - 275.0) / 25.0) * ((hBarP - 275.0) / 25.0)))
        val rC = 2.0 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
        val sL = 1.0 + (0.015 * (lBarP - 50.0) * (lBarP - 50.0)) /
            sqrt(20.0 + (lBarP - 50.0) * (lBarP - 50.0))
        val sC = 1.0 + 0.045 * cBarP
        val sH = 1.0 + 0.015 * cBarP * t
        val rT = -sin(Math.toRadians(2.0 * dTheta)) * rC

        val termL = dLp / sL
        val termC = dCp / sC
        val termH = dHp / sH
        return sqrt(termL * termL + termC * termC + termH * termH + rT * termC * termH)
    }

    fun deltaEab(lab1: DoubleArray, lab2: DoubleArray): Double {
        val dl = lab1[0] - lab2[0]
        val da = lab1[1] - lab2[1]
        val db = lab1[2] - lab2[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Correlated colour temperature by McCamy's approximation. */
    fun cctFromXy(xy: DoubleArray): Double {
        val x = xy[0]
        val y = xy[1]
        val denom = 0.1858 - y
        if (abs(denom) < 1e-9) return 0.0
        val n = (x - 0.3320) / denom
        return 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
    }

    /** Linear sRGB from XYZ (D65), then the sRGB transfer curve. */
    fun xyzToSrgb(xyz: DoubleArray): DoubleArray {
        val x = xyz[0] / 100.0
        val y = xyz[1] / 100.0
        val z = xyz[2] / 100.0
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return doubleArrayOf(encode(r), encode(g), encode(b))
    }

    private fun encode(v: Double): Double {
        val c = v.coerceAtLeast(0.0)
        return if (c <= 0.0031308) c * 12.92
        else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    /** Decode an sRGB-encoded value (0..1) to linear light. */
    fun srgbToLinear(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /**
     * Approximate reflectance curve from three white-balanced camera channels.
     *
     * **This app does not use this for measurement.**  A three-channel sensor
     * cannot resolve a spectrum: the mapping is infinitely under-determined, and
     * any curve produced from it is a guess shaped by the basis functions, not a
     * measurement.  The function is kept only so a future spectral display has
     * something to plot, and is labelled as an estimate wherever it appears.
     *
     * Colour goes through [linearRgbToLab] instead, which is what a colorimeter
     * actually does: report the tristimulus value, do not invent the spectrum.
     */
    fun estimateReflectanceFromRgb(rgb: DoubleArray): DoubleArray {
        val out = DoubleArray(ColorTables.COUNT)
        for (i in 0 until ColorTables.COUNT) {
            val wl = ColorTables.wavelengths[i]
            val r = gaussian(wl, 600.0, 62.0)
            val g = gaussian(wl, 545.0, 52.0)
            val b = gaussian(wl, 455.0, 42.0)
            val sum = (r + g + b).coerceAtLeast(1e-6)
            out[i] = (((rgb[0] * r + rgb[1] * g + rgb[2] * b) / sum)).coerceIn(0.0, 1.5)
        }
        return out
    }

    /**
     * Linear sRGB (0..1, gamma already removed) to CIE XYZ, Y of white = 100.
     *
     * This is the standard sRGB matrix, and it is the correct path for a
     * three-channel camera: a colorimeter reports colour, not a spectrum, and
     * pretending otherwise is what produces impossible numbers.  With
     * `rgb = (1,1,1)` this yields `Y = 100` and `L* = 100`; with `rgb = (0.5,...)`
     * it yields `L* = 76.07`, which is the textbook value for mid-grey.
     */
    fun linearRgbToXyz(rgb: DoubleArray): DoubleArray {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        return doubleArrayOf(
            (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) * 100.0,
            (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) * 100.0,
            (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) * 100.0,
        )
    }

    /** Lab straight from linear sRGB, via [linearRgbToXyz]. */
    fun linearRgbToLab(rgb: DoubleArray): DoubleArray = xyzToLab(linearRgbToXyz(rgb))

    private fun gaussian(wl: Float, center: Double, sigma: Double): Double {
        val t = (wl - center) / sigma
        return kotlin.math.exp(-0.5 * t * t)
    }
}
