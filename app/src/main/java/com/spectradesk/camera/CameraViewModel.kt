package com.spectradesk.camera

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the screen needs to draw. */
data class UiState(
    val settings: CameraSettings = CameraSettings(),
    val calibration: CalibrationState = CalibrationState(),
    val activeStandardName: String? = null,
    val measurements: List<Measurement> = emptyList(),
    val lastShot: Bitmap? = null,
    val lastMeasurement: Measurement? = null,
    val busy: Boolean = false,
    /** True once AE/AWB are locked, which is what makes readings repeatable. */
    val exposureLocked: Boolean = false,
    /** Viewfinder filter.  Display only - never touches a measurement. */
    val filter: LiveFilter = LiveFilter.NONE,
    /**
     * Monotonic capture counter.
     *
     * The filtered preview is cached on this value: a new capture must invalidate
     * the cached render, or the screen shows the previous sample beside a reading
     * that describes the new one.
     */
    val shotCounter: Int = 0,
    val message: String? = null,
) {
    val activeStandard: ColorStandard?
        get() = activeStandardName?.let { Standards.byName(it) }

    val latestVerdict: Boolean?
        get() = lastMeasurement?.passed
}

/**
 * Owns all measurement state.
 *
 * The screen is a pure renderer: every number it shows comes from here, and every
 * action is a method.  That is the same separation the desktop app uses, and it
 * is what makes the two platforms comparable - the phone's arithmetic can be
 * checked against the workstation without driving a UI.
 */
class CameraViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var nextIndex = 1

    fun setGammaCorrected(enabled: Boolean) {
        _state.update { current ->
            if (current.settings.gammaCorrected == enabled) return@update current
            // Flipping GC changes what the incoming numbers *mean*, so any white
            // or dark reference taken under the old mode is invalid: it was
            // measured in a different domain.  Keeping it would silently divide
            // a linear reading by an sRGB-encoded reference - a ~2.3x error that
            // looks like a plausible colour.  The reference is dropped and the
            // UI says why.
            current.copy(
                settings = current.settings.copy(gammaCorrected = enabled),
                calibration = CalibrationState(),
                message = if (enabled) {
                    "已切换到 GC 开（sRGB→线性）：标定已清除，请重做白参考"
                } else {
                    "已切换到 GC 关（按线性处理）：标定已清除，请重做白参考"
                },
            )
        }
    }

    fun setStandard(name: String?) {
        _state.update { it.copy(activeStandardName = name) }
    }

    fun setTolerance(value: Double) {
        _state.update { it.copy(settings = it.settings.copy(targetTolerance = value)) }
    }

    fun setBusy(busy: Boolean) {
        _state.update { it.copy(busy = busy) }
    }

    fun message(text: String?) {
        _state.update { it.copy(message = text) }
    }

    /**
     * Record a white reference.
     *
     * The capture must be of a white card filling the reticle, under the same
     * light as the samples.  The value stored is the *linear* mean, so later
     * division happens in linear light where it is physically meaningful.
     *
     * Two rejections matter here, and both were added after review:
     *
     * * **Too dark.**  The old gate only required each channel to exceed 1e-4.
     *   With sRGB decoding on, a pixel value of 1 decodes to ~0.0003, so a
     *   nearly black reference passed and then amplified quantisation noise
     *   across the whole measurement.  A white card that dark means the light or
     *   the exposure is wrong, and the honest answer is to refuse.
     * * **Clipped.**  If every channel is at or near the ceiling, the true
     *   intensity is unknown - the sensor ran out of range - so the reference
     *   cannot describe the illuminant.
     */
    fun takeWhiteReference(capture: Capture, locked: Boolean = false): Boolean {
        val mean = capture.meanLinear
        val minChannel = mean.min()
        val maxChannel = mean.max()

        if (minChannel < MIN_WHITE_LEVEL) {
            _state.update {
                it.copy(
                    lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                    message = "白参考太暗（最暗通道 %.3f），检查光照或曝光".format(minChannel),
                )
            }
            return false
        }
        if (maxChannel > MAX_WHITE_LEVEL) {
            _state.update {
                it.copy(
                    lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                    message = "白参考过曝（最亮通道 %.3f），降低曝光或换张白卡".format(maxChannel),
                )
            }
            return false
        }
        // A reference with wildly different channels is not a neutral white -
        // most likely the card is tinted or a coloured object is in the patch.
        val spread = (maxChannel - minChannel) / maxChannel.coerceAtLeast(1e-6)
        if (spread > MAX_WHITE_SPREAD) {
            _state.update {
                it.copy(
                    lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                    message = "白参考偏色（通道差 %.0f%%），请用中性白卡".format(spread * 100),
                )
            }
            return false
        }

        _state.update {
            it.copy(
                calibration = CalibrationState(
                    white = mean,
                    dark = it.calibration.dark,
                    takenAtMs = System.currentTimeMillis(),
                ),
                lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                exposureLocked = locked,
                message = buildString {
                    append("白参考已记录 · ")
                    append(mean.joinToString(", ") { v -> "%.3f".format(v) })
                    if (!locked) append("（未锁曝光，读数会随光线漂移）")
                },
            )
        }
        return true
    }

    companion object {
        /** Below this linear level a white reference is noise, not a reference. */
        const val MIN_WHITE_LEVEL = 0.02

        /** Above this the sensor has clipped and the true level is unknown. */
        const val MAX_WHITE_LEVEL = 0.98

        /** Allowed relative spread between channels for a neutral reference. */
        const val MAX_WHITE_SPREAD = 0.35

        /**
         * Below this lightness a sample has no meaningful chromaticity, so a
         * colour temperature would be invented rather than measured.
         */
        const val MIN_L_FOR_CCT = 5.0
    }

    /** Record a dark reference (lens covered). */
    fun takeDarkReference(capture: Capture) {
        _state.update {
            it.copy(
                calibration = it.calibration.copy(
                    dark = capture.meanLinear,
                    takenAtMs = System.currentTimeMillis(),
                ),
                lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                message = "暗参考已记录",
            )
        }
    }

    /** Clear calibration; every subsequent measurement will be refused until redone. */
    fun clearCalibration() {
        _state.update {
            it.copy(
                calibration = CalibrationState(),
                exposureLocked = false,
                message = "标定已清除，需要重新做白平衡",
            )
        }
    }

    /** Record that the camera's AE/AWB state changed. */
    fun setExposureLocked(locked: Boolean) {
        _state.update { it.copy(exposureLocked = locked) }
    }

    /**
     * Choose a viewfinder filter.
     *
     * Deliberately does *not* touch the calibration or the history: the filter is
     * a display transform, so a white reference taken through a mono view is
     * still a valid linear white reference.  Anything else would make the switch
     * feel like it changed the measurement, which is exactly the confusion this
     * design avoids.
     */
    fun setFilter(kind: LiveFilter) {
        _state.update { it.copy(filter = kind) }
    }

    /**
     * Turn a capture into a measurement.
     *
     * Refuses when uncalibrated rather than producing a number: an uncalibrated
     * camera reading is a ratio against nothing, and printing it would be worse
     * than printing nothing.
     */
    fun measure(capture: Capture): Measurement? {
        val state = _state.value
        if (!state.calibration.isReady) {
            _state.update {
                it.copy(lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1, message = "需要先做白平衡：把白卡放进取景框再点「白参考」")
            }
            return null
        }

        // A capture that was in flight while the GC switch was flipped carries
        // the *old* transfer function.  Dividing it by a reference taken under
        // the new one is a silent ~2.3x error, so the stale frame is rejected
        // rather than measured.
        if (capture.gammaCorrected != state.settings.gammaCorrected) {
            _state.update {
                it.copy(
                    lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                    message = "伽马模式已改变，这张作废，请重新测量",
                )
            }
            return null
        }

        val reflectance = capture.reflectance(
            state.calibration.white!!,
            state.calibration.dark,
        )
        // A camera reports colour, not a spectrum.  Going through XYZ directly is
        // the honest path: a fabricated reflectance curve would put the sample
        // through two approximations instead of one, and it produced L* > 100.
        val xyz = Colorimetry.linearRgbToXyz(reflectance)
        val lab = Colorimetry.xyzToLab(xyz)
        val lch = Colorimetry.labToLch(lab)
        val xy = Colorimetry.xyzToXy(xyz)

        // Colour temperature is only defined for a light source.  A black or
        // very dark sample has no meaningful chromaticity, and the McCamy
        // approximation returns a plausible-looking 2021 K for (x,y) = (0,0) -
        // a number that is entirely fictional.  NaN is the honest answer and the
        // UI renders it as "—".
        val cct = if (lab[0] >= MIN_L_FOR_CCT && xy[0] > 0.0 && xy[1] > 0.0) {
            Colorimetry.cctFromXy(xy)
        } else {
            Double.NaN
        }

        val standard = state.activeStandard
        val deltaE: Double?
        val passed: Boolean?
        if (standard != null) {
            val de = Colorimetry.deltaE00(lab, standard.lab)
            deltaE = de
            passed = de <= standard.tolerance
        } else {
            deltaE = null
            passed = null
        }

        val measurement = Measurement(
            index = nextIndex++,
            label = "M${state.measurements.size + 1}",
            timestampMs = System.currentTimeMillis(),
            reflectance = reflectance,
            lab = lab,
            lch = lch,
            cct = cct,
            xy = xy,
            deltaE = deltaE,
            standardLab = standard?.lab,
            standardName = standard?.name,
            passed = passed,
            gammaCorrected = capture.gammaCorrected,
            iso = capture.iso,
            exposureNs = capture.exposureNs,
            exposureLocked = state.exposureLocked,
        )

        _state.update {
            it.copy(
                measurements = it.measurements + measurement,
                lastShot = capture.bitmap,
                    shotCounter = it.shotCounter + 1,
                lastMeasurement = measurement,
                message = if (standard != null) {
                    "ΔE00 ${"%.2f".format(deltaE)} vs ${standard.name} · ${if (passed == true) "通过" else "超差"}"
                } else {
                    "测量完成（未选标准，只记录颜色）"
                },
            )
        }
        return measurement
    }

    fun clearHistory() {
        nextIndex = 1
        _state.update { it.copy(measurements = emptyList(), lastMeasurement = null) }
    }
}
