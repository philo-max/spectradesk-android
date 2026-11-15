package com.spectradesk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX 封装：预览 + 实时滤镜 + 拍照 + 录像 + 参数控制。
 *
 * 这个类存在有五个理由：
 *
 * * **快门语义。** `takePicture` 是异步且可重入的；双击快门不能开两次重叠的拍摄。
 *   [capture] 在一次拍摄进行中返回 `null`。
 * * **取消安全。** 被取消的协程仍必须释放 image proxy，否则拍几张之后管线卡死。
 * * **确定性。** 绑定之后不碰自动曝光/自动白平衡：参考白和样品必须在同一组增益下测，
 *   否则那个除法没有意义。
 * * **单条管线。** 滤镜、预览、拍照、录像共享同一个 SurfaceProcessor 输出，所以
 *   取景器里看到的、录进视频的、存成照片的是同一帧。三者不可能不一致。
 * * **参数与测量分离。** 变焦、曝光补偿这些是"观感"控制，测量仍然只读未滤镜的中心区。
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "SpectraDesk"
    }

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /** 实时滤镜处理器。绑定期间非 null，解绑后释放。 */
    private var filterProcessor: GlFilterProcessor? = null

    /** 取景器视图，释放时要摘掉回调。 */
    private var previewSurfaceView: PreviewSurfaceView? = null

    @Volatile
    private var capturing = false

    val isCapturing: Boolean get() = capturing

    /** 当前是否在录像。 */
    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * 绑定预览 + 滤镜 + 拍照 + 录像。
     *
     * 重绑定是允许且预期的（切换镜头、从后台恢复）；旧的会话先解绑，
     * 保证相机不会被打开两次。
     */
    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewSurfaceView,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        filter: LiveFilter = LiveFilter.NONE,
        intensity: Float = 1f,
        grade: ColorGrade = ColorGrade.NONE,
        frameAspect: FrameAspect = FrameAspect.RATIO_16_9,
        videoResolution: VideoResolution = VideoResolution.FHD,
        videoFps: Int = 30,
        ultraWidePhysical: Boolean = false,
        clarity: ClarityMode = ClarityMode.HD,
        videoStabilization: Boolean = true,
    ): CapabilitySnapshot {
        val cameraProvider = awaitProvider()
        provider = cameraProvider
        interop.clear()
        videoNote = null

        val selector = pickSelector(lensFacing)
        Log.i(TAG, "selector: facing=$lensFacing (prefers ultra-wide when present)")

        val targetInfo = runCatching {
            selector.filter(cameraProvider.availableCameraInfos).firstOrNull()
        }.getOrNull()
        val targetId = targetInfo?.let {
            runCatching { androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId }
                .getOrNull()
        }
        val targetZoomMin = targetInfo?.let { zoomRangeOf(it)?.first } ?: 1f
        val physicalUw = if (targetZoomMin >= 0.999f && targetId != null) {
            findUltraWidePhysicalId(targetId)
        } else {
            null
        }
        ultraWidePhysicalId = physicalUw
        val usePhysical = ultraWidePhysical && physicalUw != null
        val streamRanges = targetInfo?.let { runCatching { it.supportedFrameRateRanges }.getOrNull() }
            ?: emptySet()
        val exactFpsSupported = videoFps == 30 || streamRanges.any { it.lower == videoFps && it.upper == videoFps }
        val fpsRange = fixedFpsRange(streamRanges, videoFps)
        if (!exactFpsSupported) {
            val actual = fpsRange?.upper ?: 30
            videoNote = "* 当前设备/镜头不支持 ${videoFps}fps，已自动按本机最高可用 ${actual}fps 运行（换至支持设备后自动解锁）"
        }

        val displayRotation = runCatching {
            previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        }.getOrDefault(android.view.Surface.ROTATION_0)
        Log.i(TAG, "display rotation=$displayRotation")

        // 重绑前必须把上一次的处理器彻底释放。
        //
        // 这是"切前置没反应"的根因：bind() 每次都 new 一个 GlFilterProcessor，
        // 但旧的那个没人管 —— 它自己的 HandlerThread、EGL 上下文、以及挂在
        // 取景器 Surface 上的绘制全都还活着。实测切一次镜头后 ps 里有两个
        // `gl-filter` 线程，两个处理器轮流往**同一个** SurfaceView 上画：
        // 新相机（前置）的帧和旧相机（后置）的最后一帧在抢同一个缓冲区，
        // 视觉上就是画面没变。
        //
        // 顺序很重要：先摘掉取景器回调（否则旧处理器还会收到 surface 事件），
        // 再 release（它会 quitSafely 自己的线程）。
        previewView.onSurfaceAvailable = null
        previewView.onSurfaceSizeChanged = null
        filterProcessor?.let { old ->
            Log.i(TAG, "bind: releasing previous processor")
            suspendCancellableCoroutine { cont -> old.release { cont.resume(Unit) } }
        }
        filterProcessor = null

        // 处理器先建好：预览的 SurfaceProvider 要用它，取景器的 Surface 也要挂上去。
        val processor = GlFilterProcessor().apply {
            this.filter = filter
            this.intensity = intensity
            this.grade = grade
            this.clarity = clarity.strength
            // 前置是镜像的：不翻转的话，用户举右手画面里也举右手，
            // 自拍时会觉得别扭。只翻预览和录像，不翻测量原图。
            this.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        filterProcessor = processor
        previewSurfaceView = previewView

        // 取景器 Surface 就绪/变化时通知处理器。
        // PreviewSurfaceView 会记住当前表面并在注册时补发一次 —— 因为视图往往
        // 先于相机绑定完成布局，surfaceCreated 早于这里执行。
        previewView.onSurfaceAvailable = { surface, w, h ->
            processor.attachPreviewSurface(surface, w, h)
        }
        previewView.onSurfaceSizeChanged = { w, h ->
            processor.updatePreviewSize(w, h)
        }
        // 预览的相机帧交给处理器自己的 SurfaceTexture，由它处理后画到取景器。
        // 不经过 PreviewView 的变换链 —— 那条链会和 SurfaceProcessor 打架。
        //
        // ## 为什么要指定 16:9 的预览分辨率
        //
        // 默认按屏幕比例给，实测拿到 1632×1224（4:3）。取景器是 1176×2067
        // （比例 0.569），4:3 竖过来是 0.75 —— 两者差很多，shader 的填充裁剪
        // 只能把画面横向裁掉 24%：
        //
        //   保留水平视野 75.9%，等效多了 1.32× 变焦
        //
        // 这就是"镜头被拉长了"的原因：不是焦距变了，是取景器在吃画面。
        // 换成 16:9（比例 0.5625）几乎正好等于取景器的 0.5689，裁切量降到
        // 1% 以内，等效放大回到 1.00×。
        //
        // 用 `ResolutionSelector` 而不是 `setTargetResolution`：后者在
        // CameraX 1.4 已废弃，且不参与 resolution 排序策略。
        //
        // 比例来自用户的 [frameAspect] 设置 —— 取景器、预览流、照片三者
        // 必须同比例，否则"框住的"和"拍到的"不是同一块画面。
        val ratioStrategy = when (frameAspect) {
            FrameAspect.RATIO_4_3 ->
                androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            FrameAspect.RATIO_16_9 ->
                androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
        val previewSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratioStrategy)
            .build()

        // 三个 use case 打包成一个本地构建函数：绑定失败时要能用更保守的配置
        // （去掉 fps / 物理镜头）原地重来，而不是整颗相机起不来、取景器黑屏。
        //
        // 典型失败：4K + 60fps + 拍照三路流的组合超出设备保证的 stream
        // combination，CameraX 在 bindToLifecycle 里直接抛 IllegalArgumentException。
        class Built(
            val preview: Preview,
            val capture: ImageCapture,
            val video: VideoCapture<Recorder>,
            val group: UseCaseGroup,
        )

        fun build(fps: Range<Int>?, physicalId: String?): Built {
            val previewUseCase = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .setTargetRotation(displayRotation)
                .also { b ->
                    // 预览和录像要同一个 fps：整个会话只有一个 AE 目标帧率范围，
                    // 两边各要各的，CameraX 只能取交集或者干脆拒绝。
                    fps?.let { b.setTargetFrameRate(it) }
                    physicalId?.let { physicalStream(b, it) }
                }
                .build().also { p ->
                    p.setSurfaceProvider { request -> processor.providePreviewInput(request) }
                }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(98)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(currentTargetRotation)
                // 照片也跟画幅同比例：不指定时 ImageCapture 给传感器最大 4:3，
                // 取景器是 16:9 就会"拍到的比看到的多一条"。
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratioStrategy)
                        .build(),
                )
                .also { b -> physicalId?.let { physicalStream(b, it) } }
                .build()

            // 录像：用户选的档位优先，不支持就往下降（4K → 1080p → 720p …）。
            // 同时对标抖音 CompileVideoCodec 策略，按分辨率和帧率拉高编码目标码率，
            // 防止默认保守码率把 HydraSharp 重构出的高频细节在 H.264 压缩阶段抹平。
            val wantedQuality = videoResolution.toQuality()
            val targetBitrateBps = targetVideoBitrateBps(videoResolution, fps?.upper ?: 30)
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        wantedQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(wantedQuality),
                    ),
                )
                .setTargetVideoEncodingBitRate(targetBitrateBps)
                .build()
            val video = VideoCapture.Builder(recorder)
                .setTargetRotation(currentTargetRotation)
                .also { b ->
                    fps?.let { b.setTargetFrameRate(it) }
                    physicalId?.let { physicalStream(b, it) }
                }
                .build()

            // effect 只投给 VIDEO_CAPTURE：预览已经由处理器自己画了。
            val group = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(capture)
                .addUseCase(video)
                .addEffect(VideoFilterEffect(processor))
                .build()
            return Built(previewUseCase, capture, video, group)
        }

        // 从最贴近用户选择的配置开始，逐级退让。每一级失败都留下说明，
        // 最终面板上显示的是"实际在用什么"，而不是"用户点了什么"。
        val attempts = buildList {
            add(fpsRange to (if (usePhysical) physicalUw else null))
            if (usePhysical) add(fpsRange to null)
            if (fpsRange != null) add(null to null)
        }.distinct()

        cameraProvider.unbindAll()
        var built: Built? = null
        var lastError: Throwable? = null
        for ((fps, phys) in attempts) {
            val candidate = build(fps, phys)
            try {
                camera = cameraProvider.bindToLifecycle(owner, selector, candidate.group)
                built = candidate
                if (usePhysical && phys == null) {
                    ultraWidePhysicalActive = false
                    videoNote = "超广角物理镜头无法与当前录像档位同时使用，已回到主摄"
                } else {
                    ultraWidePhysicalActive = phys != null
                }
                if (fpsRange != null && fps == null) {
                    videoNote = "${videoFps}fps 与 ${videoResolution.label} 组合不被支持，已退回默认帧率"
                }
                appliedFps = fps?.upper
                appliedResolution = videoResolution
                break
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "bind failed with fps=$fps physical=$phys, falling back", e)
                lastError = e
                cameraProvider.unbindAll()
            }
        }
        val ok = built ?: throw (lastError ?: IllegalStateException("bind failed"))

        preview = ok.preview
        imageCapture = ok.capture
        videoCapture = ok.video

        // 告诉渲染器预览需要补多少旋转。
        //
        // 预览没有 SurfaceOutput（我们自己当 SurfaceProvider），拿不到
        // CameraX 的权威变换，只能用 Camera2 的标准公式自己算：
        //   后置：(sensorOrientation - displayDegrees + 360) % 360
        //   前置：(sensorOrientation + displayDegrees) % 360，再镜像
        //
        // 传感器方向从 Camera2 特性读；显示方向来自 display.rotation。
        val sensorOrientation = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(camera!!.cameraInfo)
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION,
                )
        }.getOrNull() ?: 90
        val displayDegrees = when (displayRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        processor.configurePreviewOrientation(
            sensorOrientation = sensorOrientation,
            displayDegrees = displayDegrees,
            frontFacing = lensFacing == CameraSelector.LENS_FACING_FRONT,
        )

        // 下发 Camera2 底层 ISP 协同：3DNR 硬件快速降噪 + 紫边色散校正 + EIS/OIS 视频防抖
        applyIspAndStabilization(videoStabilization)

        // 绑定完成、camera 已赋值，此时取能力才是真的。
        return capabilitySnapshot()
    }

    /**
     * 更新实时滤镜。
     *
     * 只写渲染器的两个 volatile 字段，不重建管线 —— 所以切换滤镜是即时的，
     * 而且录像中途切换也不会中断录制。
     */
    fun setLiveFilter(filter: LiveFilter, intensity: Float = 1f) {
        filterProcessor?.let {
            it.filter = filter
            it.intensity = intensity.coerceIn(0f, 1f)
        }
    }

    /**
     * 更新算法画质增强（标准 / 高清 CAS / 超清多尺度重构）。
     *
     * 直接写 GL 渲染器的 volatile 强度字段，预览与录像下一帧即刻生效，不中断录制。
     */
    fun setClarity(mode: ClarityMode) {
        filterProcessor?.clarity = mode.strength.coerceIn(0f, 1.5f)
    }

    /**
     * 切换硬件 EIS/OIS 视频防抖与 Camera2 底层 3DNR + 色散校正。
     */
    suspend fun setVideoStabilization(enabled: Boolean) {
        applyIspAndStabilization(enabled)
    }

    @Volatile
    private var stabilizationActive: Boolean = false

    private suspend fun applyIspAndStabilization(stabilizationWanted: Boolean) {
        val info = camera?.cameraInfo ?: return
        val c2 = runCatching { androidx.camera.camera2.interop.Camera2CameraInfo.from(info) }.getOrNull()
            ?: return

        // 1. 硬件零延迟 3DNR 降噪（NOISE_REDUCTION_MODE_FAST 不会像 HIGH_QUALITY 那样掉帧）
        val nrModes = runCatching {
            c2.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            )
        }.getOrNull()
        if (nrModes != null && CameraMetadata.NOISE_REDUCTION_MODE_FAST in nrModes) {
            interop[CaptureRequest.NOISE_REDUCTION_MODE] = CameraMetadata.NOISE_REDUCTION_MODE_FAST
        }

        // 2. 镜头边缘色散（紫边/绿边）硬件快速校正
        val abModes = runCatching {
            c2.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            )
        }.getOrNull()
        if (abModes != null && CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST in abModes) {
            interop[CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE] =
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST
        }

        // 3. 视频防抖（优先开启 OIS 光学防抖 + EIS 电子视频防抖，减少手持抖动导致的编码运动模糊）
        val oisModes = runCatching {
            c2.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
            )
        }.getOrNull()
        val eisModes = runCatching {
            c2.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
            )
        }.getOrNull()

        var anyStab = false
        if (stabilizationWanted) {
            if (oisModes != null && CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes) {
                interop[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                anyStab = true
            }
            if (eisModes != null && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes) {
                interop[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                anyStab = true
            }
        } else {
            if (oisModes != null && CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in oisModes) {
                interop[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
            if (eisModes != null && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF in eisModes) {
                interop[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
        }
        stabilizationActive = anyStab
        pushInterop()
    }

    /**
     * 对标抖音 `CompileVideoCodec`：根据分辨率与帧率动态计算高保真目标编码码率（bps）。
     *
     * 默认 CameraX 码率偏保守（1080p 仅约 10–12Mbps），运动画面或经过 HydraSharp 锐化后的
     * 丰富高频纹理容易在 H.264 编码器里被量化抹平。拉高目标码率后（CameraX 会自动夹在
     * 硬件编码器支持的 `bitrateRange` 内），录出来的 MP4 成片细节与预览框一致。
     */
    private fun targetVideoBitrateBps(res: VideoResolution, fps: Int): Int {
        val baseMbps = when (res) {
            VideoResolution.HD -> 14
            VideoResolution.FHD -> 24
            VideoResolution.QHD -> 42
            VideoResolution.UHD -> 64
            VideoResolution.UHD_8K -> 96
        }
        val fpsFactor = if (fps >= 60) 1.45 else 1.0
        return (baseMbps * fpsFactor * 1_000_000).toInt()
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    @Volatile
    private var currentFlashMode: FlashMode = FlashMode.OFF

    /**
     * 应用闪光灯 / 补光模式（对标系统相机与抖音补光逻辑）：
     * - 后置有物理闪光灯时：
     *   - `OFF`：关闭手电筒 + `FLASH_MODE_OFF`
     *   - `AUTO`：关闭手电筒 + `FLASH_MODE_AUTO`（暗光快门自动闪光）
     *   - `ON`：拍照时走 `FLASH_MODE_ON`（预闪测光+主闪），录像期间自动切为 LED 常亮补光
     *   - `TORCH`：取景、拍照、录像全程开启 LED 常亮手电筒补光
     * - 前置无物理闪光灯时：由 UI 层渲染 `FrontDisplayFlash` 屏幕柔光环补光。
     */
    fun applyFlashMode(mode: FlashMode) {
        currentFlashMode = mode
        val c = camera ?: return
        val hasHwFlash = runCatching { c.cameraInfo.hasFlashUnit() }.getOrDefault(false)
        if (!hasHwFlash) return
        val needTorch = mode == FlashMode.TORCH || (isRecording && mode == FlashMode.ON)
        runCatching { c.cameraControl.enableTorch(needTorch) }
        imageCapture?.flashMode = when {
            needTorch -> ImageCapture.FLASH_MODE_OFF
            mode == FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            mode == FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // ------------------------------------------------------------ 参数控制

    /** 变焦倍率。范围由设备的 [zoomRange] 决定，**可能是 0.5/0.6 而不是 1.0**。 */
    suspend fun setZoomRatio(ratio: Float) {
        val c = camera ?: return
        val (lo, hi) = zoomRange()
        // 不 await：双指缩放每帧都会调，等上一次落地再发下一次只会让手感发黏。
        // CameraX 自己会合并未完成的 zoom 请求（旧的 future 以取消结束）。
        runCatching { c.cameraControl.setZoomRatio(ratio.coerceIn(lo, hi)) }
            .onFailure { Log.w(TAG, "setZoomRatio($ratio) failed", it) }
    }

    /** 当前变焦范围，供 UI 设置滑块上下界。 */
    fun zoomRange(): Pair<Float, Float> {
        val info = camera?.cameraInfo ?: return 1f to 1f
        val state = info.zoomState.value
        if (state != null && state.maxZoomRatio > state.minZoomRatio) {
            return state.minZoomRatio to state.maxZoomRatio
        }
        // zoomState 在绑定后第一帧之前可能还是默认的 1..1 —— 这时退回静态特性，
        // 否则 UI 会把"还没就绪"误报成"不支持变焦"。
        return zoomRangeOf(info) ?: (1f to 1f)
    }

    // ------------------------------------------------------------ Camera2 覆盖层

    /**
     * 所有通过 Camera2Interop 下发的请求项，**合并后一次性下发**。
     *
     * ## 为什么必须合并
     *
     * `Camera2CameraControl.setCaptureRequestOptions()` 是**整体替换**，不是
     * 合并。之前手动对焦、AE/AWB 锁、手动曝光、超广角各自 new 一个 Builder 下发，
     * 后发的会把先发的整个抹掉 —— 开着手动对焦去锁曝光，对焦就悄悄回到自动了。
     * `clearCaptureRequestOptions()` 更狠，一下清空所有人的设置。
     *
     * 现在每个功能只改这张表里自己的 key，然后 [pushInterop] 把整张表发出去。
     */
    private val interop = LinkedHashMap<CaptureRequest.Key<*>, Any>()

    private suspend fun pushInterop(): Boolean {
        val c = camera ?: return false
        val b = CaptureRequestOptions.Builder()
        for ((k, v) in interop) {
            @Suppress("UNCHECKED_CAST")
            b.setCaptureRequestOption(k as CaptureRequest.Key<Any>, v)
        }
        return try {
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(b.build()).await()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 被下一次 push 顶掉时 future 会以 OperationCanceledException 结束，
            // 这不是错误：新的那组设置已经在路上了。
            Log.w(TAG, "pushInterop failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 把 ListenableFuture 变成挂起调用。
     *
     * 之前到处是 `future.get()` —— 而调用方是 Compose 的 Main 协程，
     * 于是点按对焦期间**主线程被阻塞**直到对焦结束（最长 3–5 秒），
     * 表现为点完取景器后界面卡一下。挂起等待不占线程。
     */
    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        val v = get()
                        if (cont.isActive) cont.resume(v)
                    } catch (e: java.util.concurrent.ExecutionException) {
                        if (cont.isActive) cont.resumeWithException(e.cause ?: e)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                },
                mainExecutor,
            )
            cont.invokeOnCancellation { cancel(false) }
        }

    // ------------------------------------------------------------ 超广角

    /** 当前逻辑相机下可用的超广角物理子相机；逻辑相机自己能 <1× 时恒为 null。 */
    @Volatile
    var ultraWidePhysicalId: String? = null
        private set

    /** 本次绑定是否真的在用超广角物理镜头出图（可能因组合不支持被降级）。 */
    @Volatile
    var ultraWidePhysicalActive: Boolean = false
        private set

    /** 本次绑定实际下发的固定帧率；null = 设备默认。 */
    @Volatile
    private var appliedFps: Int? = null

    /** 本次绑定请求的录像分辨率档位。 */
    @Volatile
    private var appliedResolution: VideoResolution = VideoResolution.FHD

    /** 绑定过程中的降级说明，展示在录像页。 */
    @Volatile
    private var videoNote: String? = null

    /**
     * 在 [logicalId] 的物理子相机里找超广角。
     *
     * ## 怎么判断"广"
     *
     * 不能只比焦距毫米数：超广角往往配更小的传感器，1.8mm 配 1/4" 和
     * 5.4mm 配 1/1.5" 的视野差距不是 3 倍。视野由 **焦距 / 传感器对角线**
     * 决定（等效焦距正比于它），所以比的是这个比值。
     *
     * 只有比主摄明显更广（< 0.8 倍）才算 —— 有的机型物理 ID 里是长焦或
     * 同焦段的黑白/景深镜头，把它们当超广角切过去只会让用户困惑。
     */
    private fun findUltraWidePhysicalId(logicalId: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        }.getOrNull() ?: return null
        val logical = runCatching { manager.getCameraCharacteristics(logicalId) }.getOrNull()
            ?: return null
        val physical = runCatching { logical.physicalCameraIds }.getOrNull().orEmpty()
        if (physical.isEmpty()) return null

        fun fovIndex(c: android.hardware.camera2.CameraCharacteristics): Float? {
            val f = c.get(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            )?.firstOrNull() ?: return null
            val s = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return null
            val diag = kotlin.math.hypot(s.width, s.height)
            return if (diag > 0f) f / diag else null
        }

        val reference = fovIndex(logical) ?: return null
        var best: Pair<String, Float>? = null
        for (id in physical) {
            val pc = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val idx = fovIndex(pc) ?: continue
            Log.i(TAG, "physical id=$id fovIndex=$idx (logical=$reference)")
            if (idx < reference * 0.8f && (best == null || idx < best.second)) best = id to idx
        }
        Log.i(TAG, "findUltraWidePhysicalId($logicalId) = ${best?.first}")
        return best?.first
    }

    /** 让一个 use case 从指定物理子相机出图（API 28+ 的 OutputConfiguration.setPhysicalCameraId）。 */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun <T> physicalStream(builder: androidx.camera.core.ExtendableBuilder<T>, id: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        androidx.camera.camera2.interop.Camera2Interop.Extender(builder).setPhysicalCameraId(id)
    }

    /**
     * 选一个**固定**帧率范围 `[fps, fps]`。
     *
     * - 如果设备原生支持 `[fps, fps]`，直接下发（30fps 返回 null 走默认，兼顾暗光自动曝光）。
     * - 如果用户滑到了当前设备不支持的高帧率档位（带 `*` 标记，如 90* / 120* / 240*），
     *   自动向下挑选当前设备支持的**最高固定帧率**（例如本机最高支持 60fps 就跑 60fps，
     *   而不是直接跌回 30fps）。
     */
    private fun fixedFpsRange(ranges: Set<Range<Int>>, fps: Int): Range<Int>? {
        if (fps == 30) return null
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        val fallback = ranges
            .filter { it.lower == it.upper && it.upper <= fps }
            .maxByOrNull { it.upper }
        return if (fallback != null && fallback.upper != 30) fallback else null
    }

    /** 设备在当前相机/镜头会话里原生支持的固定帧率档位。 */
    private fun supportedFps(info: androidx.camera.core.CameraInfo): Set<Int> {
        val ranges = runCatching { info.supportedFrameRateRanges }.getOrNull().orEmpty()
        return VIDEO_FPS_TIERS.filter { fps ->
            fps == 30 || ranges.any { it.lower == fps && it.upper == fps }
        }.toSet()
    }

    /**
     * 只在**高速慢动作会话**（Constrained High-Speed）里才有的帧率。
     */
    private fun highSpeedOnlyFps(info: androidx.camera.core.CameraInfo, regular: Set<Int>): Set<Int> {
        val map = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info).getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            )
        }.getOrNull() ?: return emptySet()
        val hs = runCatching { map.highSpeedVideoFpsRanges }.getOrNull().orEmpty()
        return VIDEO_FPS_TIERS.filter { fps -> fps !in regular && hs.any { it.upper >= fps } }.toSet()
    }

    /**
     * 动态探测当前设备/镜头硬件原生支持的分辨率档位（720p ~ 8K）。
     *
     * 同时核对 CameraX `Recorder.getVideoCapabilities` 与底层 Camera2
     * `SCALER_STREAM_CONFIGURATION_MAP` 的 `MediaRecorder` 输出尺寸：
     * - 换到支持 8K (7680×4320) 的旗舰机型时，8K 自动归入支持集合（无星号）；
     * - 在最高支持 4K 的机型上，720p/1080p/2K/4K 无星号，8K 自动带星号；
     * - 在最高支持 1080p 的前置镜头上，2K/4K/8K 自动带星号。
     */
    private fun supportedResolutions(info: androidx.camera.core.CameraInfo): Set<VideoResolution> {
        val sdrQualities = runCatching {
            Recorder.getVideoCapabilities(info)
                .getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
                .toSet()
        }.getOrNull().orEmpty()

        val map = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info).getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            )
        }.getOrNull()
        val recorderSizes = buildList {
            map?.let { m ->
                runCatching { m.getOutputSizes(android.media.MediaRecorder::class.java) }
                    .getOrNull()?.let { addAll(it) }
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    runCatching { m.getHighResolutionOutputSizes(android.graphics.ImageFormat.PRIVATE) }
                        .getOrNull()?.let { addAll(it) }
                }
            }
        }
        fun hasHardwareSize(w: Int, h: Int): Boolean =
            recorderSizes.any { sz ->
                val longSide = maxOf(sz.width, sz.height)
                val shortSide = minOf(sz.width, sz.height)
                longSide >= w && shortSide >= h
            }

        return buildSet {
            if (Quality.HD in sdrQualities) add(VideoResolution.HD)
            if (Quality.FHD in sdrQualities) add(VideoResolution.FHD)
            if (Quality.UHD in sdrQualities) {
                if (hasHardwareSize(2560, 1440)) add(VideoResolution.QHD)
                add(VideoResolution.UHD)
                if (hasHardwareSize(7680, 4320)) add(VideoResolution.UHD_8K)
            }
        }
    }

    fun capabilitySnapshot(): CapabilitySnapshot {
        val zoom = zoomRange()
        val exp = exposureRange()
        val step = exposureStepEv()
        val info = camera?.cameraInfo
        val qualities = info?.let { supportedResolutions(it) }.orEmpty()
        val fps = info?.let { supportedFps(it) } ?: setOf(30)
        val hsOnly = info?.let { highSpeedOnlyFps(it, fps) }.orEmpty()
        val effectiveRes = when {
            appliedResolution in qualities -> appliedResolution
            VideoResolution.UHD in qualities -> VideoResolution.UHD
            VideoResolution.FHD in qualities -> VideoResolution.FHD
            VideoResolution.HD in qualities -> VideoResolution.HD
            else -> appliedResolution
        }
        val resNote = if (qualities.isNotEmpty() && appliedResolution !in qualities) {
            "* 当前镜头硬件最高支持 ${effectiveRes.label} (${effectiveRes.width}×${effectiveRes.height})，${appliedResolution.label}* 已通过超清算法增强渲染"
        } else {
            null
        }
        val combinedNote = listOfNotNull(resNote, videoNote).joinToString("\n").ifEmpty { null }
        val videoSizeText = buildString {
            append("${effectiveRes.width}×${effectiveRes.height} (${effectiveRes.label})")
            if (appliedResolution != effectiveRes) {
                append(" → 算法${appliedResolution.label}")
            }
        }
        val uwMode = when {
            zoom.first < 0.999f -> UltraWideMode.ZOOM
            ultraWidePhysicalId != null -> UltraWideMode.PHYSICAL
            else -> UltraWideMode.NONE
        }
        val actualFps = appliedFps ?: 30
        val bitrateMbps = targetVideoBitrateBps(effectiveRes, actualFps) / 1_000_000
        val hasFlash = info?.let { runCatching { it.hasFlashUnit() }.getOrDefault(false) } ?: false
        val snapshot = CapabilitySnapshot(
            zoomMin = zoom.first,
            zoomMax = zoom.second,
            exposureRange = exp,
            cameraId = info?.let {
                runCatching { androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId }
                    .getOrNull()
            },
            hasUltraWide = zoom.first < 0.999f,
            exposureStepEv = step,
            ultraWideBlocked = ultraWideUnavailable,
            ultraWideAvailable = uwMode != UltraWideMode.NONE,
            ultraWideMode = uwMode,
            ultraWidePhysicalActive = ultraWidePhysicalActive,
            minFocusDiopters = minFocusDiopters(),
            videoQualities = qualities,
            videoFpsSupported = fps,
            videoFpsHighSpeedOnly = hsOnly,
            videoActualSize = videoSizeText,
            videoAppliedFps = actualFps,
            videoBitrateMbps = bitrateMbps,
            stabilizationActive = stabilizationActive,
            hasFlashUnit = hasFlash,
            videoNote = combinedNote,
        )
        Log.i(TAG, "capabilities: $snapshot")
        return snapshot
    }

    /**
     * 设备每档曝光补偿的 EV 数。
     *
     * CameraX 的 `exposureState` 只给整数索引范围，不给每档多少 EV ——
     * 各机型并不统一：常见 1/3 EV，也有 1/2、1/4、1/6。
     */
    fun exposureStepEv(): Double {
        val info = camera?.cameraInfo ?: return 1.0 / 3.0
        val step: android.util.Rational = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                )
        }.getOrNull() ?: return 1.0 / 3.0
        val v = step.toDouble()
        return if (v > 0.0 && v < 10.0) v else 1.0 / 3.0
    }

    /** 手动调色。只写渲染器字段，不重建管线，所以拖滑杆是实时的。 */
    fun setColorGrade(grade: ColorGrade) {
        filterProcessor?.grade = grade
    }

    /**
     * 曝光补偿。单位是"档"，每档 EV 数由设备决定（见 [exposureStepEv]）。
     *
     * 这是唯一一个既影响观感、又影响测量读数的参数 —— 改变它会清空标定。
     */
    suspend fun setExposureCompensation(index: Int): Boolean {
        val c = camera ?: return false
        val info = c.cameraInfo.exposureState
        if (!info.isExposureCompensationSupported) return false
        val clamped = index.coerceIn(
            info.exposureCompensationRange.lower,
            info.exposureCompensationRange.upper,
        )
        return runCatching { c.cameraControl.setExposureCompensationIndex(clamped).await() }
            .isSuccess
    }

    fun exposureRange(): IntRange {
        val info = camera?.cameraInfo?.exposureState ?: return 0..0
        if (!info.isExposureCompensationSupported) return 0..0
        return info.exposureCompensationRange.lower..info.exposureCompensationRange.upper
    }

    // ------------------------------------------------------------ 对焦

    /**
     * 点按对焦 / 测光（归一化坐标，自绘取景器用）。
     *
     * `DisplayOrientedMeteringPointFactory` 按 Display 旋转和相机朝向把 view
     * 坐标换算到传感器坐标，不需要 PreviewView。
     *
     * @param lock 长按语义：对焦完成后**不自动取消**，并锁住 AE ——
     *   构图时把主体对好、再移开镜头，曝光和焦点都不跟着跑。
     *   普通点按会先解除之前的锁。
     */
    suspend fun focusAtPoint(
        viewW: Int,
        viewH: Int,
        nx: Float,
        ny: Float,
        lock: Boolean = false,
    ): Boolean {
        val c = camera ?: return false
        if (viewW <= 0 || viewH <= 0) return false
        val display = previewSurfaceView?.display ?: return false

        // 先退出手动对焦并解除旧的 AE 锁：AF_MODE_OFF 盖在上面时，
        // CameraX 发出的 AF trigger 会被 HAL 无视，点了等于没点。
        var dirty = false
        if (interop.remove(CaptureRequest.CONTROL_AF_MODE) != null) dirty = true
        if (interop.remove(CaptureRequest.LENS_FOCUS_DISTANCE) != null) dirty = true
        if (interop.remove(CaptureRequest.CONTROL_AE_LOCK) != null) dirty = true
        if (dirty) pushInterop()

        val factory = androidx.camera.core.DisplayOrientedMeteringPointFactory(
            display,
            c.cameraInfo,
            viewW.toFloat(),
            viewH.toFloat(),
        )
        val point = factory.createPoint(
            nx.coerceIn(0f, 1f) * viewW,
            ny.coerceIn(0f, 1f) * viewH,
        )
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).apply {
            if (lock) disableAutoCancel()
            else setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
        }.build()
        val ok = try {
            c.cameraControl.startFocusAndMetering(action).await().isFocusSuccessful
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 连点时上一次会以 OperationCanceledException 结束 —— 正常现象。
            Log.d(TAG, "focus interrupted: ${e.javaClass.simpleName}")
            false
        }
        if (lock) {
            // 测光区域已经按点按位置收敛完了，这时锁 AE 才锁在"对的"亮度上。
            interop[CaptureRequest.CONTROL_AE_LOCK] = true
            pushInterop()
        }
        return ok
    }

    /**
     * 手动对焦（屈光度：0 = 无穷远，越大越近；传 null 交回自动对焦）。
     *
     * ## 单位修正
     *
     * `LENS_FOCUS_DISTANCE` 的单位**本来就是屈光度**（1/米），和
     * `LENS_INFO_MINIMUM_FOCUS_DISTANCE` 同一个单位。之前这里先换算成米
     * 再下发：想对 0.5m（2 屈光度）实际发了 0.5 屈光度 = 2m，
     * 滑杆越往"近"拖焦点反而越远。现在原样下发。
     */
    suspend fun setManualFocus(diopters: Float?): Boolean {
        val c = camera ?: return false
        if (diopters == null) {
            interop.remove(CaptureRequest.CONTROL_AF_MODE)
            interop.remove(CaptureRequest.LENS_FOCUS_DISTANCE)
        } else {
            // 进入手动前取消正在进行的点按对焦，否则它的 AF trigger 会和
            // AF_MODE_OFF 打架，镜头来回拉一下。
            runCatching { c.cameraControl.cancelFocusAndMetering() }
            val maxD = minFocusDiopters().takeIf { it > 0f } ?: diopters
            interop[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_OFF
            interop[CaptureRequest.LENS_FOCUS_DISTANCE] = diopters.coerceIn(0f, maxD)
        }
        return pushInterop()
    }

    /** 最近对焦距离（屈光度上界），用于手动对焦滑杆的量程；0 = 定焦不支持手动。 */
    fun minFocusDiopters(): Float {
        val info = camera?.cameraInfo ?: return 0f
        val d: Float = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                )
        }.getOrNull() ?: return 0f
        return d
    }

    // ------------------------------------------------------------ 曝光锁 / 手动曝光

    /**
     * 锁定或解锁自动曝光与自动白平衡。
     *
     * 测量重复性的关键：AE/AWB 自由运行时，白参考和样品之间曝光与通道增益
     * 都会变，那个除法就在比较两台不同的相机。
     */
    suspend fun setAutoLock(locked: Boolean): Boolean {
        if (locked) {
            interop[CaptureRequest.CONTROL_AE_LOCK] = true
            interop[CaptureRequest.CONTROL_AWB_LOCK] = true
        } else {
            interop.remove(CaptureRequest.CONTROL_AE_LOCK)
            interop.remove(CaptureRequest.CONTROL_AWB_LOCK)
        }
        val ok = pushInterop()
        Log.d(TAG, "AE/AWB lock set to $locked ok=$ok")
        return ok
    }

    /** 手动 ISO / 快门。传 null 表示交回自动。 */
    suspend fun setManualExposure(iso: Int?, exposureNs: Long?): Boolean {
        if (iso != null) {
            interop[CaptureRequest.SENSOR_SENSITIVITY] = iso
            interop[CaptureRequest.CONTROL_AE_MODE] = CameraMetadata.CONTROL_AE_MODE_OFF
        } else {
            interop.remove(CaptureRequest.SENSOR_SENSITIVITY)
            interop.remove(CaptureRequest.CONTROL_AE_MODE)
        }
        if (exposureNs != null) interop[CaptureRequest.SENSOR_EXPOSURE_TIME] = exposureNs
        else interop.remove(CaptureRequest.SENSOR_EXPOSURE_TIME)
        return pushInterop()
    }

    /** 设备支持的 ISO 范围；不支持手动时返回 null。 */
    fun isoRange(): IntRange? {
        val info = camera?.cameraInfo ?: return null
        val range: Range<Int> = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                )
        }.getOrNull() ?: return null
        return range.lower..range.upper
    }

    /** 设备支持的快门范围（纳秒）；不支持时返回 null。 */
    fun exposureTimeRange(): LongRange? {
        val info = camera?.cameraInfo ?: return null
        val range: Range<Long> = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                .getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                )
        }.getOrNull() ?: return null
        return range.lower..range.upper
    }

    // ------------------------------------------------------------ 录像

    /**
     * 开始录像。
     *
     * 音频在没授权时静默降级为无声录制，而不是让整个录像失败 ——
     * 用户想要的是视频，声音是加分项。
     */
    fun startRecording(
        withAudio: Boolean,
        onEvent: (VideoRecordEvent) -> Unit,
    ): Boolean {
        val video = videoCapture ?: return false
        if (isRecording) return false

        val hasAudio = withAudio && ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val name = "LightYear_" + java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US,
        ).format(java.util.Date())

        val pending = video.output.prepareRecording(
            context,
            MediaStoreOutput.mediaStore(context.contentResolver, name),
        )
        val prepared = if (hasAudio) pending.withAudioEnabled() else pending

        activeRecording = prepared.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                isRecording = false
                activeRecording = null
                applyFlashMode(currentFlashMode)
            }
            onEvent(event)
        }
        isRecording = true
        applyFlashMode(currentFlashMode)
        Log.i(TAG, "recording started audio=$hasAudio")
        return true
    }

    fun stopRecording() {
        activeRecording?.let {
            runCatching { it.stop() }
            activeRecording = null
            isRecording = false
            applyFlashMode(currentFlashMode)
        }
    }

    fun toggleRecording(
        withAudio: Boolean,
        onEvent: (VideoRecordEvent) -> Unit,
    ): Boolean {
        return if (isRecording) {
            stopRecording()
            true
        } else {
            startRecording(withAudio, onEvent)
        }
    }

    // ------------------------------------------------------------ 拍照

    /**
     * 等 [PreviewView] 完成一次布局。
     *
     * `getViewPort()` 在视图测量完成前返回 null，而 null 的 ViewPort 会让
     * CameraX 按传感器默认宽高比出图 —— 表现就是预览上下有黑边。
     * 这里等到视图有非零尺寸为止，最多等 [timeoutMs]。
     */
    private suspend fun awaitLaidOut(view: android.view.View, timeoutMs: Long = 2000) {
        if (view.width > 0 && view.height > 0) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (view.width > 0 && view.height > 0) return
            kotlinx.coroutines.delay(16)
        }
        Log.w(TAG, "PreviewView still not laid out after ${timeoutMs}ms")
    }

    /** 当前相机对象，用于 interop 控制。 */
    var camera: androidx.camera.core.Camera? = null
        private set

    /** 最近一张拍到的帧，供滤镜切换后重渲染。 */
    var lastShot: android.graphics.Bitmap? = null
        private set

    /**
     * 对最近一张照片套用滤镜，返回新 bitmap。
     *
     * 实时滤镜已经由 GPU 管线负责取景器，这个 CPU 版本保留是因为：
     * 报告缩略图、离线复算、以及 GL 上下文不可用时的兜底，都需要一个
     * 可在 JVM 上单测的等价实现。
     */
    fun renderFiltered(kind: LiveFilter): android.graphics.Bitmap? {
        val source = lastShot ?: return null
        if (kind == LiveFilter.NONE) return null
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val lin = doubleArrayOf(
                Colorimetry.srgbToLinear(r),
                Colorimetry.srgbToLinear(g),
                Colorimetry.srgbToLinear(b),
            )
            val out = LiveFilters.apply(kind, lin)
            val rr = (Colorimetry.srgbEncode(out[0]) * 255).toInt().coerceIn(0, 255)
            val gg = (Colorimetry.srgbEncode(out[1]) * 255).toInt().coerceIn(0, 255)
            val bb = (Colorimetry.srgbEncode(out[2]) * 255).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        val outBmp = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888,
        )
        outBmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return outBmp
    }

    /**
     * 拍一张并存成图库照片，同时解码用于测量。
     *
     * 测量读的是 **ImageCapture 的原始输出**，不是滤镜后的画面 —— 这是硬边界：
     * 滤镜是观察辅助，不能进数字。滤镜管线只写屏幕和视频。
     */
    suspend fun capture(gammaCorrected: Boolean): Capture? {
        val capture = imageCapture ?: throw IllegalStateException("相机未就绪")
        if (capturing) return null
        capturing = true
        try {
            Log.d(TAG, "takePicture requested")
            val proxy = takePicture(capture)
            try {
                Log.d(TAG, "decoding ${proxy.width}x${proxy.height}")
                val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val bitmap = FrameDecoder.imageProxyToBitmap(proxy)
                    val mean = FrameDecoder.sampleCentrePatch(
                        bitmap,
                        FrameDecoder.PATCH_FRACTION,
                        gammaCorrected,
                    )
                    Triple(bitmap, mean, DoubleArray(0))
                }
                val (bitmap, mean, _) = decoded
                lastShot = bitmap
                Log.d(
                    TAG,
                    "decoded ${bitmap.width}x${bitmap.height} " +
                        "mean=${mean.joinToString { "%.4f".format(it) }}",
                )
                return Capture(
                    linearRgb = DoubleArray(0),
                    width = bitmap.width,
                    height = bitmap.height,
                    bitmap = bitmap,
                    timestampMs = System.currentTimeMillis(),
                    gammaCorrected = gammaCorrected,
                    meanLinear = mean,
                )
            } finally {
                proxy.close()
            }
        } finally {
            capturing = false
            Log.d(TAG, "capture finished")
        }
    }

    /** 拍一张直接存到图库（不走测量）。 */
    suspend fun captureToGallery(
        watermark: Watermark = Watermark(),
        location: String? = null,
    ): Boolean {
        val capture = imageCapture ?: return false
        val resolver = context.contentResolver
        val name = "LightYear_" + java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US,
        ).format(java.util.Date())

        // 我们自己 insert 一行并拿到 OutputStream，让 CameraX 往里写字节。
        //
        // 不要用 OutputFileOptions.Builder(resolver, uri, values)：CameraX 内部
        // 还会再 insert 一次（JpegBytes2Disk.copyFileToMediaStore），撞上我们
        // 已插的那一行就抛异常，照片存不下来。
        val target = MediaStoreOutput.openPhotoStream(resolver, name) ?: run {
            Log.e(TAG, "cannot open MediaStore photo target")
            return false
        }

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                // 水印开启时走"内存拿位图 → 合成 → 写流"，否则走原来的直写流。
                //
                // 为什么不在 CameraX 的回调里直接写流：水印需要完整位图才能
                // 排版（要知道画布宽高），而 onImageSaved 给的是已经落盘的
                // 字节流，拿不到尺寸。
                if (watermark.enabled) {
                    capture.takePicture(
                        mainExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    val bmp = FrameDecoder.imageProxyToBitmap(image, maxEdge = 4096)
                                    // 时间在这里取一次，预览和成片共用 —— 否则
                                    // 会出现"预览 12:30、成片 12:29"的差一秒问题。
                                    val content = WatermarkContent.now(watermark, location)
                                    val stamped = WatermarkRenderer.draw(bmp, watermark, content)
                                    val ok = runCatching {
                                        target.stream.use { s ->
                                            stamped.compress(
                                                android.graphics.Bitmap.CompressFormat.JPEG,
                                                95,
                                                s,
                                            )
                                        }
                                    }.getOrDefault(false)
                                    if (ok) MediaStoreOutput.publishPhoto(resolver, target.uri)
                                    else MediaStoreOutput.deletePhoto(resolver, target.uri)
                                    if (cont.isActive) cont.resume(ok)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "watermark photo failed", t)
                                    runCatching { target.close() }
                                    MediaStoreOutput.deletePhoto(resolver, target.uri)
                                    if (cont.isActive) cont.resume(false)
                                } finally {
                                    image.close()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "capture for watermark failed", exception)
                                runCatching { target.close() }
                                MediaStoreOutput.deletePhoto(resolver, target.uri)
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                    )
                } else {
                    val options = ImageCapture.OutputFileOptions.Builder(target.stream).build()
                    capture.takePicture(
                        options,
                        mainExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                target.close()
                                MediaStoreOutput.publishPhoto(resolver, target.uri)
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "save photo failed", exception)
                                target.close()
                                MediaStoreOutput.deletePhoto(resolver, target.uri)
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureToGallery failed", e)
            runCatching { target.close() }
            MediaStoreOutput.deletePhoto(resolver, target.uri)
            false
        }
    }

    private suspend fun takePicture(capture: ImageCapture): ImageProxy =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        Log.d(TAG, "onCaptureSuccess active=${cont.isActive}")
                        if (cont.isActive) cont.resume(image) else image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "onError code=${exception.imageCaptureError}", exception)
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                },
            )
        }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    /**
     * 在同朝向的相机里挑一颗，优先支持超广角（zoom 下限 < 1）的那颗。
     *
     * ## 为什么需要这个
     *
     * `CameraSelector.Builder().requireLensFacing(BACK)` 返回的是**第一个**
     * 后置相机。现代手机的后置通常是多个逻辑相机并列：
     *
     * * 主摄逻辑相机：zoomRatioRange = [1.0, 10.0]
     * * 超广角逻辑相机：zoomRatioRange = [0.6, 10.0]
     *
     * 用户要"0–1 区间的变焦"，只有后者能给。实测这台 vivo 有 8 个 camera
     * device，其中两颗后置的下限是 0.6 —— 不主动挑就永远拿不到。
     *
     * ## 为什么按 zoom 下限挑，而不是按物理镜头 ID 挑
     *
     * 物理镜头 ID 是厂商私有的，不同机型完全不一样；而 `zoomRatioRange`
     * 是标准 Camera2 特性，且**语义直接对应需求**：下限 0.6 就代表这颗
     * 逻辑相机能拍比"标准视野"更宽的画面。用能力挑比用 ID 挑稳健得多。
     *
     * 拿不到 zoomState 时退回按 facing 选，保证不因为探测失败而没相机可用。
     */
    /**
     * 在同朝向的相机里挑一颗，优先支持超广角（zoom 下限 < 1）的那颗。
     *
     * ## 为什么需要这个
     *
     * `CameraSelector.Builder().requireLensFacing(BACK)` 返回的是**第一个**
     * 后置相机。现代手机的后置通常是多个逻辑相机并列：
     *
     * * 主摄逻辑相机：zoomRatioRange = [1.0, 10.0]
     * * 超广角逻辑相机：zoomRatioRange = [0.6, 10.0]
     *
     * 用户要"0–1 区间的变焦"，只有后者能给。实测这台 vivo 有 8 个 camera
     * device，其中两颗后置的下限是 0.6 —— 不主动挑就永远拿不到。
     *
     * ## 两个关键实现细节
     *
     * 1. **不能只写 `requireLensFacing` 再加 filter**：`requireLensFacing`
     *    会在 filter 之前就把候选集收窄到"第一个匹配朝向的相机"，
     *    filter 里永远只看到 1 个，挑无可挑。所以朝向过滤必须自己写在
     *    filter 里，`requireLensFacing` 只作为兜底保留。
     * 2. **filter 里不能读 `info.zoomState.value`**：那时相机还没绑定，
     *    CameraX 会抛 `UnsupportedOperationException`。改读 Camera2 的
     *    静态特性 `CONTROL_ZOOM_RATIO_RANGE`，它不需要绑定就能查。
     */
    private fun pickSelector(lensFacing: Int): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos ->
                val same = infos.filter { info ->
                    runCatching { info.lensFacing }.getOrNull() == lensFacing
                }
                if (same.isEmpty()) {
                    Log.w(TAG, "pickSelector: no camera for facing=$lensFacing")
                    return@addCameraFilter infos
                }

                // 前置摄像头直接选默认第一颗（内屏真前置 Camera 1）。
                //
                // 千万不能在前置上按 zoomMin < 1 去挑"超广角逻辑相机"：
                // 在折叠屏（如 Moto Razr XT2551-3）上，系统会把后置主摄+超广角
                // 虚拟成一颗 facing=FRONT、zoom=0.5..3.0 的外屏自拍逻辑相机（Camera 7）。
                // 如果按 zoomMin 挑，切前置就会打开机身背面的后置镜头！
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    return@addCameraFilter listOf(same.first())
                }

                // 后置：先试超广角逻辑相机。
                val ultraId = findUltraWideCameraId(lensFacing)
                if (ultraId != null) {
                    val match = same.firstOrNull { info ->
                        runCatching {
                            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                                .cameraId == ultraId
                        }.getOrDefault(false)
                    }
                    if (match != null) {
                        Log.i(TAG, "pickSelector: matched ultra-wide cameraId=$ultraId")
                        return@addCameraFilter listOf(match)
                    }
                    Log.w(
                        TAG,
                        "pickSelector: ultra-wide cameraId=$ultraId not exposed by CameraX " +
                            "(available=${infos.size})",
                    )
                    ultraWideUnavailable = true
                }

                val withZoom = same.mapNotNull { info ->
                    zoomRangeOf(info)?.let { info to it }
                }
                if (withZoom.isEmpty()) return@addCameraFilter listOf(same.first())

                // 下限最小的那颗 = 视野最广的那颗（后置逻辑多摄）。
                val widest = withZoom.minByOrNull { it.second.first }
                    ?: return@addCameraFilter listOf(same.first())
                listOf(widest.first)
            }
            .build()

    /**
     * 设备有超广角相机，但 CameraX 没有把它暴露出来。
     *
     * UI 用这个说明"为什么没有 0.6×"，而不是让用户对着一个 1.0 起的
     * 滑杆猜是功能没做还是设备不支持。
     */
    @Volatile
    var ultraWideUnavailable: Boolean = false
        private set

    /**
     * 从 Camera2 静态特性读 zoom 范围，不需要绑定。
     *
     * `CONTROL_ZOOM_RATIO_RANGE` 是 Android 11 起的关键；老设备退回
     * `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`（下限固定 1.0）。
     */
    private fun zoomRangeOf(info: androidx.camera.core.CameraInfo): Pair<Float, Float>? {
        val c2 = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
        }.getOrNull() ?: return null
        val range: android.util.Range<Float>? = runCatching {
            c2.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE,
            )
        }.getOrNull()
        if (range != null) return range.lower to range.upper
        val maxZoom: Float? = runCatching {
            c2.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
            )
        }.getOrNull()
        return if (maxZoom != null) 1f to maxZoom else null
    }

    /**
     * 这台设备的**超广角逻辑相机**的 cameraId，没有则 null。
     *
     * ## 为什么需要绕开 CameraX 的枚举
     *
     * 实测这台 vivo（V2352A / Android 16）有 8 个 camera device，其中
     * 一颗后置逻辑相机的 `zoomRatioRange` 是 **0.6–10.0**，其
     * `logicalMultiCamera.physicalIds = [3, 0]`（超广角 + 主摄）。
     *
     * 但 `ProcessCameraProvider.getAvailableCameraInfos()` 只返回 **2** 个
     * （主摄 + 前置），那颗 0.6 起的相机被 CameraX 内部去重掉了。
     * 于是不管怎么在 `CameraSelector` 里挑，`zoomState.minZoomRatio`
     * 永远是 1.0 —— 用户要的"0–1 区间变焦"在 CameraX 层根本拿不到。
     *
     * 所以这里直接用 `CameraManager` 枚举全部 cameraId，找出 zoom 下限
     * 小于 1 的那颗，用 `Camera2CameraInfo.getCameraId()` 在
     * `CameraFilter` 里精确匹配它。
     *
     * 找不到就返回 null，调用方保持原行为 —— 这条路径是增强，不是依赖。
     */
    private fun findUltraWideCameraId(lensFacing: Int): String? {
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        }.getOrNull() ?: return null
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())

        var best: Pair<String, Float>? = null
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            val expected = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            } else {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
            if (facing != expected) continue

            val range = chars.get(
                android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE,
            ) ?: continue
            val min = range.lower
            Log.i(TAG, "camera id=$id facing=$facing zoom=${range.lower}..${range.upper}")
            if (min < 0.999f && (best == null || min < best!!.second)) {
                best = id to min
            }
        }
        Log.i(TAG, "findUltraWideCameraId: ${best?.first ?: "none"} (min=${best?.second})")
        return best?.first
    }

    /**
     * 设备朝向变化时更新 targetRotation。
     *
     * ## 为什么必须做这一步
     *
     * 横屏时 UI 会重排（控制条移到右侧），但**相机流不会自己转**：
     * `Preview`/`ImageCapture`/`VideoCapture` 的 `targetRotation` 是在
     * `bind()` 时定下的，绑定后不随设备转动更新。结果是横屏下 UI 转过去了、
     * 画面内容还躺在竖屏方向 —— 看起来就是"画面转了 90°"。
     *
     * CameraX 提供 `targetRotation` 的 setter，改它会触发一次内部重配置，
     * **不需要重建 use case**，所以取景器不会黑。这是官方推荐的做法。
     *
     * @return 是否真的改了（用于日志）
     */
    @Volatile
    private var currentTargetRotation: Int = android.view.Surface.ROTATION_0

    /**
     * 设备物理朝向（重力感应）变化时更新 `ImageCapture` 与 `VideoCapture` 的 `targetRotation`。
     *
     * - Activity 窗口锁定为竖屏 (`portrait`)，因此 `Preview` 的 `targetRotation` 始终保持窗口显示方向，
     *   保证旋转手机时取景器不黑屏、不重绑、画面不额外翻转；
     * - `ImageCapture` 和 `VideoCapture` 跟随重力传感器的 `Surface.ROTATION_0 / 90 / 180 / 270`，
     *   保证横屏握持拍出的照片 EXIF 方向和录制的 MP4 视频旋转矩阵都是真正的横屏正向。
     */
    fun updateTargetRotation(rotation: Int): Boolean {
        currentTargetRotation = rotation
        var changed = false
        runCatching {
            if (imageCapture?.targetRotation != rotation) {
                imageCapture?.targetRotation = rotation
                changed = true
            }
            if (videoCapture?.targetRotation != rotation) {
                videoCapture?.targetRotation = rotation
                changed = true
            }
        }.onFailure { Log.w(TAG, "updateTargetRotation($rotation) failed", it) }

        if (changed) Log.i(TAG, "targetRotation (capture/video) -> $rotation")
        return changed
    }

    /**
     * 设备能力快照：变焦范围与曝光补偿范围。
     *
     * 只有在 [bind] 返回之后取才有意义 —— 绑定是协程，`camera` 在其末尾才赋值。
     */
    data class CapabilitySnapshot(
        val zoomMin: Float,
        val zoomMax: Float,
        val exposureRange: IntRange,
        /** 本次绑定实际用的相机 ID，排查"为什么没有超广角"时用。 */
        val cameraId: String? = null,
        /** 这颗镜头是否支持 < 1.0 的变焦（超广角）。 */
        val hasUltraWide: Boolean = false,
        /** 每档曝光补偿的 EV 数（1/3 或 1/10，由设备决定）。 */
        val exposureStepEv: Double = 1.0 / 3.0,
        /** 设备有超广角相机，但 CameraX 没暴露它 —— UI 要说明原因。 */
        val ultraWideBlocked: Boolean = false,
        /** 超广角物理相机可用（能真的切换）。 */
        val ultraWideAvailable: Boolean = false,
        val ultraWideMode: UltraWideMode = UltraWideMode.NONE,
        val ultraWidePhysicalActive: Boolean = false,
        /** 最近对焦距离（屈光度），0 表示不支持手动对焦。 */
        val minFocusDiopters: Float = 0f,
        val videoQualities: Set<VideoResolution> = emptySet(),
        val videoFpsSupported: Set<Int> = setOf(30),
        val videoFpsHighSpeedOnly: Set<Int> = emptySet(),
        val videoActualSize: String? = null,
        val videoAppliedFps: Int = 30,
        val videoBitrateMbps: Int = 24,
        val stabilizationActive: Boolean = false,
        val hasFlashUnit: Boolean = false,
        val videoNote: String? = null,
    )

    enum class UltraWideMode {
        ZOOM,
        PHYSICAL,
        NONE,
    }

    fun release() {
        stopRecording()
        previewSurfaceView?.onSurfaceAvailable = null
        previewSurfaceView?.onSurfaceSizeChanged = null
        previewSurfaceView = null
        filterProcessor?.release()
        filterProcessor = null
        provider?.unbindAll()
        provider = null
        imageCapture = null
        preview = null
        videoCapture = null
        camera = null
    }
}

/**
 * `VideoResolution` ↔ CameraX `Quality` 的映射。
 *
 * ## 为什么放在这里，不放在 CaptureProfile.kt
 *
 * `VideoResolution` 会被 `CameraSettings` 的默认值引用，而 `CameraSettings`
 * 在**单元测试**里会被构造 —— 测试跑在纯 JVM 上，没有 CameraX 的 Android 类。
 *
 * 如果把 `Quality` 放进枚举本身，JVM 初始化那个枚举时就要加载
 * `androidx.camera.video.Quality`，直接 `NoClassDefFoundError`，
 * 一次崩掉 25 个测试（实测）。
 *
 * 所以枚举只存像素高度（纯 Kotlin），`Quality` 的映射留在本文件 ——
 * 这里的代码只在真机上跑，测试永远不会碰到。
 */
private fun VideoResolution.toQuality(): Quality = when (this) {
    VideoResolution.UHD_8K -> Quality.UHD
    VideoResolution.UHD -> Quality.UHD
    VideoResolution.QHD -> Quality.UHD
    VideoResolution.FHD -> Quality.FHD
    VideoResolution.HD -> Quality.HD
}

/**
 * 把 [GlFilterProcessor] 包成只作用于录像的 CameraEffect。
 *
 * ## 为什么只投 VIDEO_CAPTURE
 *
 * 预览已经由处理器自己画（[GlFilterProcessor.providePreviewInput]），
 * 走的是我们自己的 SurfaceTexture + SurfaceView，完全不经过 PreviewView 的
 * 变换链。如果把 PREVIEW 也交给 effect，CameraX 会把预览再接管一次，
 * 我们就没有 Surface 可画了。
 *
 * 只投录像时，effect 给处理器一个录像输出 Surface，处理器用同一套 shader
 * 再画一遍 —— 所以录进视频的画面和取景器里看到的是同一个滤镜结果。
 *
 * ## 为什么不含 IMAGE_CAPTURE
 *
 * 这台机器的本职是测色。测量读的是 ImageCapture 的原始输出，滤镜绝不能进
 * 数字 —— 照片本来就不该带滤镜。滤镜作用于"你看到的"和"你录下的"，
 * 测量走未处理的原图。这条边界是硬的。
 */
private class VideoFilterEffect(processor: GlFilterProcessor) : CameraEffect(
    CameraEffect.VIDEO_CAPTURE,
    CameraEffect.OUTPUT_OPTION_ONE_FOR_ALL_TARGETS,
    CameraEffect.TRANSFORMATION_ARBITRARY,
    Executor { it.run() },
    processor as SurfaceProcessor,
    { t -> Log.e("VideoFilterEffect", "effect error", t) },
)
