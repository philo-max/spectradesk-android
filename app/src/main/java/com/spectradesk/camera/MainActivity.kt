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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
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
    // 设备能力（变焦范围、曝光范围）。绑定完成后由 bind() 的返回值填充。
    // 用状态而不是 remember 缓存：camera 字段不是 Compose 状态，缓存会拿到空值。
    var caps by remember { mutableStateOf<CameraController.CapabilitySnapshot?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Camera binding must NOT run inside a LaunchedEffect keyed on things that
    // change often. Its coroutine scope is cancelled the moment the composable
    // leaves the composition, which surfaces as "coroutine scope left the
    // composition" and a silent fallback to a black preview.
    LaunchedEffect(controller, previewView, state.settings.lensFacing, state.settings.frameAspect) {
        val c = controller ?: return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        try {
            caps = c.bind(
                lifecycleOwner,
                view,
                state.settings.lensFacing,
                filter = if (state.liveFilterOn) state.filter else LiveFilter.NONE,
                intensity = state.settings.filterIntensity,
                grade = state.settings.grade,
                frameAspect = state.settings.frameAspect,
            )
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

    // 调色：只写渲染器字段，拖滑杆实时生效，不重建管线。
    LaunchedEffect(state.settings.grade) {
        controller?.setColorGrade(state.settings.grade)
    }

    // 曝光补偿
    LaunchedEffect(state.settings.exposureIndex) {
        controller?.setExposureCompensation(state.settings.exposureIndex)
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

    /** 快门：拍照存图库。 */
    fun takePhoto() {
        val c = controller ?: return
        scope.launch {
            vm.setBusy(true)
            try {
                val ok = c.captureToGallery()
                vm.message(if (ok) "已保存到相册 · Pictures/LightYear" else "保存失败")
            } catch (e: Exception) {
                vm.message("拍摄失败：${e.message}")
            } finally {
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

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Header(state)

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(PanelShape)
                .border(1.dp, colors.outlineVariant, PanelShape),
            contentAlignment = Alignment.Center,
        ) {            AndroidView(
                factory = { ctx ->
                    PreviewSurfaceView(ctx).also {
                        previewView = it
                    }
                },
                // 取景器按用户选的比例定形，和预览流、照片三者一致。
                //
                // 之前这里是 fillMaxSize()：取景器变成 1176×2067（比例 0.569），
                // 而默认的 4:3 预览流竖过来是 0.75，shader 只能横向裁掉 24%，
                // 等效多出 1.32× 变焦 —— 用户看到的"镜头拉长"就是这么来的。
                //
                // 现在三条流都跟着 state.settings.frameAspect 走：
                // 16:9 时取景框比例 0.5625，几乎正好等于屏幕可用区域，
                // 裁切量降到 1% 以内，等效放大回到 1.00×。
                //
                // `fillMaxHeight()` 让取景框先吃满可用高度，再由 aspectRatio
                // 反推宽度 —— 否则在 4:3 下宽高比 0.75 会留下左右黑边，
                // 在 16:9 下又会因为 weight 容器没收缩而上下留黑。
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(state.settings.frameAspect.portraitRatio)
                    .background(colors.scrim),
            )

            // 测量准星：只有开测量模式才显示，否则普通拍照/录像时它是干扰。
            if (state.paramsOpen) {
                ReticleOverlay(fraction = state.settings.patchFraction)
            }

            // 录像红点 + 计时
            if (state.recording) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(colors.scrim.copy(alpha = 0.7f), ChipShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(colors.error, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "%02d:%02d".format(state.recordingSeconds / 60, state.recordingSeconds % 60),
                        color = colors.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = Mono,
                    )
                }
            }

            // 滤镜作用中的提示
            if (state.liveFilterOn && state.filter != LiveFilter.NONE) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
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

            FilterBar(
                selected = state.filter,
                liveOn = state.liveFilterOn,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                onSelect = { vm.setFilter(it) },
                onToggleLive = { vm.setLiveFilterOn(!state.liveFilterOn) },
            )

            // 参数面板做成取景器内的浮层，而不是挤在下方。
            //
            // 这不是审美选择：SurfaceView 的缓冲区尺寸跟 View 尺寸绑定，
            // 一旦参数面板把取景器压矮，缓冲区要重建，重建期间画面会缩在
            // 中间露出黑边。浮层不改变取景器尺寸，就没有这个问题。
            if (state.paramsOpen) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 44.dp)
                        .background(colors.surface.copy(alpha = 0.96f), RoundedCornerShape(10.dp))
                        // 面板加了调色十项滑杆，高度会超过取景器 —— 必须能滚。
                        // 用 verticalScroll 而不是 LazyColumn：项数固定且少，
                        // LazyColumn 的复用在这里只会让滑杆状态更难保持。
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ParamsPanel(
                        state = state,
                        controller = controller,
                        caps = caps,
                        onZoom = { vm.setZoom(it, (caps?.zoomMin ?: 1f)..(caps?.zoomMax ?: 1f)) },
                        onExposure = { vm.setExposureIndex(it) },
                        onIntensity = { vm.setFilterIntensity(it) },
                        onAudio = { vm.setAudioEnabled(it) },
                        onGamma = vm::setGammaCorrected,
                        onClampExposure = { vm.clampExposureIndex(it) },
                        onGradeChannel = { ch, v -> vm.setGradeChannel(ch, v) },
                        onGradePreset = { vm.applyGradePreset(it) },
                        onGradeReset = { vm.resetGrade() },
                        onFrameAspect = { vm.setFrameAspect(it) },
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

        // 主控制条：参数开关 | 快门 | 录像
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 参数面板开关
            Box(
                Modifier
                    .size(52.dp)
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

            // 前后置切换。放在快门左侧、参数右侧，和系统相机的布局一致。
            val isFront = state.settings.lensFacing ==
                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            Box(
                Modifier
                    .size(52.dp)
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
                else -> "点快门拍照 · 右侧开始录像"
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
 * 参数面板：变焦、曝光补偿、滤镜强度、录音、伽马。
 *
 * ## 哪些参数是"观感"、哪些会改测量
 *
 * 这个区分必须显式做出来，否则用户会以为所有滑块都只影响画面：
 *
 * | 参数 | 改观感 | 改测量读数 | 处理 |
 * |---|---|---|---|
 * | 变焦 | 是 | 否（只改取景范围） | 直接下发 |
 * | 滤镜强度 | 是 | 否（测量读原始帧） | 只写渲染器 |
 * | 录音 | 否 | 否 | 只影响视频 |
 * | 曝光补偿 | 是 | **是** | 清空标定 |
 * | GC 伽马 | 是 | **是** | 清空标定 |
 *
 * 后两个不是"设置"，是物理条件的改变：白参考是在旧条件下测的，
 * 拿它去除新读数会得到一个看起来合理的错误颜色。
 */
@Composable
private fun ParamsPanel(
    state: UiState,
    controller: CameraController?,
    /** 相机绑定完成时取到的设备能力；null 表示还没绑定好。 */
    caps: CameraController.CapabilitySnapshot?,
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
) {
    val colors = MaterialTheme.colorScheme

    // 设备能力直接来自 bind() 的返回值，不再自己读 controller。
    //
    // 之前用 remember(controller, bound) 缓存 zoomRange()/exposureRange()：
    // 那两个方法读的是 controller 内部的 camera 字段，它不是 Compose 状态。
    // bound 变 true 的那一刻 camera 可能还没赋值（绑定是协程，赋值在末尾），
    // 于是空范围被永久缓存 —— 实测表现是"变焦显示 1.0×、拖不动"。
    val zoomMax = caps?.zoomMax ?: 1f
    val exposureRange = caps?.exposureRange ?: 0..0
    /** 设备每档曝光补偿的 EV 数。默认 1/3，绑定后由真实范围推出。 */
    val evStep = caps?.exposureStepEv ?: (1.0 / 3.0)

    // 范围拿到后校正一次索引：绑定前滑块可能已经把索引推到设备不支持的档位。
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
        // 画幅比例。放最上面：它决定下面所有参数作用在多大的画面上。
        ParamRow(label = "画幅", value = state.settings.frameAspect.label) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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

        // 变焦。下界取设备的真实下限 —— 带超广角的机型是 0.6，不是 1.0。
        //
        // 之前这里写死 `1f..zoomMax`，于是 0.6–1.0 那段永远拖不到，
        // 而这台 vivo 的超广角逻辑相机恰恰是 0.6 起。滑杆下界必须是设备
        // 报的 zoomMin，否则用户要的"0–1 区间"根本不存在。
        val zoomMin = caps?.zoomMin ?: 1f
        ParamRow(
            label = "变焦",
            value = "%.1f×".format(state.settings.zoomRatio),
        ) {
            if (zoomMax > zoomMin + 0.001f) {
                Slider(
                    value = state.settings.zoomRatio.coerceIn(zoomMin, zoomMax),
                    onValueChange = { onZoom(it) },
                    valueRange = zoomMin..zoomMax,
                    colors = sliderColors(colors),
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                // 快捷档位：0.6/1/2/3/5/10 一键跳。滑杆在 0.6–10 上
                // 一像素≈0.02×，精确跳到 1.0× 很难，这几个按钮解决它。
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(0.6f, 1f, 2f, 3f, 5f, 10f)
                        .filter { it in zoomMin..zoomMax }
                        .forEach { step ->
                            val active = kotlin.math.abs(state.settings.zoomRatio - step) < 0.05f
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
                // 把实际拿到的能力显示出来，而不是只说"不支持"。
                // 排查时这一行比任何日志都有用：它直接说明 caps 是 null
                // （还没绑定好）还是 max 真的是 1.0（设备真不支持）。
                Text(
                    if (caps == null) "设备能力尚未就绪（相机未绑定）"
                    else "此设备不支持变焦（${caps.zoomMin}–${caps.zoomMax}）",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // 曝光补偿。EV 步长不是常数 3 —— 设备每档可能是 1/3、1/2 甚至 1/4 EV。
        // 之前写死 `/3.0`，在这台每档 1/6 EV 的机器上读数会差一倍。
        ParamRow(
            label = "曝光补偿",
            value = "%+.1f EV".format(
                state.settings.exposureIndex * evStep,
            ),
            warn = state.settings.exposureIndex != 0,
        ) {
            if (exposureRange.last > exposureRange.first) {
                // 索引必须夹在真实范围内。范围在绑定前是 0..0，那时任何触摸都会
                // 算出越界值；夹一次保证不会下发 -3.7 EV 这种设备根本不支持的档位。
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
            } else {
                Text(
                    "此设备不支持曝光补偿",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // 滤镜强度
        ParamRow(
            label = "滤镜强度",
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

        // 开关行
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("录音", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = state.settings.audioEnabled,
                onCheckedChange = onAudio,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text("GC 伽马", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
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

        // 这两项会清标定，必须写在面板上而不是只写在代码注释里。
        if (state.settings.exposureIndex != 0 || !state.settings.gammaCorrected) {
            Text(
                "曝光补偿 / GC 改变物理条件，已清除标定",
                color = colors.error,
                style = MonoSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)

        // ── 调色（参考剪映/醒图的「调节」分项）─────────────────────────
        //
        // 为什么和上面的参数分开：上面那些改的是**相机**（变焦、曝光、GC），
        // 会改变物理条件甚至清掉标定；这里改的是**画面**，纯显示变换，
        // 不进任何数字。分开标注，用户才不会以为调色会影响测色结果。
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

        // 预设：横排小胶囊，和上面的滤镜条区分开（这里是调色不是风格）。
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

        // 十项滑杆。用循环生成，避免十段几乎一样的代码 —— 每项只在
        // label/范围/get/set 上有差别，而这三个都已在 ColorGrade.Channel 里。
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
                    // 只有双向的项带正负号；锐化/暗角/颗粒是 0..1，加号没意义。
                    if (ch.min < 0f) "%+.0f".format(v * 100) else "%.0f".format(v * 100),
                    color = if (v != 0f) colors.tertiary else colors.onSurfaceVariant,
                    style = MonoSmall,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
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
private fun Header(state: UiState) {
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
        // 仪器"面板指示灯"：一眼判断当前状态。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.liveFilterOn && state.filter != LiveFilter.NONE) {
                StatusChip("实时滤镜", colors.tertiary)
            } else {
                StatusChip("原图", colors.onSurfaceVariant)
            }
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

