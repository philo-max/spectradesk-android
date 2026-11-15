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
    /** 正在录像。 */
    val recording: Boolean = false,
    /** 录像已录时长，秒。 */
    val recordingSeconds: Int = 0,
    /** 参数面板是否展开。 */
    val paramsOpen: Boolean = false,
    /** 是否显示实时滤镜（关掉可对比原图）。 */
    val liveFilterOn: Boolean = true,
    /** 设备物理朝向，驱动横竖屏自适应。 */
    val orientation: OrientationTracker.DeviceOrientation =
        OrientationTracker.DeviceOrientation.PORTRAIT,
    /** 实时重力传感器倾角与水平仪状态（对应 Moto Camera LevelerView）。 */
    val gravity: OrientationTracker.GravityState =
        OrientationTracker.GravityState(),
    /** 取景器上的对焦框。null = 不显示。 */
    val focusMark: FocusMark? = null,
    /** 参数面板当前分类。 */
    val paramsTab: ParamsTab = ParamsTab.LENS,
    /** 前置屏幕补光快门瞬间强光脉冲（对应 FrontDisplayFlash.mFlashFired）。 */
    val screenFlashPulse: Boolean = false,
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

    /** 实时滤镜强度。 */
    fun setFilterIntensity(value: Float) {
        _state.update { it.copy(settings = it.settings.copy(filterIntensity = value.coerceIn(0f, 1f))) }
    }

    /** 是否启用实时滤镜（关掉 = 看原图对比）。 */
    fun setLiveFilterOn(on: Boolean) {
        _state.update { it.copy(liveFilterOn = on) }
    }

    /** 变焦。范围由设备决定，可能从 0.6 起（超广角）。 */
    fun setZoom(ratio: Float, range: ClosedFloatingPointRange<Float> = 1f..10f) {
        _state.update {
            it.copy(settings = it.settings.copy(zoomRatio = ratio.coerceIn(range)))
        }
    }

    /** 切换前后置。切换会重建相机管线，所以同时把变焦复位到该镜头的下限。 */
    fun toggleLens() {
        _state.update {
            val next = if (it.settings.lensFacing ==
                androidx.camera.core.CameraSelector.LENS_FACING_BACK
            ) {
                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            } else {
                androidx.camera.core.CameraSelector.LENS_FACING_BACK
            }
            it.copy(
                settings = it.settings.copy(lensFacing = next, zoomRatio = 1f),
                message = if (next == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) {
                    "已切到前置（镜像）"
                } else {
                    "已切到后置"
                },
            )
        }
    }

    /** 取景器 / 成片比例。切换会重建相机管线（三条流都要跟着变）。 */
    fun setFrameAspect(aspect: FrameAspect) {
        _state.update {
            if (it.settings.frameAspect == aspect) it
            else it.copy(
                settings = it.settings.copy(frameAspect = aspect),
                message = "画幅已切到 ${aspect.label}",
            )
        }
    }

    /** 超广角开关。切换物理相机，不重建管线。 */
    fun setUltraWide(on: Boolean) {
        _state.update {
            it.copy(
                settings = it.settings.copy(ultraWide = on),
                message = if (on) "超广角已开" else "已回到主摄",
            )
        }
    }

    /** 手动对焦；null 交回自动。 */
    fun setManualFocus(diopters: Float?) {
        _state.update { it.copy(settings = it.settings.copy(manualFocus = diopters)) }
    }

    /** 水印：整体开关。 */
    fun setWatermarkEnabled(on: Boolean) {
        _state.update {
            it.copy(
                settings = it.settings.copy(watermark = it.settings.watermark.copy(enabled = on)),
                message = if (on) "水印已开" else "水印已关",
            )
        }
    }

    /** 水印：改任意字段。 */
    fun updateWatermark(block: (Watermark) -> Watermark) {
        _state.update {
            it.copy(settings = it.settings.copy(watermark = block(it.settings.watermark)))
        }
    }

    /** 设备朝向变化，驱动横竖屏自适应。 */
    fun setOrientation(o: OrientationTracker.DeviceOrientation) {
        _state.update { if (it.orientation == o) it else it.copy(orientation = o) }
    }

    /** 实时重力感应状态更新（含横竖屏象限 + LevelerView 倾角 + 平放检测）。 */
    fun updateGravity(g: OrientationTracker.GravityState) {
        _state.update {
            if (it.orientation == g.orientation && it.gravity == g) it
            else it.copy(orientation = g.orientation, gravity = g)
        }
    }

    /** 切换取景器重力电子水平仪开关。 */
    fun setLevelerEnabled(on: Boolean) {
        _state.update {
            if (it.settings.levelerEnabled == on) it
            else it.copy(
                settings = it.settings.copy(levelerEnabled = on),
                message = if (on) "重力电子水平仪已开启" else "重力电子水平仪已关闭",
            )
        }
    }

    /** 点按对焦的结果提示。 */
    fun reportFocus(ok: Boolean, x: Float, y: Float) {
        _state.update {
            it.copy(
                focusMark = FocusMark(x, y, locked = false, success = ok, id = ++focusSeq),
                message = if (ok) null else "对焦未成功（可能太近或光线不足）",
            )
        }
    }

    private var focusSeq = 0L

    /**
     * 开始一次点按对焦：立刻画框（白色 = 对焦中），返回这次的 id。
     *
     * 点按同时意味着"我要自动对焦"，所以顺手退出手动对焦 —— 否则
     * AF_MODE_OFF 还盖着，点了也不会动。
     */
    fun beginFocus(x: Float, y: Float, lock: Boolean): Long {
        val id = ++focusSeq
        _state.update {
            it.copy(
                focusMark = FocusMark(x, y, locked = lock, success = null, id = id),
                settings = it.settings.copy(manualFocus = null),
                message = if (lock) "AE/AF 已锁定 · 点按任意处解锁" else null,
            )
        }
        return id
    }

    /** 对焦结束。id 不匹配说明用户已经点了下一处，旧结果丢弃。 */
    fun finishFocus(id: Long, ok: Boolean) {
        _state.update {
            val mark = it.focusMark
            if (mark == null || mark.id != id) it
            else it.copy(focusMark = mark.copy(success = ok))
        }
    }

    /** 淡出对焦框（锁定的不淡出）。 */
    fun clearFocusMark(id: Long) {
        _state.update {
            val mark = it.focusMark
            if (mark == null || mark.id != id || mark.locked) it else it.copy(focusMark = null)
        }
    }

    fun setParamsTab(tab: ParamsTab) {
        _state.update { it.copy(paramsTab = tab) }
    }

    /**
     * 录像分辨率。录像中不允许改 —— 改它要重建相机会话，正在写的文件会被截断。
     * 滑到 4K / 8K 时自动开启「超清」CAS 多尺度细节重构算法。
     */
    fun setVideoResolution(res: VideoResolution) {
        _state.update {
            when {
                it.settings.videoResolution == res -> it
                it.recording -> it.copy(message = "录像中不能切换分辨率")
                else -> {
                    val autoClarity = if (res == VideoResolution.UHD_8K || res == VideoResolution.UHD) {
                        ClarityMode.ULTRA
                    } else {
                        it.settings.clarityMode
                    }
                    it.copy(
                        settings = it.settings.copy(
                            videoResolution = res,
                            clarityMode = autoClarity,
                        ),
                        message = if (res == VideoResolution.UHD_8K) {
                            "分辨率 → 8K · 已联动开启「超清」细节重构算法"
                        } else {
                            "录像分辨率 → ${res.label}"
                        },
                    )
                }
            }
        }
    }

    fun setVideoFps(fps: Int) {
        _state.update {
            when {
                it.settings.videoFps == fps -> it
                it.recording -> it.copy(message = "录像中不能切换帧率")
                else -> it.copy(
                    settings = it.settings.copy(videoFps = fps),
                    message = "录像帧率 → ${fps}fps",
                )
            }
        }
    }

    /** 算法画质增强档位（标准 / 高清 HydraSharp / 超清多尺度重构 / 超清HDR）。 */
    fun setClarityMode(mode: ClarityMode) {
        _state.update {
            if (it.settings.clarityMode == mode) it
            else it.copy(
                settings = it.settings.copy(clarityMode = mode),
                message = "画质算法 → ${mode.label}（${mode.desc}）",
            )
        }
    }

    /** 硬件防抖（EIS/OIS）+ 3DNR 降噪协同开关。 */
    fun setVideoStabilization(on: Boolean) {
        _state.update {
            if (it.settings.videoStabilization == on) it
            else it.copy(
                settings = it.settings.copy(videoStabilization = on),
                message = if (on) "已开启 EIS/OIS 硬件防抖 + 3DNR 协同" else "已关闭视频防抖",
            )
        }
    }

    /** 设置闪光灯 / 屏幕柔光环补光档位。 */
    fun setFlashMode(mode: FlashMode) {
        _state.update {
            if (it.settings.flashMode == mode) it
            else {
                val isFront = it.settings.lensFacing ==
                    androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                val desc = if (isFront) {
                    when (mode) {
                        FlashMode.OFF -> "前置屏幕补光 → 关闭"
                        FlashMode.AUTO -> "前置屏幕补光 → 自动柔光闪"
                        FlashMode.ON -> "前置屏幕补光 → 快门屏幕柔光闪"
                        FlashMode.TORCH -> "前置屏幕补光 → 柔光环常亮（屏幕峰值亮度）"
                    }
                } else {
                    when (mode) {
                        FlashMode.OFF -> "后置闪光灯 → 关闭"
                        FlashMode.AUTO -> "后置闪光灯 → 自动"
                        FlashMode.ON -> "后置闪光灯 → 开启（录像时自动转常亮）"
                        FlashMode.TORCH -> "后置闪光灯 → LED 常亮补光灯"
                    }
                }
                it.copy(
                    settings = it.settings.copy(flashMode = mode),
                    message = desc,
                )
            }
        }
    }

    /** 取景器左上角快捷循环切换闪光灯档位：关 → 自动 → 开启 → 常亮补光。 */
    fun cycleFlashMode() {
        val current = _state.value.settings.flashMode
        val next = when (current) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        setFlashMode(next)
    }

    /** 设置前置屏幕柔光环色温（暖阳柔光 / 自然冷白 / 蜜桃粉光）。 */
    fun setScreenFlashTone(tone: ScreenFlashTone) {
        _state.update {
            it.copy(
                settings = it.settings.copy(screenFlashTone = tone),
                message = "前置柔光色温 → ${tone.label}",
            )
        }
    }

    /** 快门触发前置屏幕强光脉冲。 */
    fun setScreenFlashPulse(active: Boolean) {
        _state.update { it.copy(screenFlashPulse = active) }
    }

    /** 重绑后变焦范围可能变了（切镜头 / 物理超广角），把当前值夹进去。 */
    fun clampZoom(range: ClosedFloatingPointRange<Float>) {
        _state.update {
            val z = it.settings.zoomRatio
            val c = z.coerceIn(range)
            if (c == z) it else it.copy(settings = it.settings.copy(zoomRatio = c))
        }
    }

    /** 手动调色：拖滑杆时高频调用，只写状态，不下发相机。 */
    fun setGradeChannel(channel: ColorGrade.Channel, value: Float) {
        _state.update {
            val next = channel.set(it.settings.grade, value.coerceIn(channel.min, channel.max))
            it.copy(
                settings = it.settings.copy(grade = next, gradePreset = null),
            )
        }
    }

    /** 应用一个调色预设。 */
    fun applyGradePreset(name: String) {
        val preset = ColorGrade.presets.firstOrNull { it.first == name } ?: return
        _state.update {
            it.copy(settings = it.settings.copy(grade = preset.second, gradePreset = name))
        }
    }

    /** 还原全部调色。 */
    fun resetGrade() {
        _state.update {
            it.copy(
                settings = it.settings.copy(grade = ColorGrade.NONE, gradePreset = "原片"),
                message = "调色已还原",
            )
        }
    }

    /**
     * 曝光补偿。
     *
     * 这是唯一一个既改观感又改测量读数的参数 —— 换曝光等于换了物理条件，
     * 旧的白参考不再成立。所以改它要清标定，和切 GC 同理。
     */
    fun setExposureIndex(index: Int, stepEv: Double = 1.0 / 3.0) {
        _state.update { current ->
            if (current.settings.exposureIndex == index) return@update current
            current.copy(
                settings = current.settings.copy(exposureIndex = index),
                calibration = CalibrationState(),
                exposureLocked = false,
                message = "曝光补偿 %+.1f EV · 标定已清除".format(index * stepEv),
            )
        }
    }

    /**
     * 用设备真实的曝光范围夹住当前索引。
     *
     * 相机绑定前拿不到范围，滑块可能把索引推到设备不支持的档位
     * （实测出现过 -3.7 EV，而真机范围远小于此）。绑定完成后用它校正一次，
     * 越界就归零并说明，而不是让一个不存在的档位留在状态里。
     */
    fun clampExposureIndex(range: IntRange) {
        if (range.last <= range.first) return
        _state.update { current ->
            val idx = current.settings.exposureIndex
            if (idx in range) current
            else current.copy(
                settings = current.settings.copy(exposureIndex = 0),
                calibration = CalibrationState(),
                exposureLocked = false,
                message = "曝光补偿超出设备范围，已归零",
            )
        }
    }

    /** 录像是否录音。 */
    fun setAudioEnabled(on: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(audioEnabled = on)) }
    }

    fun setRecording(recording: Boolean) {
        _state.update { it.copy(recording = recording, recordingSeconds = if (!recording) 0 else it.recordingSeconds) }
    }

    fun setRecordingSeconds(seconds: Int) {
        _state.update { it.copy(recordingSeconds = seconds) }
    }

    fun setParamsOpen(open: Boolean) {
        _state.update { it.copy(paramsOpen = open) }
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
