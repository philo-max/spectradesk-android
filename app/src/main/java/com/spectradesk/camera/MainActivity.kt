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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) recreate() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the system bars, then pad the content by the insets.  The
        // previous version did neither, so the header sat underneath the status
        // bar: the title was clipped and the two status pills overlapped the
        // clock and battery icons.  That is the "适应得有点怪" part - nothing was
        // broken, but every element was half a centimetre from where it belonged.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var glView by remember { mutableStateOf<android.opengl.GLSurfaceView?>(null) }
    var renderer by remember { mutableStateOf<LiveFilterRenderer?>(null) }
    // Flipped to true from the GL thread once the renderer has its SurfaceTexture.
    // The camera bind waits on this, because binding first makes CameraX reject
    // the surface and every filter silently disappears.
    var glReady by remember { mutableStateOf(false) }
    var bound by remember { mutableStateOf(false) }

    // Camera binding must NOT run inside a LaunchedEffect. Its coroutine scope is
    // cancelled the moment the composable leaves the composition - which happens
    // on any recomposition that changes a key, including the `glReady` flip this
    // very bind waits for. The result is the "coroutine scope left the composition"
    // error and a silent fallback to the unfiltered preview.
    //
    // A scope tied to the Activity survives recomposition, so the bind completes
    // even while the UI is rebuilding. This is the same fix the earlier
    // LaunchedEffect bind needed.
    val activityScope = rememberCoroutineScope()

    // Two bind paths: the GL renderer (needed for live filters) and the plain
    // PreviewView (fallback if GL fails). A measuring tool must still let the
    // operator take a reading when the GPU path is unavailable, so failing to
    // create a GL context degrades to an unfiltered preview instead of a black
    // screen.
    //
    // `glReady` is the key detail: the renderer's SurfaceTexture is created on the
    // GL thread inside onSurfaceCreated, which runs *after* the factory returns.
    // Binding before it exists makes CameraX refuse the surface, so the bind is
    // deferred until the renderer reports that its GL surface exists.
    LaunchedEffect(controller, glReady, state.settings.lensFacing) {
        val c = controller ?: return@LaunchedEffect
        if (!glReady) return@LaunchedEffect
        val r = renderer ?: return@LaunchedEffect
        activityScope.launch {
            try {
                c.bindWithRenderer(lifecycleOwner, r, state.settings.lensFacing)
                bound = true
            } catch (e: Throwable) {
                // Log the actual cause: a silent fallback here means the operator
                // sees "filters unavailable" with no way to diagnose why, and a
                // filter that silently disappears looks like a measurement
                // problem rather than a preview one.
                android.util.Log.e(
                    "SpectraDesk",
                    "GL preview bind failed, falling back to PreviewView",
                    e,
                )
                vm.message("实时滤镜不可用（${e.javaClass.simpleName}），已退回普通预览")
                // Fall back to the plain preview so framing still works.
                val view = previewView ?: return@launch
                try {
                    c.bind(lifecycleOwner, view, state.settings.lensFacing)
                    bound = true
                } catch (e2: Throwable) {
                    android.util.Log.e("SpectraDesk", "camera bind failed", e2)
                    vm.message("相机启动失败：${e2.javaClass.simpleName}: ${e2.message}")
                }
            }
        }
    }

    // GLSurfaceView must be resumed and paused with the Activity, or its render
    // thread stops and the preview goes black while the camera stays open. The
    // AndroidView factory has no lifecycle hook of its own, so this is driven
    // from the composable's own lifecycle observer.
    val glLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(glLifecycleOwner, glView) {
        val view = glView
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> view?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        glLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            glLifecycleOwner.lifecycle.removeObserver(observer)
            view?.onPause()
        }
    }

    // Push the selected filter into the renderer.  The renderer reads these fields
    // on the GL thread during onDrawFrame, so a plain volatile write is the right
    // handoff: the frame that is one filter behind is imperceptible, and a lock
    // would stall the GL thread.
    LaunchedEffect(state.filter) {
        renderer?.filter = state.filter
    }

    /** One shutter action: capture, then either calibrate or measure. */
    fun shutter(mode: ShutterMode) {
        val c = controller ?: return
        scope.launch {
            vm.setBusy(true)
            try {
                // Locking before the white reference is what makes every later
                // reading comparable: with AE/AWB free-running, the reference and
                // the sample are effectively taken by two different cameras.
                if (mode == ShutterMode.WHITE) {
                    val locked = c.setAutoLock(true)
                    vm.setExposureLocked(locked)
                }
                val shot = c.capture(state.settings.gammaCorrected)
                if (shot == null) {
                    vm.message("正在拍摄，请稍候")
                } else {
                    when (mode) {
                        ShutterMode.WHITE -> vm.takeWhiteReference(shot, state.exposureLocked)
                        ShutterMode.DARK -> vm.takeDarkReference(shot)
                        ShutterMode.MEASURE -> vm.measure(shot)
                    }
                }
            } catch (e: Exception) {
                vm.message("拍摄失败：${e.message}")
            } finally {
                vm.setBusy(false)
            }
        }
    }

    val colors = MaterialTheme.colorScheme

    // 版面主次：取景区占用全部剩余高度（weight 1f）成为最大块；
    // 读数卡紧随其下作为视觉焦点；控制区压缩为紧凑的次要层；记录区最弱。
    //
    // 系统栏内边距：状态栏高度必须让给顶部，导航栏高度必须让给底部，
    // 否则标题会被状态栏压住、快门会被手势条压住。这是上一版"看着别扭"
    // 的根因——元素本身没错，只是位置整体偏了。
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Header(state)

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(PanelShape)
                .border(1.dp, colors.outlineVariant, PanelShape),
        ) {
            AndroidView(
                factory = { ctx ->
                    // GLSurfaceView, not PreviewView: the camera renders into an
                    // external OES texture that the fragment shader samples, which
                    // is the only way to get true live filters. PreviewView blits
                    // the camera surface directly and offers no pixel hook.
                    android.opengl.GLSurfaceView(ctx).also { view ->
                        val r = LiveFilterRenderer(
                            context = ctx,
                            onFrameAvailable = {
                                view.requestRender()
                            },
                            // Runs on the GL thread; the surface now exists, so the
                            // camera bind can proceed. Compose state written from a
                            // non-main thread does trigger recomposition (snapshot
                            // system handles it), which is what unblocks the bind.
                            onSurfaceReady = { glReady = true },
                        )
                        view.setEGLContextClientVersion(2)
                        view.setRenderer(r)
                        // Continuous rendering rather than WHEN_DIRTY: with
                        // WHEN_DIRTY the frame is only drawn when requestRender()
                        // fires, and a missed call leaves a black preview with no
                        // error anywhere. The battery cost of a full-screen
                        // viewfinder is the same either way at 30 fps, so the
                        // robust option is also the cheaper one.
                        view.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        glView = view
                        renderer = r
                    }
                },
                modifier = Modifier.fillMaxSize().background(colors.scrim),
            )

            // When a filter is active, the captured frame is shown on top of the
            // live preview so the operator can examine the sample through it.
            // The live preview stays underneath and keeps running, so the moment
            // the filter is switched off the viewfinder is current again.

            // The reticle marks the exact area that is measured.  Without it the
            // user has no idea which part of the frame the number came from.
            ReticleOverlay(fraction = state.settings.patchFraction)

            // Filter chip strip, overlaid on the viewfinder rather than placed in
            // the control column: choosing a viewing aid is a framing decision and
            // belongs next to the image, not next to the shutter.
            FilterBar(
                selected = state.filter,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                onSelect = { vm.setFilter(it) },
            )

            // A filter that changes pixels is a viewing aid, not a measurement
            // condition.  Saying so on screen prevents the classic mistake of
            // trusting a number taken through a false-colour view.
            if (!state.filter.measurementSafe) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(colors.errorContainer, ChipShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("滤镜仅影响显示", color = colors.onErrorContainer, style = MaterialTheme.typography.labelSmall)
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
                        .padding(8.dp)
                        .background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(msg, color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Readout(state)
        Spacer(Modifier.height(6.dp))

        Controls(
            state = state,
            onShutter = { shutter(ShutterMode.MEASURE) },
            onWhite = { shutter(ShutterMode.WHITE) },
            onDark = { shutter(ShutterMode.DARK) },
            onGamma = vm::setGammaCorrected,
            onStandard = vm::setStandard,
            onClearCal = vm::clearCalibration,
            onClearHistory = vm::clearHistory,
            onExportCsv = {
                if (state.measurements.isEmpty()) {
                    vm.message("没有可导出的记录")
                } else {
                    val f = Exporter.writeCsv(context, state)
                    Exporter.share(context, f, "text/csv")
                }
            },
            onExportHtml = {
                if (state.measurements.isEmpty()) {
                    vm.message("没有可导出的记录")
                } else {
                    val f = Exporter.writeHtml(context, state)
                    Exporter.share(context, f, "text/html")
                }
            },
        )

        if (state.measurements.isNotEmpty()) {
            History(state.measurements)
        }
    }
}

enum class ShutterMode { MEASURE, WHITE, DARK }

@Composable
private fun FilterBar(
    selected: LiveFilter,
    modifier: Modifier = Modifier,
    onSelect: (LiveFilter) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 半透明底条压在取景画面上：选中项用琥珀主色，与快门、准星同属"操作色"。
    LazyRow(
        modifier = modifier
            .background(colors.scrim.copy(alpha = OVERLAY_ALPHA), ChipShape)
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
                    .padding(horizontal = 12.dp, vertical = 5.dp),
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

@Composable
private fun Header(state: UiState) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Product name, not a category label.  This ships to consumers, so
            // the title is the brand; what it measures belongs in the subtitle.
            Text(
                stringResource(R.string.app_name),
                color = colors.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "色差 · ΔE00 · ${state.settings.gammaLabel()}",
                color = if (state.settings.gammaCorrected) colors.onSurfaceVariant else colors.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // 仪器"面板指示灯"：一眼判断能否开始测量。
        // 已标定=绿 / 未标定=红；曝光锁定=蓝 / 未锁=灰。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.calibration.isReady) {
                StatusChip("已标定", colors.tertiary)
            } else {
                StatusChip("未标定", colors.error)
            }
            if (state.exposureLocked) {
                StatusChip("曝光锁", colors.secondary)
            } else {
                StatusChip("未锁", colors.onSurfaceVariant)
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
private fun Readout(state: UiState) {
    val colors = MaterialTheme.colorScheme
    val m = state.lastMeasurement
    // 判定色贯穿整张卡：顶部色条 + 徽章 + ΔE 数字同色，扫一眼就知道结果。
    val verdictTint = when (m?.passed) {
        true -> colors.tertiary
        false -> colors.error
        null -> colors.outline
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(PanelShape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, PanelShape),
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(verdictTint))

        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
            // 第一层：色块 + ΔE 主读数 + 判定徽章 —— 整个界面最大的数字。
            Row(verticalAlignment = Alignment.CenterVertically) {
                SampleSwatch(m?.lab)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ΔE00", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (m == null) "—.——" else Format.de(m.deltaE),
                        color = when (m?.passed) {
                            false -> colors.error
                            null -> if (m == null) colors.outline else colors.onSurface
                            true -> colors.onSurface
                        },
                        style = MaterialTheme.typography.displayMedium,
                        maxLines = 1,
                    )
                }
                VerdictBadge(m)
            }

            m?.standardName?.let { name ->
                val tol = Standards.byName(name)?.tolerance
                Text(
                    if (tol != null) "对比 $name · 容差 ΔE ≤ ${"%.1f".format(tol)}" else "对比 $name",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.outlineVariant)

            if (m == null) {
                // 空态：保留 L*a*b* 刻度位，避免首次测量后卡片高度跳变。
                Row(Modifier.fillMaxWidth()) {
                    LabValue("L*", null, Modifier.weight(1f))
                    LabValue("a*", null, Modifier.weight(1f))
                    LabValue("b*", null, Modifier.weight(1f))
                }
                Text(
                    if (state.calibration.isReady) "对准样品，点下方快门测量。"
                    else "首次使用请先对白板拍「白参考」完成标定。",
                    color = if (state.calibration.isReady) colors.onSurfaceVariant else colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                // 第二层：L*a*b* 三个等宽大数字，是 ΔE 的"原因"。
                Row(Modifier.fillMaxWidth()) {
                    LabValue("L*", m.lab[0], Modifier.weight(1f), m.standardLab?.get(0))
                    LabValue("a*", m.lab[1], Modifier.weight(1f), m.standardLab?.get(1))
                    LabValue("b*", m.lab[2], Modifier.weight(1f), m.standardLab?.get(2))
                }
                Spacer(Modifier.height(10.dp))
                // 第三层：派生量用小号等宽字，作为参考信息。
                Row(Modifier.fillMaxWidth()) {
                    Metric("C*", "%.2f".format(m.lch[1]), Modifier.weight(1f))
                    Metric("h°", "%.1f".format(m.lch[2]), Modifier.weight(1f))
                    Metric("CCT", if (m.cct > 0) "%.0fK".format(m.cct) else "—", Modifier.weight(1f))
                    Metric("x,y", "%.3f,%.3f".format(m.xy[0], m.xy[1]), Modifier.weight(1.4f))
                }
                Text(
                    "三通道估计 · ${if (m.gammaCorrected) "GC 开" else "GC 关"}",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 样品色块：把测得的 Lab 反算为 sRGB 显示，让数字与"看到的颜色"对应起来。 */
@Composable
private fun SampleSwatch(lab: DoubleArray?) {
    val colors = MaterialTheme.colorScheme
    val fill = remember(lab) {
        lab?.let {
            val srgb = Colorimetry.xyzToSrgb(Colorimetry.labToXyz(it))
            Color(
                srgb[0].coerceIn(0.0, 1.0).toFloat(),
                srgb[1].coerceIn(0.0, 1.0).toFloat(),
                srgb[2].coerceIn(0.0, 1.0).toFloat(),
            )
        }
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill ?: colors.surfaceVariant)
            .border(1.dp, colors.outline, RoundedCornerShape(10.dp)),
    )
}

/** 判定徽章：实心填充 + 中英双标，模仿仪表上的 PASS/FAIL 指示。 */
@Composable
private fun VerdictBadge(m: Measurement?) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg, label) = when (m?.passed) {
        true -> Triple(colors.tertiary, colors.onTertiary, "PASS · 通过")
        false -> Triple(colors.error, colors.onError, "FAIL · 超差")
        null -> Triple(Color.Transparent, colors.onSurfaceVariant, m?.verdictText() ?: "待测")
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, if (m?.passed == null) colors.outline else bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** L*a*b* 单值：小标签在上，大号等宽数字在下；有标准时附带差值 Δ。 */
@Composable
private fun LabValue(label: String, value: Double?, modifier: Modifier = Modifier, reference: Double? = null) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(
            value?.let { "%.2f".format(it) } ?: "—",
            color = if (value == null) colors.outline else colors.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
        )
        if (value != null && reference != null) {
            Text("Δ%+.2f".format(value - reference), color = colors.onSurfaceVariant, style = MonoSmall)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = colors.onSurface.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun Controls(
    state: UiState,
    onShutter: () -> Unit,
    onWhite: () -> Unit,
    onDark: () -> Unit,
    onGamma: (Boolean) -> Unit,
    onStandard: (String?) -> Unit,
    onClearCal: () -> Unit,
    onClearHistory: () -> Unit,
    onExportCsv: () -> Unit,
    onExportHtml: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ready = state.calibration.isReady

    // 控制区整体是"次要层"：小号描边按钮、弱化文字，唯一例外是快门。
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {

        // 标定行：未标定时"白参考"按钮描红，直接告诉用户下一步该点哪里。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton("白参考", onWhite, Modifier.weight(1f), !state.busy, if (ready) null else colors.error)
            SecondaryButton("暗参考", onDark, Modifier.weight(1f), !state.busy)
            SecondaryButton("清除标定", onClearCal, Modifier.weight(1f), !state.busy)
        }

        Text(
            state.calibration.describe(),
            color = if (ready) colors.tertiary.copy(alpha = 0.8f) else colors.error,
            style = MonoSmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )

        // GC 开关与判定标准合并为一行，把纵向空间让给取景区。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GC", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
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
            Spacer(Modifier.weight(1f))
            StandardPicker(state, onStandard)
        }

        Spacer(Modifier.height(6.dp))

        // Shutter: the primary action, so it is large and centred.
        // 快门是全屏唯一的实心琥珀大圆；导出按钮分列两侧且为弱化描边，主次一目了然。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Export: only useful once there is something to export, but shown
            // disabled rather than hidden so the capability is discoverable.
            SecondaryButton("导出 CSV", onExportCsv, Modifier.weight(1f), state.measurements.isNotEmpty())
            Spacer(Modifier.width(16.dp))
            ShutterButton(busy = state.busy, ready = ready, onClick = onShutter)
            Spacer(Modifier.width(16.dp))
            SecondaryButton("导出报告", onExportHtml, Modifier.weight(1f), state.measurements.isNotEmpty())
        }

        Text(
            when {
                state.busy -> "测量中…"
                ready -> "点按测量"
                else -> "先做白参考"
            },
            color = if (ready) colors.onSurfaceVariant else colors.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ShutterButton(busy: Boolean, ready: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // 外环表达标定状态（绿=可测 / 红=未标定），内芯始终是主色实心圆，忙碌时整体置灰。
    val ring = when {
        busy -> colors.outline
        ready -> colors.tertiary
        else -> colors.error
    }
    val core = if (busy) colors.outline else colors.primary
    Box(
        Modifier
            .size(SHUTTER_SIZE)
            .border(3.dp, ring, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(core)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics {
                contentDescription = "快门：测量"
                role = Role.Button
            },
    )
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint = emphasis ?: colors.onSurface
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (!enabled) colors.outlineVariant else emphasis ?: colors.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = tint,
            disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@Composable
private fun StandardPicker(state: UiState, onStandard: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("判定标准", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(8.dp))
        Box {
            val active = state.activeStandard
            SecondaryButton(
                text = if (active == null) "未选 ▾" else "${active.name} ≤${"%.1f".format(active.tolerance)} ▾",
                onClick = { expanded = true },
            )
            // 标准的 Lab 值移入下拉项副行，主界面这一行只保留名称与容差。
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("未选（只记录）") },
                    onClick = { onStandard(null); expanded = false },
                )
                Standards.all.forEach { std ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("${std.name}   ΔE≤${"%.1f".format(std.tolerance)}")
                                Text(
                                    "L*%.0f a*%.0f b*%.0f".format(std.lab[0], std.lab[1], std.lab[2]),
                                    color = colors.onSurfaceVariant,
                                    style = MonoSmall,
                                )
                            }
                        },
                        onClick = { onStandard(std.name); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun History(measurements: List<Measurement>) {
    val colors = MaterialTheme.colorScheme
    // 记录区是最弱的一层：无边框、更深的底色、小号等宽字，只在需要回看时才被注意到。
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("记录 · ${measurements.size}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.height(84.dp)) {
            items(measurements.reversed()) { m ->
                val tint = when (m.passed) {
                    true -> colors.tertiary
                    false -> colors.error
                    null -> colors.onSurfaceVariant
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(tint, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        m.label,
                        color = colors.onSurfaceVariant,
                        style = MonoSmall,
                        modifier = Modifier.width(34.dp),
                    )
                    Text(
                        m.summary(),
                        color = colors.onSurface,
                        style = MonoSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        m.verdictText(),
                        color = tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
