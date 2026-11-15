package com.spectradesk.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/*
 * 仪器配色方案（参考 Fluke / Keyence 的手持仪表风格）：
 * - 底色用近黑石墨灰，而非偏蓝的"IDE 深色"，让取景画面与样品色不被环境色干扰。
 * - primary = 仪表琥珀黄：只给"主操作"（快门、准星、选中态），保证视线第一落点。
 * - secondary = 信号蓝：表示"曝光/白平衡已锁定"这类锁存状态。
 * - tertiary = 合格绿：通过判定 + 已标定。
 * - error = 告警红：超差 + 未标定 + 显示用滤镜警告。
 * 所有颜色只通过 MaterialTheme.colorScheme 取用，界面里不再出现散落的 Color(0x...)。
 */
private val InstrumentColors = darkColorScheme(
    primary = Color(0xFFFFC21A),
    onPrimary = Color(0xFF221A00),
    primaryContainer = Color(0xFF3A2E00),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF4DA8FF),
    onSecondary = Color(0xFF00213F),
    secondaryContainer = Color(0xFF0D2A45),
    onSecondaryContainer = Color(0xFFB9DCFF),
    tertiary = Color(0xFF3DD68C),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF0B3A25),
    onTertiaryContainer = Color(0xFF9EF5C5),
    error = Color(0xFFFF5A4E),
    onError = Color(0xFF410001),
    errorContainer = Color(0xFF4A1210),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF07090B),
    onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF0F1317),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF182027),
    onSurfaceVariant = Color(0xFF8A97A5),
    outline = Color(0xFF3A4652),
    outlineVariant = Color(0xFF242D36),
    scrim = Color(0xFF000000),
)

/*
 * 排版层级：数字一律等宽（读数跳动时不抖动、小数点对齐），按重要性分三级：
 * displayMedium = ΔE 主读数；headlineSmall = L*a*b*；titleSmall = 次要派生量。
 * 标签统一 labelSmall：小字、字距加宽、弱化颜色，只起"刻度说明"作用。
 */
private val Mono = FontFamily.Monospace

private val InstrumentTypography = Typography(
    displayMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.8.sp),
)

private val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp)

private val PanelShape = RoundedCornerShape(12.dp)
private val ChipShape = RoundedCornerShape(50)
private val SHUTTER_SIZE = 84.dp
private const val OVERLAY_ALPHA = 0.72f
private const val TINT_ALPHA = 0.14f

@Composable
private fun SpectraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InstrumentColors, typography = InstrumentTypography, content = content)
}

class MainActivity : ComponentActivity() {

    private var controller: CameraController? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // 相机是硬需求；麦克风可选（没授权就录无声视频）。
            if (granted[Manifest.permission.CAMERA] == true) {
                recreate()
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        }

        controller = CameraController(this)

        setContent {
            SpectraTheme {
                CameraScreen(controller)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.release()
        controller = null
    }
}

@Composable
fun CameraScreen(controller: CameraController?) {
    val vm: CameraViewModel = viewModel()
    val state by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var previewView by remember { mutableStateOf<PreviewSurfaceView?>(null) }
    var bound by remember { mutableStateOf(false) }
    // 设备能力（变焦范围、曝光范围、录像档位）。绑定完成后由 bind() 的返回值填充。
    var caps by remember { mutableStateOf<CameraController.CapabilitySnapshot?>(null) }
    // 取景器内是否展开精细变焦滑杆。
    var zoomScrubberOpen by remember { mutableStateOf(false) }
    // 取景器容器的实际像素尺寸，供对焦框精确定位。
    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }

    // Camera binding must NOT run inside a LaunchedEffect keyed on things that
    // change often (zoom, filter, grade). Only session-level parameters
    // (lensFacing, frameAspect, videoResolution, videoFps, ultraWide physical)
    // trigger a rebind.
    LaunchedEffect(
        controller,
        previewView,
        state.settings.lensFacing,
        state.settings.frameAspect,
        state.settings.videoResolution,
        state.settings.videoFps,
        state.settings.ultraWide,
    ) {
        val c = controller ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        try {
            val snap = c.bind(
                lifecycleOwner,
                view,
                state.settings.lensFacing,
                filter = if (state.liveFilterOn) state.filter else LiveFilter.NONE,
                intensity = state.settings.filterIntensity,
                grade = state.settings.grade,
                frameAspect = state.settings.frameAspect,
                videoResolution = state.settings.videoResolution,
                videoFps = state.settings.videoFps,
                ultraWidePhysical = state.settings.ultraWide,
                clarity = state.settings.clarityMode,
                videoStabilization = state.settings.videoStabilization,
            )
            caps = snap
            vm.clampZoom(snap.zoomMin..snap.zoomMax)
            bound = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("SpectraDesk", "camera bind failed", e)
            vm.message("相机启动失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // 滤镜变化只写渲染器字段，不重建管线 —— 切换即时，录像中途切换也不中断。
    LaunchedEffect(state.filter, state.settings.filterIntensity, state.liveFilterOn) {
        val kind = if (state.liveFilterOn) state.filter else LiveFilter.NONE
        controller?.setLiveFilter(kind, state.settings.filterIntensity)
    }

    // 变焦
    LaunchedEffect(state.settings.zoomRatio, caps) {
        controller?.setZoomRatio(state.settings.zoomRatio)
    }

    // 手动对焦：补齐之前缺失的下发链路；重绑后也会按当前状态重新下发。
    LaunchedEffect(state.settings.manualFocus, caps) {
        if (caps != null) {
            controller?.setManualFocus(state.settings.manualFocus)
        }
    }

    // 调色：只写渲染器字段，拖滑杆实时生效，不重建管线。
    LaunchedEffect(state.settings.grade) {
        controller?.setColorGrade(state.settings.grade)
    }

    // 算法高清/超清增强：直接更新 GL 着色器 u_clarity，实时生效不中断录制。
    LaunchedEffect(state.settings.clarityMode, caps) {
        controller?.setClarity(state.settings.clarityMode)
    }

    // 硬件防抖（EIS/OIS）+ 3DNR 协同：通过 Camera2Interop 实时下发
    LaunchedEffect(state.settings.videoStabilization, caps) {
        if (caps != null) {
            controller?.setVideoStabilization(state.settings.videoStabilization)
        }
    }

    // 闪光灯 / 补光灯（后置走硬件 LED/Torch，前置走 FrontDisplayFlash 屏幕峰值亮度 + 柔光环）
    val context = androidx.compose.ui.platform.LocalContext.current

    // 重力感应横竖屏追踪器 + 电子水平仪（对标系统相机 MultiSensorOrientationEventListener + 抖音重力感应）
    DisposableEffect(context) {
        val tracker = OrientationTracker(context)
        tracker.start(
            onChange = { o -> vm.setOrientation(o) },
            onGravity = { g -> vm.updateGravity(g) },
        )
        onDispose { tracker.stop() }
    }

    // 重力感应横竖屏变化时，实时更新 ImageCapture 与 VideoCapture 的 targetRotation，
    // 保证横握手机拍出的照片 EXIF 和录制的 MP4 视频都是正方向的横屏画幅，且不中断取景与录像！
    LaunchedEffect(state.orientation, bound) {
        if (bound) {
            controller?.updateTargetRotation(state.orientation.surfaceRotation)
        }
    }

    // UI 控件平滑重力旋转角（0° 竖屏 / +90° 左横屏 / -90° 右横屏 / 180° 倒置）
    val uiRotation by animateFloatAsState(
        targetValue = state.orientation.uiRotationDegrees,
        animationSpec = tween(durationMillis = 260),
        label = "uiOrientationRotation",
    )
    LaunchedEffect(
        state.settings.flashMode,
        state.settings.lensFacing,
        state.recording,
        state.screenFlashPulse,
        caps,
    ) {
        controller?.applyFlashMode(state.settings.flashMode)
        val isFront = state.settings.lensFacing ==
            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        val hasHwFlash = caps?.hasFlashUnit == true && !isFront
        val needScreenMaxBrightness = !hasHwFlash && (
            state.screenFlashPulse ||
                state.settings.flashMode == FlashMode.TORCH ||
                (state.recording && state.settings.flashMode != FlashMode.OFF) ||
                state.settings.flashMode == FlashMode.ON
            )
        (context as? android.app.Activity)?.window?.let { win ->
            val lp = win.attributes
            val targetBrightness = if (needScreenMaxBrightness) {
                1.0f
            } else {
                android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (lp.screenBrightness != targetBrightness) {
                lp.screenBrightness = targetBrightness
                win.attributes = lp
            }
        }
    }

    // 曝光补偿
    LaunchedEffect(state.settings.exposureIndex, caps) {
        if (caps != null) {
            controller?.setExposureCompensation(state.settings.exposureIndex)
        }
    }

    // 对焦框自动淡出：普通点按对焦完成后留 2.2 秒淡出，长按锁定则常驻。
    LaunchedEffect(state.focusMark?.id, state.focusMark?.locked) {
        val mark = state.focusMark ?: return@LaunchedEffect
        if (mark.locked) return@LaunchedEffect
        kotlinx.coroutines.delay(2500)
        vm.clearFocusMark(mark.id)
    }

    // 录像计时
    LaunchedEffect(state.recording) {
        if (!state.recording) return@LaunchedEffect
        var secs = 0
        while (state.recording) {
            kotlinx.coroutines.delay(1000)
            secs++
            vm.setRecordingSeconds(secs)
        }
    }

    /** 快门：拍照存图库（前置或无物理闪光灯镜头在开启补光时先触发屏幕峰值补光脉冲）。 */
    fun takePhoto() {
        val c = controller ?: return
        scope.launch {
            vm.setBusy(true)
            val isFront = state.settings.lensFacing ==
                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            val needScreenPulse = (isFront || caps?.hasFlashUnit == false) &&
                (state.settings.flashMode == FlashMode.ON || state.settings.flashMode == FlashMode.AUTO)
            try {
                if (needScreenPulse) {
                    vm.setScreenFlashPulse(true)
                    kotlinx.coroutines.delay(240)
                }
                val ok = c.captureToGallery(state.settings.watermark)
                vm.message(if (ok) "已保存到相册 · Pictures/LightYear" else "保存失败")
            } catch (e: Exception) {
                vm.message("拍摄失败：${e.message}")
            } finally {
                if (needScreenPulse) {
                    vm.setScreenFlashPulse(false)
                }
                vm.setBusy(false)
            }
        }
    }

    /** 录像开关。 */
    fun toggleRecord() {
        val c = controller ?: return
        if (state.recording) {
            c.stopRecording()
            vm.setRecording(false)
        } else {
            val started = c.startRecording(state.settings.audioEnabled) { event ->
                when (event) {
                    is androidx.camera.video.VideoRecordEvent.Finalize -> {
                        vm.setRecording(false)
                        val err = event.error
                        if (err != androidx.camera.video.VideoRecordEvent.Finalize.ERROR_NONE) {
                            vm.message("录像结束（错误 $err）")
                        } else {
                            vm.message("视频已保存到相册 · Movies/LightYear")
                        }
                    }
                    is androidx.camera.video.VideoRecordEvent.Start -> {
                        vm.message("开始录像")
                    }
                    else -> Unit
                }
            }
            vm.setRecording(started)
            if (!started) vm.message("录像启动失败")
        }
    }

    val colors = MaterialTheme.colorScheme
    val zoomMin = caps?.zoomMin ?: 1f
    val zoomMax = caps?.zoomMax ?: 1f
    val currentZoom by rememberUpdatedState(state.settings.zoomRatio)
    val currentZoomRange by rememberUpdatedState(zoomMin..zoomMax)

    /** 触发一次取景器对焦（点按 = 自动对焦，长按 = AE/AF 锁定）。 */
    fun triggerFocus(w: Int, h: Int, nx: Float, ny: Float, lock: Boolean) {
        if (w <= 0 || h <= 0) return
        val cx = nx.coerceIn(0.05f, 0.95f)
        val cy = ny.coerceIn(0.05f, 0.95f)
        val id = vm.beginFocus(cx, cy, lock)
        scope.launch {
            val ok = controller?.focusAtPoint(w, h, cx, cy, lock = lock) ?: false
            vm.finishFocus(id, ok)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Header(state, caps)

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(PanelShape)
                .border(1.dp, colors.outlineVariant, PanelShape),
            contentAlignment = Alignment.Center,
        ) {
            // 取景器画框：AndroidView + 对焦框与手势都挂在同一个等比例 Box 里，
            // 保证点击坐标 (nx, ny) 和对焦框的像素偏移 1:1 严格对齐。
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(state.settings.frameAspect.portraitRatio)
                    .background(colors.scrim)
                    .onSizeChanged { viewfinderSize = it }
                    // 手势 1：单击点按对焦 / 长按 AE+AF 锁定
                    .pointerInput(state.settings.lensFacing) {
                        detectTapGestures(
                            onTap = { offset ->
                                val w = size.width
                                val h = size.height
                                if (w > 0 && h > 0) {
                                    triggerFocus(w, h, offset.x / w, offset.y / h, lock = false)
                                }
                            },
                            onLongPress = { offset ->
                                val w = size.width
                                val h = size.height
                                if (w > 0 && h > 0) {
                                    triggerFocus(w, h, offset.x / w, offset.y / h, lock = true)
                                }
                            },
                        )
                    }
                    // 手势 2：双指捏合无级变焦（只认 >=2 指，不抢单指点击）
                    .pointerInput(state.settings.lensFacing) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val z = event.calculateZoom()
                                    if (kotlin.math.abs(z - 1f) > 0.004f) {
                                        val range = currentZoomRange
                                        if (range.endInclusive > range.start + 0.01f) {
                                            vm.setZoom(currentZoom * z, range)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewSurfaceView(ctx).also {
                            previewView = it
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // 对焦框浮层 + 旁侧 EV 快捷滑块
                val mark = state.focusMark
                if (mark != null && viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                    FocusReticleOverlay(
                        mark = mark,
                        containerSize = viewfinderSize,
                        exposureIndex = state.settings.exposureIndex,
                        exposureRange = caps?.exposureRange ?: 0..0,
                        evStep = caps?.exposureStepEv ?: (1.0 / 3.0),
                        uiRotation = uiRotation,
                        onExposureChange = { idx ->
                            vm.setExposureIndex(idx, caps?.exposureStepEv ?: (1.0 / 3.0))
                        },
                    )
                }
            }

            // 重力感应中心电子水平仪（对标系统相机 com.motorola.camera.ui.widgets.LevelerView）
            if (state.settings.levelerEnabled && !state.paramsOpen) {
                GravityLevelerOverlay(
                    gravity = state.gravity,
                    uiRotation = uiRotation,
                )
            }

            // 水印预览。和存盘用的是同一份 WatermarkRenderer 几何参数。
            if (state.settings.watermark.enabled) {
                val wm = state.settings.watermark
                val lines = WatermarkContent.now(wm, "光年相机").lines(wm)
                if (lines.isNotEmpty()) {
                    val corner = wm.corner
                    Column(
                        Modifier
                            .align(
                                when (corner) {
                                    Watermark.Corner.TOP_LEFT -> Alignment.TopStart
                                    Watermark.Corner.TOP_RIGHT -> Alignment.TopEnd
                                    Watermark.Corner.BOTTOM_LEFT -> Alignment.BottomStart
                                    Watermark.Corner.BOTTOM_RIGHT -> Alignment.BottomEnd
                                },
                            )
                            .padding(14.dp)
                            .graphicsLayer { rotationZ = uiRotation }
                            .then(
                                if (wm.style == Watermark.Style.CARD) {
                                    Modifier
                                        .background(
                                            Color.Black.copy(alpha = 0.55f),
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                } else {
                                    Modifier
                                },
                            ),
                        horizontalAlignment =
                            if (corner.isRight) Alignment.End else Alignment.Start,
                    ) {
                        lines.forEach { line ->
                            Text(line, color = Color.White, style = MonoSmall)
                        }
                    }
                }
            }

            // 测量准星：只有开参数面板才显示，否则普通拍照/录像时它是干扰。
            if (state.paramsOpen) {
                ReticleOverlay(fraction = state.settings.patchFraction)
            }

            // 前置屏幕柔光环 / 快门屏幕补光浮层（对标系统相机 FrontDisplayFlash + 抖音柔光环）
            val isFrontLens = state.settings.lensFacing ==
                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            val useScreenFlash = (isFrontLens || caps?.hasFlashUnit == false) &&
                state.settings.flashMode != FlashMode.OFF
            if (useScreenFlash || state.screenFlashPulse) {
                val toneColor = Color(state.settings.screenFlashTone.argb)
                if (state.screenFlashPulse) {
                    // 快门瞬间全屏柔光脉冲（留出中心半透明观察窗）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(toneColor.copy(alpha = 0.88f)),
                    )
                } else {
                    // 常驻环形柔光边框（照亮面部的同时不挡中心取景）
                    val borderDp = if (state.settings.flashMode == FlashMode.TORCH || state.recording) {
                        30.dp
                    } else {
                        18.dp
                    }
                    val ringAlpha = if (state.settings.flashMode == FlashMode.TORCH || state.recording) {
                        0.92f
                    } else {
                        0.72f
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(borderDp, toneColor.copy(alpha = ringAlpha), PanelShape),
                    )
                }
            }

            // 左上角：闪光灯 / 屏幕补光快捷胶囊 + 重力感应朝向/水平仪胶囊 + 录像计时
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val fMode = state.settings.flashMode
                    val flashActive = fMode != FlashMode.OFF
                    Box(
                        Modifier
                            .clip(ChipShape)
                            .background(
                                when (fMode) {
                                    FlashMode.TORCH -> colors.primary.copy(alpha = 0.94f)
                                    FlashMode.ON -> colors.secondary.copy(alpha = 0.92f)
                                    FlashMode.AUTO -> colors.tertiary.copy(alpha = 0.90f)
                                    FlashMode.OFF -> colors.scrim.copy(alpha = 0.68f)
                                },
                            )
                            .clickable { vm.cycleFlashMode() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (isFrontLens) {
                                when (fMode) {
                                    FlashMode.OFF -> "💡 补光关"
                                    FlashMode.AUTO -> "💡 自动柔光"
                                    FlashMode.ON -> "💡 屏幕闪光"
                                    FlashMode.TORCH -> "💡 柔光环常亮"
                                }
                            } else {
                                fMode.shortLabel
                            },
                            color = when (fMode) {
                                FlashMode.TORCH -> colors.onPrimary
                                FlashMode.ON -> colors.onSecondary
                                FlashMode.AUTO -> colors.onTertiary
                                FlashMode.OFF -> Color.White
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (flashActive) FontWeight.Bold else FontWeight.Normal,
                        )
                    }

                    // 前置开启补光时，旁边直接弹出抖音同款三色温切换圆点（暖阳 / 冷白 / 蜜桃）
                    if (useScreenFlash) {
                        Row(
                            Modifier
                                .background(colors.scrim.copy(alpha = 0.72f), ChipShape)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ScreenFlashTone.entries.forEach { tone ->
                                val selected = state.settings.screenFlashTone == tone
                                Box(
                                    Modifier
                                        .size(if (selected) 18.dp else 14.dp)
                                        .clip(CircleShape)
                                        .background(Color(tone.argb))
                                        .border(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) colors.primary else Color.Black.copy(alpha = 0.4f),
                                            CircleShape,
                                        )
                                        .clickable { vm.setScreenFlashTone(tone) },
                                )
                            }
                        }
                    }
                }

                // 重力感应横竖屏姿态 + 电子水平仪快捷胶囊（点击可开关中心电子水平仪）
                val g = state.gravity
                val levelColor = when {
                    g.isFlat -> colors.secondary
                    g.isLeveled -> colors.tertiary
                    state.orientation.isLandscape -> colors.primary
                    else -> Color.White
                }
                Box(
                    Modifier
                        .clip(ChipShape)
                        .background(colors.scrim.copy(alpha = 0.68f))
                        .border(
                            1.dp,
                            if (g.isLeveled && state.settings.levelerEnabled) {
                                colors.tertiary.copy(alpha = 0.85f)
                            } else {
                                colors.outlineVariant.copy(alpha = 0.6f)
                            },
                            ChipShape,
                        )
                        .clickable { vm.setLevelerEnabled(!state.settings.levelerEnabled) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        buildString {
                            append("🧭 ")
                            if (g.isFlat) {
                                append("${state.orientation.label} · 平放俯拍")
                            } else {
                                append(state.orientation.label)
                                append(" %+.1f°".format(g.tiltDegrees))
                                if (g.isLeveled) append(" · 水平")
                            }
                        },
                        color = levelColor,
                        style = MonoSmall,
                        fontWeight = if (g.isLeveled || state.orientation.isLandscape) FontWeight.Bold else FontWeight.Normal,
                    )
                }

                if (state.recording) {
                    Row(
                        Modifier
                            .background(colors.scrim.copy(alpha = 0.7f), ChipShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).background(colors.error, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "%02d:%02d · %s %dfps".format(
                                state.recordingSeconds / 60,
                                state.recordingSeconds % 60,
                                state.settings.videoResolution.label,
                                caps?.videoAppliedFps ?: state.settings.videoFps,
                            ),
                            color = colors.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = Mono,
                        )
                    }
                }
            }

            // 右上角状态角标（超清算法 / 滤镜 / 手动对焦 MF）
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .clip(ChipShape)
                        .background(
                            when (state.settings.clarityMode) {
                                ClarityMode.HDR_ENHANCE -> colors.secondary.copy(alpha = 0.92f)
                                ClarityMode.ULTRA -> colors.tertiary.copy(alpha = 0.92f)
                                ClarityMode.HD -> colors.primary.copy(alpha = 0.88f)
                                ClarityMode.OFF -> colors.scrim.copy(alpha = 0.65f)
                            },
                        )
                        .clickable {
                            val next = when (state.settings.clarityMode) {
                                ClarityMode.OFF -> ClarityMode.HD
                                ClarityMode.HD -> ClarityMode.ULTRA
                                ClarityMode.ULTRA -> ClarityMode.HDR_ENHANCE
                                ClarityMode.HDR_ENHANCE -> ClarityMode.OFF
                            }
                            vm.setClarityMode(next)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        when (state.settings.clarityMode) {
                            ClarityMode.HDR_ENHANCE -> "算法 · 超清HDR"
                            ClarityMode.ULTRA -> "算法 · 超清重构"
                            ClarityMode.HD -> "算法 · 高清Hydra"
                            ClarityMode.OFF -> "画质 · 标准"
                        },
                        color = when (state.settings.clarityMode) {
                            ClarityMode.HDR_ENHANCE -> colors.onSecondary
                            ClarityMode.ULTRA -> colors.onTertiary
                            ClarityMode.HD -> colors.onPrimary
                            ClarityMode.OFF -> Color.White
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (state.liveFilterOn && state.filter != LiveFilter.NONE) {
                    Box(
                        Modifier
                            .background(colors.primary.copy(alpha = 0.9f), ChipShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "实时 · ${state.filter.label}",
                            color = colors.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (state.settings.manualFocus != null) {
                    Box(
                        Modifier
                            .background(colors.secondary.copy(alpha = 0.9f), ChipShape)
                            .clickable { vm.setManualFocus(null) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "MF ${"%.1f".format(state.settings.manualFocus)}D · 点此回AF",
                            color = colors.onSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // 预览框底部控制栈：变焦/超广角快捷胶囊 + 精细变焦滑杆 + 滤镜条
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ViewfinderLensControls(
                    zoomRatio = state.settings.zoomRatio,
                    zoomMin = zoomMin,
                    zoomMax = zoomMax,
                    caps = caps,
                    ultraWideOn = state.settings.ultraWide,
                    manualFocus = state.settings.manualFocus,
                    scrubberOpen = zoomScrubberOpen,
                    uiRotation = uiRotation,
                    onToggleScrubber = { zoomScrubberOpen = !zoomScrubberOpen },
                    onZoom = { z -> vm.setZoom(z, zoomMin..zoomMax) },
                    onUltraWide = { on -> vm.setUltraWide(on) },
                    onManualFocus = { mf -> vm.setManualFocus(mf) },
                )

                FilterBar(
                    selected = state.filter,
                    liveOn = state.liveFilterOn,
                    onSelect = { vm.setFilter(it) },
                    onToggleLive = { vm.setLiveFilterOn(!state.liveFilterOn) },
                )
            }

            // 参数面板做成取景器内的分类浮层
            if (state.paramsOpen) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 44.dp)
                        .background(colors.surface.copy(alpha = 0.96f), RoundedCornerShape(10.dp))
                        .heightIn(max = 460.dp),
                ) {
                    ParamsPanel(
                        state = state,
                        caps = caps,
                        onSelectTab = { vm.setParamsTab(it) },
                        onClose = { vm.setParamsOpen(false) },
                        onZoom = { vm.setZoom(it, zoomMin..zoomMax) },
                        onExposure = { vm.setExposureIndex(it, caps?.exposureStepEv ?: (1.0 / 3.0)) },
                        onIntensity = { vm.setFilterIntensity(it) },
                        onAudio = { vm.setAudioEnabled(it) },
                        onGamma = vm::setGammaCorrected,
                        onUltraWide = { vm.setUltraWide(it) },
                        onManualFocus = { vm.setManualFocus(it) },
                        onWatermarkEnabled = { vm.setWatermarkEnabled(it) },
                        onWatermark = { block -> vm.updateWatermark(block) },
                        onClampExposure = { vm.clampExposureIndex(it) },
                        onGradeChannel = { ch, v -> vm.setGradeChannel(ch, v) },
                        onGradePreset = { vm.applyGradePreset(it) },
                        onGradeReset = { vm.resetGrade() },
                        onFrameAspect = { vm.setFrameAspect(it) },
                        onVideoResolution = { vm.setVideoResolution(it) },
                        onVideoFps = { vm.setVideoFps(it) },
                        onClarityMode = { vm.setClarityMode(it) },
                        onVideoStabilization = { vm.setVideoStabilization(it) },
                        onFlashMode = { vm.setFlashMode(it) },
                        onScreenFlashTone = { vm.setScreenFlashTone(it) },
                        onLevelerEnabled = { vm.setLevelerEnabled(it) },
                    )
                }
            }

            if (state.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
            }

            state.message?.let { msg ->
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp)
                        .background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(msg, color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 主控制条：参数开关 | 前后置 | 快门 | 录像（跟随重力感应平滑旋转图标与文字）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 参数面板开关
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(if (state.paramsOpen) colors.primary else colors.surfaceVariant)
                    .clickable { vm.setParamsOpen(!state.paramsOpen) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "参数",
                    color = if (state.paramsOpen) colors.onPrimary else colors.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 前后置切换
            val isFront = state.settings.lensFacing ==
                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(if (isFront) colors.secondary else colors.surfaceVariant)
                    .border(1.dp, if (isFront) colors.secondary else colors.outline, CircleShape)
                    .clickable { vm.toggleLens() }
                    .semantics {
                        contentDescription = if (isFront) "切到后置" else "切到前置"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isFront) "前置" else "后置",
                    color = if (isFront) colors.onSecondary else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 快门
            ShutterButton(
                busy = state.busy,
                recording = state.recording,
                onClick = { if (state.recording) toggleRecord() else takePhoto() },
            )

            // 录像
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(if (state.recording) colors.error else colors.surfaceVariant)
                    .border(
                        2.dp,
                        if (state.recording) colors.error else colors.outline,
                        CircleShape,
                    )
                    .clickable { toggleRecord() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (state.recording) 18.dp else 24.dp)
                        .clip(if (state.recording) RoundedCornerShape(3.dp) else CircleShape)
                        .background(if (state.recording) colors.onError else colors.error),
                )
            }
        }

        Text(
            when {
                state.recording -> "录像中 · 点快门停止"
                state.busy -> "保存中…"
                else -> "点按对焦 · 长按锁定 · 双指变焦 · 快门拍照"
            },
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

enum class ShutterMode { MEASURE, WHITE, DARK }

/**
 * 取景器上的点按对焦框 + 旁侧快捷曝光补偿调节。
 *
 * - 白色：正在对焦
 * - 琥珀黄 / 绿色：对焦成功（长按锁定时常亮琥珀色并标 LOCK）
 * - 红色：对焦未命中
 */
@Composable
private fun FocusReticleOverlay(
    mark: FocusMark,
    containerSize: IntSize,
    exposureIndex: Int,
    exposureRange: IntRange,
    evStep: Double,
    uiRotation: Float = 0f,
    onExposureChange: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val boxDp = 64.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val boxPx = with(density) { boxDp.roundToPx() }
    val cx = (mark.x * containerSize.width).toInt()
    val cy = (mark.y * containerSize.height).toInt()
    val left = (cx - boxPx / 2).coerceIn(4, (containerSize.width - boxPx - 4).coerceAtLeast(4))
    val top = (cy - boxPx / 2).coerceIn(4, (containerSize.height - boxPx - 4).coerceAtLeast(4))

    val strokeColor = when {
        mark.locked -> colors.primary
        mark.success == null -> Color.White
        mark.success -> colors.tertiary
        else -> colors.error
    }

    Box(
        Modifier
            .offset { IntOffset(left, top) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(boxDp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.dp.toPx()
                    val s = size.width
                    val tick = s * 0.18f
                    // 外框四角 + 四边短刻度线
                    drawRoundRect(
                        color = strokeColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    )
                    drawLine(strokeColor, Offset(s / 2f, 0f), Offset(s / 2f, tick), w)
                    drawLine(strokeColor, Offset(s / 2f, s - tick), Offset(s / 2f, s), w)
                    drawLine(strokeColor, Offset(0f, s / 2f), Offset(tick, s / 2f), w)
                    drawLine(strokeColor, Offset(s - tick, s / 2f), Offset(s, s / 2f), w)
                }
                if (mark.locked) {
                    Text(
                        "AE/AF 锁",
                        color = colors.onPrimary,
                        style = MonoSmall,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-18).dp)
                            .graphicsLayer { rotationZ = uiRotation }
                            .background(colors.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }

            // 对焦框旁边的快捷曝光加减按钮（在取景器里点完对焦就能顺手压暗/提亮）
            if (exposureRange.last > exposureRange.first && mark.x < 0.78f) {
                Spacer(Modifier.width(6.dp))
                Column(
                    Modifier
                        .background(colors.scrim.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                        .border(1.dp, strokeColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val stepJump = (1.0 / (evStep * 3.0)).toInt().coerceAtLeast(1)
                    Box(
                        Modifier
                            .graphicsLayer { rotationZ = uiRotation }
                            .clip(CircleShape)
                            .clickable {
                                onExposureChange((exposureIndex + stepJump).coerceAtMost(exposureRange.last))
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("☀+", color = colors.primary, style = MonoSmall)
                    }
                    Text(
                        "%+.1f".format(exposureIndex * evStep),
                        color = Color.White,
                        style = MonoSmall,
                        modifier = Modifier.graphicsLayer { rotationZ = uiRotation },
                    )
                    Box(
                        Modifier
                            .graphicsLayer { rotationZ = uiRotation }
                            .clip(CircleShape)
                            .clickable {
                                onExposureChange((exposureIndex - stepJump).coerceAtLeast(exposureRange.first))
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("☀-", color = colors.primary, style = MonoSmall)
                    }
                }
            }
        }
    }
}

/**
 * 重力感应中心电子水平仪（复刻系统相机 `com.motorola.camera.ui.widgets.LevelerView`）：
 *
 * - 外侧两条固定水平基准短线（`mMainLine`）随当前竖屏/横屏基准轴（`uiRotation`）对齐；
 * - 中间浮动重力水平线（`mLevelerLine`）按实时倾角偏差 `-gravity.tiltDegrees` 动态偏转；
 * - 当倾角误差 `<= 1.5°`（`gravity.isLeveled`）时，两线合拢并高亮为仪表合格绿（`colors.tertiary`）；
 * - 当手机平放俯拍（`gravity.isFlat`）时，自动切换为天顶十字准星气泡仪。
 */
@Composable
private fun GravityLevelerOverlay(
    gravity: OrientationTracker.GravityState,
    uiRotation: Float,
) {
    val colors = MaterialTheme.colorScheme
    val lineColor = when {
        gravity.isFlat -> colors.secondary.copy(alpha = 0.78f)
        gravity.isLeveled -> colors.tertiary.copy(alpha = 0.92f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    val refColor = if (gravity.isLeveled && !gravity.isFlat) {
        colors.tertiary.copy(alpha = 0.92f)
    } else {
        Color.White.copy(alpha = 0.35f)
    }

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = if (gravity.isLeveled) 2.2.dp.toPx() else 1.5.dp.toPx()

        if (gravity.isFlat) {
            // 平放俯拍模式：天顶同心圆 + 气泡偏移十字
            val r = 24.dp.toPx()
            drawCircle(
                color = Color.White.copy(alpha = 0.32f),
                radius = r,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx()),
            )
            val offsetPx = (gravity.tiltDegrees / 20f).coerceIn(-1f, 1f) * (r * 0.65f)
            drawCircle(
                color = lineColor,
                radius = 5.dp.toPx(),
                center = Offset(cx + offsetPx, cy),
            )
        } else {
            val outerHalf = 58.dp.toPx()
            val innerHalf = 36.dp.toPx()
            // 1. 外侧左右两条基准水平刻度（跟随当前横/竖屏握持方向 uiRotation）
            rotate(degrees = uiRotation, pivot = Offset(cx, cy)) {
                drawLine(
                    color = refColor,
                    start = Offset(cx - outerHalf, cy),
                    end = Offset(cx - innerHalf - 4.dp.toPx(), cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = refColor,
                    start = Offset(cx + innerHalf + 4.dp.toPx(), cy),
                    end = Offset(cx + outerHalf, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            // 2. 中心重力感应浮动水平线（补偿实时倾角 -tiltDegrees）
            rotate(degrees = uiRotation - gravity.tiltDegrees, pivot = Offset(cx, cy)) {
                drawLine(
                    color = lineColor,
                    start = Offset(cx - innerHalf, cy),
                    end = Offset(cx + innerHalf, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = lineColor,
                    radius = if (gravity.isLeveled) 3.5.dp.toPx() else 2.5.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

/**
 * 取景器底部的镜头 / 超广角 / 变焦 / 对焦快捷控制条。
 *
 * 用户无需打开参数面板，在预览框内就能：
 * 1. 一键切超广角（0.5× / 0.6× 或 UW 物理镜头）与常用焦段（1× / 2× / 3× / 5× / 10×）
 * 2. 点当前倍率胶囊展开无级变焦 + 手动对焦滑杆
 * 3. 双指捏合时实时看到当前倍率变化
 */
@Composable
private fun ViewfinderLensControls(
    zoomRatio: Float,
    zoomMin: Float,
    zoomMax: Float,
    caps: CameraController.CapabilitySnapshot?,
    ultraWideOn: Boolean,
    manualFocus: Float?,
    scrubberOpen: Boolean,
    uiRotation: Float = 0f,
    onToggleScrubber: () -> Unit,
    onZoom: (Float) -> Unit,
    onUltraWide: (Boolean) -> Unit,
    onManualFocus: (Float?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val hasZoomRange = zoomMax > zoomMin + 0.01f
    val maxDiopters = caps?.minFocusDiopters ?: 0f

    // 展开的精细调节浮条（变焦滑杆 + 对焦滑杆）
    if (scrubberOpen && (hasZoomRange || maxDiopters > 0.01f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.scrim.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (hasZoomRange) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "变焦",
                        color = colors.onSurfaceVariant,
                        style = MonoSmall,
                        modifier = Modifier.width(34.dp),
                    )
                    Slider(
                        value = zoomRatio.coerceIn(zoomMin, zoomMax),
                        onValueChange = onZoom,
                        valueRange = zoomMin..zoomMax,
                        colors = sliderColors(colors),
                        modifier = Modifier.weight(1f).height(24.dp),
                    )
                    Text(
                        "%.1f×".format(zoomRatio),
                        color = colors.primary,
                        style = MonoSmall,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (maxDiopters > 0.01f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "对焦",
                        color = colors.onSurfaceVariant,
                        style = MonoSmall,
                        modifier = Modifier.width(34.dp),
                    )
                    Slider(
                        value = manualFocus ?: 0f,
                        onValueChange = { onManualFocus(if (it < 0.01f) null else it) },
                        valueRange = 0f..maxDiopters,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.secondary,
                            activeTrackColor = colors.secondary,
                            inactiveTrackColor = colors.outlineVariant,
                        ),
                        modifier = Modifier.weight(1f).height(24.dp),
                    )
                    Box(
                        Modifier
                            .clip(ChipShape)
                            .background(if (manualFocus == null) colors.secondary else colors.surfaceVariant)
                            .clickable { onManualFocus(null) }
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (manualFocus == null) "AF" else "%.1fD".format(manualFocus),
                            color = if (manualFocus == null) colors.onSecondary else colors.onSurfaceVariant,
                            style = MonoSmall,
                        )
                    }
                }
            }
        }
    }

    // 快捷焦段胶囊环：超广角 + 1× + 2× + 5× + 滑杆开关（圆钮设计，文字随重力感应 0°/90°/-90°/180° 原地平滑旋转不突兀）
    Row(
        Modifier
            .background(colors.scrim.copy(alpha = OVERLAY_ALPHA), ChipShape)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), ChipShape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 如果设备通过物理子相机提供超广角（而 zoomMin >= 1.0），单独给一个 UW 圆钮
        if (caps?.ultraWideMode == CameraController.UltraWideMode.PHYSICAL) {
            Box(
                Modifier
                    .size(36.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(if (ultraWideOn) colors.secondary else Color.Transparent)
                    .clickable { onUltraWide(!ultraWideOn) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "广角",
                    color = if (ultraWideOn) colors.onSecondary else colors.onSurfaceVariant,
                    style = MonoSmall,
                    fontWeight = if (ultraWideOn) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }

        // 组装当前设备支持的变焦档位：若 zoomMin < 1（如 0.5 或 0.6），第一个档位就是超广角！
        val presets = buildList {
            if (zoomMin < 0.95f) add(zoomMin)
            add(1f)
            if (zoomMax >= 2f) add(2f)
            if (zoomMax >= 5f) add(5f)
            if (zoomMax >= 10f) add(10f)
        }.distinct()

        presets.forEach { step ->
            val active = kotlin.math.abs(zoomRatio - step) < 0.08f && !ultraWideOn
            val label = when {
                step < 1f -> "%.1f×".format(step)
                active -> "%.1f×".format(zoomRatio)
                else -> "${step.toInt()}×"
            }
            Box(
                Modifier
                    .size(36.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(if (active) colors.primary else colors.surfaceVariant.copy(alpha = 0.55f))
                    .clickable {
                        if (ultraWideOn) onUltraWide(false)
                        onZoom(step)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) colors.onPrimary else Color.White,
                    style = MonoSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }

        // 精细滑杆 / 对焦开关圆钮
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { rotationZ = uiRotation }
                .clip(CircleShape)
                .background(if (scrubberOpen || manualFocus != null) colors.secondary else colors.surfaceVariant)
                .clickable { onToggleScrubber() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (manualFocus != null) "MF" else "微调",
                color = if (scrubberOpen || manualFocus != null) colors.onSecondary else colors.onSurfaceVariant,
                style = MonoSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 分类参数面板：把原本混在一起的十几项参数按职能拆成 5 个 Tab：
 * - **镜头**：画幅比例、超广角、变焦、手动/自动对焦
 * - **曝光**：曝光补偿 EV、GC 伽马标定保护
 * - **录像**：分辨率档位（4K / 1080p / 720p）、帧率档位（24 / 30 / 60 / 120fps）、录音开关
 * - **画面**：实时滤镜强度、10 通道手动调色与预设
 * - **水印**：时间戳/地点/自定义文字与四角版式
 */
@Composable
private fun ParamsPanel(
    state: UiState,
    caps: CameraController.CapabilitySnapshot?,
    onSelectTab: (ParamsTab) -> Unit,
    onClose: () -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Int) -> Unit,
    onIntensity: (Float) -> Unit,
    onAudio: (Boolean) -> Unit,
    onGamma: (Boolean) -> Unit,
    onClampExposure: (IntRange) -> Unit,
    onGradeChannel: (ColorGrade.Channel, Float) -> Unit,
    onGradePreset: (String) -> Unit,
    onGradeReset: () -> Unit,
    onFrameAspect: (FrameAspect) -> Unit,
    onUltraWide: (Boolean) -> Unit,
    onManualFocus: (Float?) -> Unit,
    onWatermarkEnabled: (Boolean) -> Unit,
    onWatermark: ((Watermark) -> Watermark) -> Unit,
    onVideoResolution: (VideoResolution) -> Unit,
    onVideoFps: (Int) -> Unit,
    onClarityMode: (ClarityMode) -> Unit,
    onVideoStabilization: (Boolean) -> Unit,
    onFlashMode: (FlashMode) -> Unit,
    onScreenFlashTone: (ScreenFlashTone) -> Unit,
    onLevelerEnabled: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val zoomMin = caps?.zoomMin ?: 1f
    val zoomMax = caps?.zoomMax ?: 1f
    val exposureRange = caps?.exposureRange ?: 0..0
    val evStep = caps?.exposureStepEv ?: (1.0 / 3.0)

    LaunchedEffect(exposureRange) {
        onClampExposure(exposureRange)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(PanelShape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, PanelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // 顶部全分类导航条 + 收起按钮
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ParamsTab.entries.forEach { tab ->
                val active = state.paramsTab == tab
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.primary else colors.surfaceVariant)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Box(
                Modifier
                    .clip(ChipShape)
                    .background(colors.surfaceVariant)
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("收起", color = colors.onSurfaceVariant, style = MonoSmall)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)

        // 内容区可滚动，且切换 Tab 时高度紧凑不乱跳
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (state.paramsTab) {
                ParamsTab.LENS -> LensTabContent(
                    state = state,
                    caps = caps,
                    zoomMin = zoomMin,
                    zoomMax = zoomMax,
                    onFrameAspect = onFrameAspect,
                    onZoom = onZoom,
                    onUltraWide = onUltraWide,
                    onManualFocus = onManualFocus,
                    onFlashMode = onFlashMode,
                    onScreenFlashTone = onScreenFlashTone,
                    onLevelerEnabled = onLevelerEnabled,
                )
                ParamsTab.EXPOSURE -> ExposureTabContent(
                    state = state,
                    exposureRange = exposureRange,
                    evStep = evStep,
                    onExposure = onExposure,
                    onGamma = onGamma,
                )
                ParamsTab.VIDEO -> VideoTabContent(
                    state = state,
                    caps = caps,
                    onVideoResolution = onVideoResolution,
                    onVideoFps = onVideoFps,
                    onClarityMode = onClarityMode,
                    onVideoStabilization = onVideoStabilization,
                    onAudio = onAudio,
                )
                ParamsTab.LOOK -> LookTabContent(
                    state = state,
                    onClarityMode = onClarityMode,
                    onIntensity = onIntensity,
                    onGradeChannel = onGradeChannel,
                    onGradePreset = onGradePreset,
                    onGradeReset = onGradeReset,
                )
                ParamsTab.WATERMARK -> WatermarkTabContent(
                    state = state,
                    onWatermarkEnabled = onWatermarkEnabled,
                    onWatermark = onWatermark,
                )
            }
        }
    }
}

@Composable
private fun LensTabContent(
    state: UiState,
    caps: CameraController.CapabilitySnapshot?,
    zoomMin: Float,
    zoomMax: Float,
    onFrameAspect: (FrameAspect) -> Unit,
    onZoom: (Float) -> Unit,
    onUltraWide: (Boolean) -> Unit,
    onManualFocus: (Float?) -> Unit,
    onFlashMode: (FlashMode) -> Unit,
    onScreenFlashTone: (ScreenFlashTone) -> Unit,
    onLevelerEnabled: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isFront = state.settings.lensFacing ==
        androidx.camera.core.CameraSelector.LENS_FACING_FRONT

    // 0a. 重力感应横竖屏自适应 + 电子水平仪（对标系统相机 LevelerView + 抖音重力感应）
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "重力感应横竖屏自适应 · 电子水平仪",
                color = colors.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                buildString {
                    append("当前姿态：${state.orientation.label} (${state.orientation.degrees}°)")
                    if (state.gravity.isFlat) {
                        append(" · 桌面平放俯拍")
                    } else {
                        append(" · 倾角 %+.1f°".format(state.gravity.tiltDegrees))
                        if (state.gravity.isLeveled) append(" (已水平)")
                    }
                },
                color = if (state.gravity.isLeveled) colors.tertiary else colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
        Switch(
            checked = state.settings.levelerEnabled,
            onCheckedChange = onLevelerEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onTertiary,
                checkedTrackColor = colors.tertiary,
            ),
        )
    }

    Spacer(Modifier.height(6.dp))

    // 0. 闪光灯与补光模式（后置 LED 闪光/常亮手电筒 & 前置屏幕柔光环补光）
    ParamRow(
        label = if (isFront) "前置屏幕柔光环补光（支持三色温）" else "后置闪光灯 / 常亮补光灯",
        value = state.settings.flashMode.label,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            FlashMode.entries.forEach { mode ->
                val active = state.settings.flashMode == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.primary else colors.surfaceVariant)
                        .clickable { onFlashMode(mode) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mode.label,
                        color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        style = MonoSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        if (isFront && state.settings.flashMode != FlashMode.OFF) {
            Row(
                Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ScreenFlashTone.entries.forEach { tone ->
                    val active = state.settings.screenFlashTone == tone
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .background(if (active) colors.secondary else colors.surfaceVariant)
                            .clickable { onScreenFlashTone(tone) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tone.label,
                            color = if (active) colors.onSecondary else colors.onSurfaceVariant,
                            style = MonoSmall,
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    // 1. 画幅比例
    ParamRow(label = "画幅比例", value = state.settings.frameAspect.label) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FrameAspect.entries.forEach { asp ->
                val active = state.settings.frameAspect == asp
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.secondary else colors.surfaceVariant)
                        .clickable { onFrameAspect(asp) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        asp.label,
                        color = if (active) colors.onSecondary else colors.onSurfaceVariant,
                        style = MonoSmall,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    // 2. 超广角控制（兼容逻辑相机 <1× 变焦与物理子相机直连两种硬件架构）
    val uwMode = caps?.ultraWideMode ?: CameraController.UltraWideMode.NONE
    val inZoomUw = uwMode == CameraController.UltraWideMode.ZOOM && state.settings.zoomRatio < 0.95f
    val inPhysUw = uwMode == CameraController.UltraWideMode.PHYSICAL && state.settings.ultraWide
    val uwActive = inZoomUw || inPhysUw

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("超广角镜头", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
            Text(
                when (uwMode) {
                    CameraController.UltraWideMode.ZOOM ->
                        "逻辑多摄无缝切换（%.1f× 超广角 ↔ 1.0× 主摄）".format(zoomMin)
                    CameraController.UltraWideMode.PHYSICAL ->
                        "独立物理超广角子相机（切换时重绑管线）"
                    CameraController.UltraWideMode.NONE ->
                        if (caps == null) "相机未绑定" else "当前朝向无超广角镜头"
                },
                color = colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
        Switch(
            checked = uwActive,
            onCheckedChange = { wantUw ->
                when (uwMode) {
                    CameraController.UltraWideMode.ZOOM ->
                        onZoom(if (wantUw) zoomMin else 1f)
                    CameraController.UltraWideMode.PHYSICAL ->
                        onUltraWide(wantUw)
                    CameraController.UltraWideMode.NONE -> Unit
                }
            },
            enabled = uwMode != CameraController.UltraWideMode.NONE,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
            ),
        )
    }

    Spacer(Modifier.height(4.dp))

    // 3. 变焦滑杆 + 快捷档位
    ParamRow(
        label = "变焦倍率（预览框支持双指缩放）",
        value = "%.2f×".format(state.settings.zoomRatio),
    ) {
        if (zoomMax > zoomMin + 0.001f) {
            Slider(
                value = state.settings.zoomRatio.coerceIn(zoomMin, zoomMax),
                onValueChange = { onZoom(it) },
                valueRange = zoomMin..zoomMax,
                colors = sliderColors(colors),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            val steps = buildList {
                if (zoomMin < 0.95f) add(zoomMin)
                addAll(listOf(1f, 2f, 3f, 5f, 10f, 20f).filter { it in zoomMin..zoomMax })
            }.distinct()
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                steps.forEach { step ->
                    val active = kotlin.math.abs(state.settings.zoomRatio - step) < 0.06f
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .background(if (active) colors.primary else colors.surfaceVariant)
                            .clickable { onZoom(step) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (step < 1f) "%.1f×".format(step) else "${step.toInt()}×",
                            color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                            style = MonoSmall,
                        )
                    }
                }
            }
        } else {
            Text(
                if (caps == null) "设备能力尚未就绪"
                else "当前镜头不支持变焦（${caps.zoomMin}–${caps.zoomMax}）",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    Spacer(Modifier.height(6.dp))

    // 4. 手动对焦（屈光度 0 = 无穷远，maxD = 微距最近端）
    val maxD = caps?.minFocusDiopters ?: 0f
    ParamRow(
        label = "对焦距离（预览框点按AF / 长按锁定）",
        value = state.settings.manualFocus?.let { mf ->
            if (mf <= 0.05f) "MF · ∞ 无穷远"
            else "MF · %.0f cm (%.1fD)".format(100f / mf, mf)
        } ?: "AF · 自动对焦",
    ) {
        if (maxD > 0.01f) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.settings.manualFocus ?: 0f,
                    onValueChange = { onManualFocus(it) },
                    valueRange = 0f..maxD,
                    colors = sliderColors(colors),
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(ChipShape)
                        .background(
                            if (state.settings.manualFocus == null) colors.secondary
                            else colors.surfaceVariant,
                        )
                        .clickable { onManualFocus(null) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "AF 自动",
                        color = if (state.settings.manualFocus == null) colors.onSecondary
                        else colors.onSurfaceVariant,
                        style = MonoSmall,
                    )
                }
            }
            // 对焦预设档位：自动 / 无穷远 / 1m / 25cm / 最近微距
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val focusPresets = buildList {
                    add("自动" to null)
                    add("∞ 远景" to 0f)
                    if (maxD >= 1f) add("1m" to 1f)
                    if (maxD >= 4f) add("25cm" to 4f)
                    add("微距" to maxD)
                }
                focusPresets.forEach { (label, diopter) ->
                    val active = if (diopter == null) {
                        state.settings.manualFocus == null
                    } else {
                        state.settings.manualFocus != null &&
                            kotlin.math.abs(state.settings.manualFocus - diopter) < 0.15f
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .background(if (active) colors.secondary else colors.surfaceVariant)
                            .clickable { onManualFocus(diopter) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (active) colors.onSecondary else colors.onSurfaceVariant,
                            style = MonoSmall,
                        )
                    }
                }
            }
        } else {
            Text(
                "当前镜头为固定焦距（点按预览框仍可触发测光）",
                color = colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
    }
}

@Composable
private fun ExposureTabContent(
    state: UiState,
    exposureRange: IntRange,
    evStep: Double,
    onExposure: (Int) -> Unit,
    onGamma: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    ParamRow(
        label = "曝光补偿",
        value = "%+.1f EV".format(state.settings.exposureIndex * evStep),
        warn = state.settings.exposureIndex != 0,
    ) {
        if (exposureRange.last > exposureRange.first) {
            val safeIndex = state.settings.exposureIndex
                .coerceIn(exposureRange.first, exposureRange.last)
            Slider(
                value = safeIndex.toFloat(),
                onValueChange = { onExposure(it.toInt()) },
                valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                steps = ((exposureRange.last - exposureRange.first) - 1).coerceAtLeast(0),
                colors = sliderColors(colors),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            // 快捷 EV 按钮：-2 / -1 / 0 / +1 / +2 EV
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(-2, -1, 0, 1, 2).forEach { ev ->
                    val idx = kotlin.math.round(ev / evStep).toInt()
                        .coerceIn(exposureRange.first, exposureRange.last)
                    val active = state.settings.exposureIndex == idx
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .background(if (active) colors.primary else colors.surfaceVariant)
                            .clickable { onExposure(idx) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "%+d EV".format(ev),
                            color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                            style = MonoSmall,
                        )
                    }
                }
            }
        } else {
            Text(
                "此设备不支持曝光补偿",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("GC 伽马解码（sRGB → 线性光）", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
            Text(
                state.settings.gammaLabel(),
                color = colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
        Switch(
            checked = state.settings.gammaCorrected,
            onCheckedChange = onGamma,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.error,
                uncheckedTrackColor = colors.errorContainer,
                uncheckedBorderColor = colors.error,
            ),
        )
    }

    if (state.settings.exposureIndex != 0 || !state.settings.gammaCorrected) {
        Text(
            "曝光补偿 / GC 改变物理条件，已清除标定",
            color = colors.error,
            style = MonoSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun VideoTabContent(
    state: UiState,
    caps: CameraController.CapabilitySnapshot?,
    onVideoResolution: (VideoResolution) -> Unit,
    onVideoFps: (Int) -> Unit,
    onClarityMode: (ClarityMode) -> Unit,
    onVideoStabilization: (Boolean) -> Unit,
    onAudio: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val supportedQualities = caps?.videoQualities.orEmpty()
    val supportedFps = caps?.videoFpsSupported ?: setOf(30)
    val hsOnlyFps = caps?.videoFpsHighSpeedOnly.orEmpty()

    // 当前实际生效的录像规格说明
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("当前生效规格", color = colors.onSurfaceVariant, style = MonoSmall)
        Spacer(Modifier.weight(1f))
        Text(
            buildString {
                append(caps?.videoActualSize ?: state.settings.videoResolution.label)
                append(" · ")
                append("${caps?.videoAppliedFps ?: state.settings.videoFps}fps")
                append(" · ${caps?.videoBitrateMbps ?: 24}Mbps")
                append(" · ${state.settings.clarityMode.label}")
            },
            color = colors.tertiary,
            style = MonoSmall,
            fontWeight = FontWeight.Bold,
        )
    }

    Spacer(Modifier.height(8.dp))

    // 0. 算法高清 / 超清增强档位（HydraSharp 3×3 自适应锐化 + 双边保边降噪 + OneKeyHDR）
    ParamRow(
        label = "画质增强算法（HydraSharp + 3×3双边降噪 + OneKeyHDR）",
        value = state.settings.clarityMode.label,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ClarityMode.entries.forEach { mode ->
                val active = state.settings.clarityMode == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.tertiary else colors.surfaceVariant)
                        .clickable { onClarityMode(mode) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (mode) {
                            ClarityMode.OFF -> "标准"
                            ClarityMode.HD -> "高清"
                            ClarityMode.ULTRA -> "超清"
                            ClarityMode.HDR_ENHANCE -> "超清HDR"
                        },
                        color = if (active) colors.onTertiary else colors.onSurfaceVariant,
                        style = MonoSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Text(
            state.settings.clarityMode.desc,
            color = colors.onSurfaceVariant,
            style = MonoSmall,
            modifier = Modifier.padding(top = 3.dp),
        )
    }

    Spacer(Modifier.height(8.dp))

    // 1. 分辨率滑动条：720p — 1080p — 2K — 4K — 8K
    val resTiers = VideoResolution.entries
    val currentResIndex = resTiers.indexOf(state.settings.videoResolution).coerceAtLeast(0)
    val currentResSupported = supportedQualities.isEmpty() || state.settings.videoResolution in supportedQualities
    ParamRow(
        label = "分辨率滑动调节（最高 8K）",
        value = buildString {
            append("${state.settings.videoResolution.label} (${state.settings.videoResolution.width}×${state.settings.videoResolution.height})")
            if (!currentResSupported) append(" *当前设备不支持")
        },
        warn = !currentResSupported,
    ) {
        Slider(
            value = currentResIndex.toFloat(),
            onValueChange = { v ->
                if (!state.recording) {
                    val idx = kotlin.math.round(v).toInt().coerceIn(0, resTiers.lastIndex)
                    onVideoResolution(resTiers[idx])
                }
            },
            valueRange = 0f..resTiers.lastIndex.toFloat(),
            steps = (resTiers.size - 2).coerceAtLeast(0),
            enabled = !state.recording,
            colors = sliderColors(colors),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        // 滑轨下方刻度标签：当前设备原生不支持的档位标 *
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            resTiers.forEach { res ->
                val supported = supportedQualities.isEmpty() || res in supportedQualities
                val active = state.settings.videoResolution == res
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(
                            when {
                                active && supported -> colors.primary
                                active && !supported -> colors.secondary
                                supported -> colors.surfaceVariant
                                else -> colors.surfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                        .clickable(enabled = !state.recording) {
                            onVideoResolution(res)
                        }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (supported) res.label else "${res.label}*",
                        color = when {
                            active && supported -> colors.onPrimary
                            active && !supported -> colors.onSecondary
                            supported -> colors.onSurface
                            else -> colors.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        style = MonoSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // 2. 帧率滑动条：24 — 30 — 60 — 90 — 120 — 240 fps
    val currentFpsIndex = VIDEO_FPS_TIERS.indexOf(state.settings.videoFps).let { if (it >= 0) it else 1 }
    val currentFpsSupported = state.settings.videoFps in supportedFps
    ParamRow(
        label = "帧率滑动调节（最高 240fps）",
        value = if (currentFpsSupported) {
            "${state.settings.videoFps} fps"
        } else {
            "${state.settings.videoFps} fps* (当前设备不支持)"
        },
        warn = !currentFpsSupported,
    ) {
        Slider(
            value = currentFpsIndex.toFloat(),
            onValueChange = { v ->
                if (!state.recording) {
                    val idx = kotlin.math.round(v).toInt().coerceIn(0, VIDEO_FPS_TIERS.lastIndex)
                    onVideoFps(VIDEO_FPS_TIERS[idx])
                }
            },
            valueRange = 0f..VIDEO_FPS_TIERS.lastIndex.toFloat(),
            steps = (VIDEO_FPS_TIERS.size - 2).coerceAtLeast(0),
            enabled = !state.recording,
            colors = SliderDefaults.colors(
                thumbColor = if (currentFpsSupported) colors.secondary else colors.error,
                activeTrackColor = if (currentFpsSupported) colors.secondary else colors.error,
                inactiveTrackColor = colors.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        // 滑轨下方帧率刻度：当前设备/镜头不支持的帧率直接标 *
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VIDEO_FPS_TIERS.forEach { fps ->
                val isRegular = fps in supportedFps
                val active = state.settings.videoFps == fps
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(
                            when {
                                active && isRegular -> colors.secondary
                                active && !isRegular -> colors.errorContainer
                                isRegular -> colors.surfaceVariant
                                else -> colors.surfaceVariant.copy(alpha = 0.42f)
                            },
                        )
                        .clickable(enabled = !state.recording) {
                            onVideoFps(fps)
                        }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isRegular) "${fps}fps" else "${fps}*",
                        color = when {
                            active && isRegular -> colors.onSecondary
                            active && !isRegular -> colors.onErrorContainer
                            isRegular -> colors.onSurface
                            else -> colors.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        style = MonoSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        val unsupportedFpsList = VIDEO_FPS_TIERS.filter { it !in supportedFps }
        if (unsupportedFpsList.isNotEmpty()) {
            Text(
                buildString {
                    append("* 标记（${unsupportedFpsList.joinToString("/") { "${it}fps" }}）代表当前设备/镜头不支持该帧率")
                    if (hsOnlyFps.isNotEmpty()) {
                        append("（其中 ${hsOnlyFps.joinToString("/")}fps 仅限系统慢动作独占流）")
                    }
                    append("；换至支持设备后将根据硬件能力自动解锁。")
                },
                color = colors.onSurfaceVariant,
                style = MonoSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        caps?.videoNote?.let { note ->
            Text(
                note,
                color = colors.primary,
                style = MonoSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)

    // 3. 硬件视频防抖 (EIS/OIS) + 3DNR 降噪协同
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "EIS/OIS 智能防抖 + 硬件 3DNR 降噪",
                color = colors.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                if (state.settings.videoStabilization) {
                    "已启用光学/电子防抖 + 零延迟硬件 3DNR + 紫边色散校正"
                } else {
                    "已关闭防抖（固定脚架拍摄时可关）"
                },
                color = colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
        Switch(
            checked = state.settings.videoStabilization,
            onCheckedChange = onVideoStabilization,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onSecondary,
                checkedTrackColor = colors.secondary,
            ),
        )
    }

    Spacer(Modifier.height(4.dp))

    // 4. 录音开关
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("录制麦克风音频", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
            Text(
                if (state.settings.audioEnabled) "录像时同步收录环境声音" else "静音录制视频",
                color = colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
        Switch(
            checked = state.settings.audioEnabled,
            onCheckedChange = onAudio,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
            ),
        )
    }
}

@Composable
private fun LookTabContent(
    state: UiState,
    onClarityMode: (ClarityMode) -> Unit,
    onIntensity: (Float) -> Unit,
    onGradeChannel: (ColorGrade.Channel, Float) -> Unit,
    onGradePreset: (String) -> Unit,
    onGradeReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    ParamRow(
        label = "画质增强算法（HydraSharp + 3×3双边降噪 + OneKeyHDR）",
        value = state.settings.clarityMode.label,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ClarityMode.entries.forEach { mode ->
                val active = state.settings.clarityMode == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.tertiary else colors.surfaceVariant)
                        .clickable { onClarityMode(mode) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (mode) {
                            ClarityMode.OFF -> "标准"
                            ClarityMode.HD -> "高清"
                            ClarityMode.ULTRA -> "超清"
                            ClarityMode.HDR_ENHANCE -> "超清HDR"
                        },
                        color = if (active) colors.onTertiary else colors.onSurfaceVariant,
                        style = MonoSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    ParamRow(
        label = "滤镜强度（${state.filter.label}）",
        value = "%.0f%%".format(state.settings.filterIntensity * 100),
    ) {
        Slider(
            value = state.settings.filterIntensity,
            onValueChange = { onIntensity(it) },
            valueRange = 0f..1f,
            colors = sliderColors(colors),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }

    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.outlineVariant)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("调色", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.settings.grade.isIdentity) "不影响测量" else "已调 ${state.settings.grade.activeCount} 项",
            color = colors.tertiary,
            style = MonoSmall,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(ChipShape)
                .background(colors.surfaceVariant)
                .clickable { onGradeReset() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text("还原", color = colors.onSurfaceVariant, style = MonoSmall)
        }
    }

    LazyRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(ColorGrade.presets) { (name, _) ->
            val active = state.settings.gradePreset == name
            Box(
                Modifier
                    .clip(ChipShape)
                    .background(if (active) colors.tertiary else colors.surfaceVariant)
                    .clickable { onGradePreset(name) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Text(
                    name,
                    color = if (active) colors.onTertiary else colors.onSurfaceVariant,
                    style = MonoSmall,
                )
            }
        }
    }

    ColorGrade.Channel.entries.forEach { ch ->
        val v = ch.get(state.settings.grade)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ch.label,
                color = colors.onSurfaceVariant,
                style = MonoSmall,
                modifier = Modifier.width(48.dp),
            )
            Slider(
                value = v,
                onValueChange = { onGradeChannel(ch, it) },
                valueRange = ch.min..ch.max,
                colors = SliderDefaults.colors(
                    thumbColor = colors.tertiary,
                    activeTrackColor = colors.tertiary,
                    inactiveTrackColor = colors.outlineVariant,
                ),
                modifier = Modifier.weight(1f).height(26.dp),
            )
            Text(
                if (ch.min < 0f) "%+.0f".format(v * 100) else "%.0f".format(v * 100),
                color = if (v != 0f) colors.tertiary else colors.onSurfaceVariant,
                style = MonoSmall,
                modifier = Modifier.width(38.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun WatermarkTabContent(
    state: UiState,
    onWatermarkEnabled: (Boolean) -> Unit,
    onWatermark: ((Watermark) -> Watermark) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("照片水印", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
            Text(
                if (state.settings.watermark.enabled) "拍照时合成进照片" else "关闭时不加水印",
                color = colors.onSurfaceVariant,
                style = MonoSmall,
            )
        }
        Switch(
            checked = state.settings.watermark.enabled,
            onCheckedChange = onWatermarkEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
            ),
        )
    }

    if (state.settings.watermark.enabled) {
        val wm = state.settings.watermark
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Watermark.Corner.entries.forEach { c ->
                val active = wm.corner == c
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.primary else colors.surfaceVariant)
                        .clickable { onWatermark { it.copy(corner = c) } }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        c.label,
                        color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        style = MonoSmall,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Watermark.Style.entries.forEach { st ->
                val active = wm.style == st
                Box(
                    Modifier
                        .weight(1f)
                        .clip(ChipShape)
                        .background(if (active) colors.secondary else colors.surfaceVariant)
                        .clickable { onWatermark { it.copy(style = st) } }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        st.label,
                        color = if (active) colors.onSecondary else colors.onSurfaceVariant,
                        style = MonoSmall,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("时间" to wm.showTime, "地点" to wm.showLocation, "文字" to wm.showCustom)
                .forEach { (label, on) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(ChipShape)
                            .background(if (on) colors.tertiary else colors.surfaceVariant)
                            .clickable {
                                onWatermark {
                                    when (label) {
                                        "时间" -> it.copy(showTime = !it.showTime)
                                        "地点" -> it.copy(showLocation = !it.showLocation)
                                        else -> it.copy(showCustom = !it.showCustom)
                                    }
                                }
                            }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (on) colors.onTertiary else colors.onSurfaceVariant,
                            style = MonoSmall,
                        )
                    }
                }
        }
        androidx.compose.material3.OutlinedTextField(
            value = wm.customText,
            onValueChange = { t -> onWatermark { it.copy(customText = t.take(24)) } },
            label = { Text("自定义文字", style = MonoSmall) },
            singleLine = true,
            textStyle = MonoSmall,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    warn: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                color = if (warn) colors.error else colors.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = Mono,
            )
        }
        content()
    }
}

@Composable
private fun sliderColors(colors: androidx.compose.material3.ColorScheme) =
    androidx.compose.material3.SliderDefaults.colors(
        thumbColor = colors.primary,
        activeTrackColor = colors.primary,
        inactiveTrackColor = colors.outlineVariant,
    )


@Composable
private fun FilterBar(
    selected: LiveFilter,
    liveOn: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (LiveFilter) -> Unit,
    onToggleLive: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .background(colors.scrim.copy(alpha = OVERLAY_ALPHA), ChipShape)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 实时开关：关掉就看原图，用来对比滤镜效果。这是"滤镜"和"实时滤镜"的区别。
        Box(
            Modifier
                .clip(ChipShape)
                .background(if (liveOn) colors.tertiary else colors.outlineVariant)
                .clickable { onToggleLive() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                if (liveOn) "实时" else "关",
                color = if (liveOn) colors.onTertiary else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(4.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(LiveFilter.entries.toList()) { kind ->
                val active = kind == selected
                Box(
                    Modifier
                        .clip(ChipShape)
                        .background(if (active) colors.primary else Color.Transparent)
                        .clickable { onSelect(kind) }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                ) {
                    Text(
                        kind.label,
                        color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: UiState, caps: CameraController.CapabilitySnapshot?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                color = colors.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "实时滤镜 · 拍照 · 录像 · 参数可调",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(
                state.orientation.shortLabel,
                if (state.orientation.isLandscape) colors.secondary else colors.tertiary,
            )
            val fpsText = caps?.videoAppliedFps ?: state.settings.videoFps
            StatusChip(
                "${state.settings.videoResolution.label}·${fpsText}f",
                colors.primary,
            )
            if (state.recording) {
                StatusChip("录像中", colors.error)
            } else if (state.settings.audioEnabled) {
                StatusChip("录音", colors.secondary)
            } else {
                StatusChip("静音", colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, tint: Color) {
    Row(
        Modifier
            .background(tint.copy(alpha = TINT_ALPHA), ChipShape)
            .border(1.dp, tint.copy(alpha = 0.45f), ChipShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReticleOverlay(fraction: Double) {
    // 准星用琥珀主色 + 圆头线帽：在多数样品色上都比冷青色更醒目，且与快门同色，
    // 暗示"按快门测的就是框里这一块"。
    val c = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val short = minOf(size.width, size.height)
        val side = (short * fraction).toFloat()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = side / 2f

        // Corner brackets rather than a full box: a full box hides the edges of
        // the patch, and on a gradient the operator needs to see what is inside.
        val arm = side * 0.22f
        val w = 2.5.dp.toPx()
        fun bracket(from: Offset, to: Offset) = drawLine(c, from, to, w, StrokeCap.Round)

        bracket(Offset(cx - half, cy - half), Offset(cx - half + arm, cy - half))
        bracket(Offset(cx - half, cy - half), Offset(cx - half, cy - half + arm))
        bracket(Offset(cx + half, cy - half), Offset(cx + half - arm, cy - half))
        bracket(Offset(cx + half, cy - half), Offset(cx + half, cy - half + arm))
        bracket(Offset(cx - half, cy + half), Offset(cx - half + arm, cy + half))
        bracket(Offset(cx - half, cy + half), Offset(cx - half, cy + half - arm))
        bracket(Offset(cx + half, cy + half), Offset(cx + half - arm, cy + half))
        bracket(Offset(cx + half, cy + half), Offset(cx + half, cy + half - arm))

        // 中心十字点：标出测量中心，便于对准小样品。
        val tick = 4.dp.toPx()
        drawLine(c, Offset(cx - tick, cy), Offset(cx + tick, cy), w * 0.6f)
        drawLine(c, Offset(cx, cy - tick), Offset(cx, cy + tick), w * 0.6f)
    }
}

@Composable
private fun ShutterButton(busy: Boolean, recording: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // 外环表达状态：录像中=红 / 就绪=绿 / 忙碌=灰。
    val ring = when {
        busy -> colors.outline
        recording -> colors.error
        else -> colors.tertiary
    }
    val core = when {
        busy -> colors.outline
        recording -> colors.error
        else -> colors.primary
    }
    Box(
        Modifier
            .size(SHUTTER_SIZE)
            .border(3.dp, ring, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(core)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics {
                contentDescription = if (recording) "快门：停止录像" else "快门：拍照"
                role = Role.Button
            },
    )
}

