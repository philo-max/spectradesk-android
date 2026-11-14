package com.spectradesk.camera

import kotlin.math.abs

/**
 * Measurement state for the camera: white reference, dark reference, and the
 * settings that decide what a captured frame means.
 *
 * A phone camera is a three-channel sensor with unknown per-channel gains that
 * drift with exposure and light source.  Without a white reference its output is
 * a ratio of nothing in particular, so [CalibrationState.isReady] gates every
 * measurement and the UI refuses rather than printing a plausible number.
 */
data class CalibrationState(
    val white: DoubleArray? = null,
    val dark: DoubleArray? = null,
    val takenAtMs: Long = 0L,
) {
    val isReady: Boolean
        get() {
            val w = white ?: return false
            val d = dark ?: doubleArrayOf(0.0, 0.0, 0.0)
            return (0 until 3).all { w[it] - d[it] > 1e-4 }
        }

    fun describe(): String {
        val w = white ?: return "未标定"
        val d = dark
        val wText = w.joinToString(", ") { String.format("%.3f", it) }
        return if (d == null) "白参考 $wText" else
            "白参考 $wText / 暗 ${
                d.joinToString(", ") { String.format("%.3f", it) }
            }"
    }
}

/**
 * Everything the user can change about a capture session.
 *
 * `gammaCorrected` is the "GC" switch.  It is not a display preference: it states
 * whether the incoming bytes are sRGB-encoded (the normal case for a phone) or
 * already linear (some industrial cameras).  The two produce different Lab values
 * for the same scene, so it is shown on screen at all times.
 */
data class CameraSettings(
    val gammaCorrected: Boolean = true,
    val patchFraction: Double = FrameDecoder.PATCH_FRACTION,
    val torchOn: Boolean = false,
    val lensFacing: Int = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
    val targetTolerance: Double = 2.0,
    /** 实时滤镜强度，0 = 原图，1 = 全量。 */
    val filterIntensity: Float = 1f,
    /** 变焦倍率。 */
    val zoomRatio: Float = 1f,
    /** 曝光补偿档位索引。0 = 自动不加补偿。 */
    val exposureIndex: Int = 0,
    /** 录像是否录音。 */
    val audioEnabled: Boolean = true,
    /** 手动调色。全零 = 不调色，与之前行为一致。 */
    val grade: ColorGrade = ColorGrade.NONE,
    /** 界面用的调色预设名，null = 手动调过、不对应任何预设。 */
    val gradePreset: String? = "原片",
    /**
     * 取景器与成片的宽高比。
     *
     * 这不是纯显示选项：它决定预览流、照片、取景器三者是否同比例。
     * 三者不一致时，"框住的"和"拍到的"就不是同一块画面 —— 这是相机
     * 应用最容易被投诉的那类问题。
     */
    val frameAspect: FrameAspect = FrameAspect.RATIO_16_9,
) {
    fun gammaLabel(): String =
        if (gammaCorrected) "GC 开 · sRGB→线性" else "GC 关 · 按线性处理"
}

/** One measurement, immutable after creation. */
data class Measurement(
    val index: Int,
    val label: String,
    val timestampMs: Long,
    val reflectance: DoubleArray,
    val lab: DoubleArray,
    val lch: DoubleArray,
    val cct: Double,
    val xy: DoubleArray,
    val deltaE: Double?,
    val standardLab: DoubleArray?,
    val standardName: String?,
    val passed: Boolean?,
    val gammaCorrected: Boolean,
    val iso: Int,
    val exposureNs: Long,
    /**
     * Whether AE/AWB were locked when this reading was taken.
     *
     * Stored per record rather than read from live state: the export used to
     * stamp every row with the *current* lock flag, so clearing the calibration
     * silently relabelled all past measurements as unlocked and rewrote history.
     */
    val exposureLocked: Boolean = false,
) {
    fun summary(): String {
        val de = deltaE?.let { String.format("ΔE %.2f", it) } ?: "ΔE —"
        return "L*%.1f a*%.1f b*%.1f  %s".format(lab[0], lab[1], lab[2], de)
    }

    fun verdictText(): String = when (passed) {
        null -> "未判定"
        true -> "通过"
        false -> "超差"
    }
}

/** Named reference colour, the "standard" half of a standard/sample pair. */
data class ColorStandard(
    val name: String,
    val lab: DoubleArray,
    val tolerance: Double = 2.0,
) {
    fun evaluate(sampleLab: DoubleArray): Pair<Double, Boolean> {
        val de = Colorimetry.deltaE00(sampleLab, lab)
        return de to (de <= tolerance)
    }
}

/**
 * A small, explainable standard set.
 *
 * These are analytic references, not measured standards: they exist so the app
 * has a defensible target without shipping proprietary colour data.  The Lab
 * values are computed from the same reflectance curves the desktop app uses for
 * its neutral and pigment families, so a phone check against "grey 50" means the
 * same thing on both platforms.
 */
object Standards {

    private fun grey(level: Double): DoubleArray = DoubleArray(ColorTables.COUNT) { level }

    private fun gaussianBump(wl: Float, center: Double, sigma: Double, amp: Double): Double {
        val t = (wl - center) / sigma
        return amp * kotlin.math.exp(-0.5 * t * t)
    }

    private fun curve(vararg bumps: DoubleArray, base: Double): DoubleArray =
        DoubleArray(ColorTables.COUNT) { i ->
            val wl = ColorTables.wavelengths[i]
            var v = base
            for (b in bumps) v += gaussianBump(wl, b[0], b[1], b[2])
            v.coerceIn(0.0, 1.5)
        }

    private fun labOf(reflectance: DoubleArray): DoubleArray =
        Colorimetry.xyzToLab(Colorimetry.spectrumToXyz(reflectance))

    val all: List<ColorStandard> by lazy {
        listOf(
            ColorStandard("灰 50", labOf(grey(0.50)), 1.0),
            ColorStandard("灰 90", labOf(grey(0.90)), 1.0),
            ColorStandard("陶瓷白", labOf(curve(doubleArrayOf(450.0, 60.0, 0.12), base = 0.72)), 1.5),
            ColorStandard("纺织青", labOf(curve(
                doubleArrayOf(500.0, 45.0, 0.45),
                doubleArrayOf(470.0, 40.0, 0.12),
                base = 0.08,
            )), 2.0),
            ColorStandard("氧化铁红", labOf(curve(
                doubleArrayOf(640.0, 70.0, 0.55),
                doubleArrayOf(560.0, 60.0, 0.08),
                base = 0.05,
            )), 2.0),
            ColorStandard("浅肤色", labOf(curve(
                doubleArrayOf(620.0, 120.0, 0.42),
                doubleArrayOf(520.0, 90.0, 0.10),
                base = 0.20,
            )), 3.0),
        )
    }

    fun byName(name: String): ColorStandard? = all.firstOrNull { it.name == name }
}

/**
 * 取景器与成片的宽高比。
 *
 * ## 为什么这是一个真选项，而不是"随手改个值"
 *
 * 相机应用里最容易出错的地方就是**三条流比例不一致**：
 * 预览流、照片、取景器 View。任意两者不同，用户框住的和他拍到的
 * 就不是同一块画面。
 *
 * 本项目的实测教训：预览默认给了 4:3（1632×1224），取景器是 0.569 的
 * 竖屏，shader 只能横向裁掉 24% —— 等效多出 1.32× 变焦，观感就是
 * "镜头被拉长了"。同时照片给的是 4:3 4096×3072，比取景器宽，
 * 成片比看到的多出上下一条。
 *
 * 所以这里把比例做成显式设置，三条流都跟着它走。
 *
 * ## 为什么默认 16:9
 *
 * 竖屏手机 16:9 的取景框（0.5625）几乎正好等于屏幕可用区域的比例，
 * 不裁切也不留黑边，等效变焦正好 1.00×。4:3 会裁掉左右，等效 1.33×。
 * 同类应用（InstaShot 的 AspectRatioFrameLayout）也是按固定比例测量
 * 取景器，而不是让它自由填满。
 */
enum class FrameAspect(val label: String, val ratio: Float) {
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    ;

    /** 竖屏取景器用的高宽比（Compose 的 aspectRatio 是宽/高）。 */
    val portraitRatio: Float get() = 1f / ratio

    companion object {
        fun fromName(name: String?): FrameAspect =
            entries.firstOrNull { it.name == name } ?: RATIO_16_9
    }
}

/** Small helpers shared by the UI. */
object Format {
    fun lab(lab: DoubleArray): String = "L*%.2f  a*%.2f  b*%.2f".format(lab[0], lab[1], lab[2])

    fun de(value: Double?): String = value?.let { "%.2f".format(it) } ?: "—"

    fun signed(value: Double): String = "%+.2f".format(value)

    /** Is this difference visually obvious? Roughly 2.3 dE00 is a just-noticeable step. */
    fun isVisible(de: Double): Boolean = abs(de) > 2.3
}

