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
    ): CapabilitySnapshot {
        val cameraProvider = awaitProvider()
        provider = cameraProvider

        // 选镜头。这里不能只按 facing 选 —— 一台手机同朝向往往有多个逻辑相机：
        // 主摄（1.0–10.0）、超广角（0.6–10.0），甚至人像/长焦。CameraX 的
        // `requireLensFacing` 只会挑"第一个符合的"，实测在这台 vivo 上挑中的是
        // 1.0× 的主摄，于是变焦下限永远是 1.0，用户要的 0–1 区间根本没有。
        //
        // 所以：同朝向里优先挑 zoomRatio 下限最小的那颗 —— 下限 0.6 的那颗
        // 就是带超广角的逻辑相机，它天然覆盖 0.6×–10×。
        val selector = pickSelector(lensFacing)
        Log.i(TAG, "selector: facing=$lensFacing (prefers ultra-wide when present)")

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

        val previewUseCase = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(displayRotation)
            .build().also { preview ->
                preview.setSurfaceProvider { request ->
                    processor.providePreviewInput(request)
                }
            }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(displayRotation)
            // 照片也按 16:9，和取景器一致。
            //
            // 不指定时 ImageCapture 给传感器最大 4:3（实测 4096×3072）。
            // 取景器是 16:9，于是**拍出来的比看到的多出上下一条** ——
            // 用户构图时框住的画面和成片不是一回事，这是最容易被投诉的
            // 那类不一致。同类应用（InstaShot 的 AspectRatioFrameLayout）
            // 都是让取景器和输出同比例。
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratioStrategy)
                    .build(),
            )
            .build()

        // 录像：优先 FHD，不支持就退回 HD/SD。FallbackStrategy 让它自动降级，
        // 而不是在低端设备上直接抛异常。
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val video = VideoCapture.withOutput(recorder).apply {
            targetRotation = displayRotation
        }

        // effect 只投给 VIDEO_CAPTURE：预览已经由处理器自己画了。
        // 这样录像拿到的是同一套 GL 处理结果，所见即所录。
        val effect = VideoFilterEffect(processor)

        val group = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(capture)
            .addUseCase(video)
            .addEffect(effect)
            .build()

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(owner, selector, group)

        preview = previewUseCase
        imageCapture = capture
        videoCapture = video

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

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    // ------------------------------------------------------------ 参数控制

    /** 变焦倍率。范围由设备的 [zoomRange] 决定，**可能是 0.6 而不是 1.0**。 */
    suspend fun setZoomRatio(ratio: Float) {
        val c = camera ?: return
        val (lo, hi) = zoomRange()
        runCatching { c.cameraControl.setZoomRatio(ratio.coerceIn(lo, hi)) }
            .onFailure { Log.w(TAG, "setZoomRatio($ratio) failed", it) }
    }

    /** 当前变焦范围，供 UI 设置滑块上界。 */
    fun zoomRange(): Pair<Float, Float> {
        val state = camera?.cameraInfo?.zoomState?.value
        if (state == null) {
            Log.w(TAG, "zoomRange: no camera or zoomState yet")
            return 1f to 1f
        }
        Log.i(
            TAG,
            "zoomRange: min=${state.minZoomRatio} max=${state.maxZoomRatio} " +
                "current=${state.zoomRatio} linear=${state.linearZoom}",
        )
        return state.minZoomRatio to state.maxZoomRatio
    }

    /**
     * 相机绑定完成后调用，把设备能力一次性报给 UI。
     *
     * 为什么不让 UI 直接调 [zoomRange] / [exposureRange]：那两个方法读的是
     * controller 内部的 `camera` 字段，而它不是 Compose 状态。UI 用
     * `remember(controller, bound)` 缓存结果时，bound 变 true 那一刻 camera
     * 可能还没赋值（绑定是协程，赋值在其末尾），于是拿到 null 就永久缓存了
     * 空范围 —— 实测表现为"变焦显示 1.0×、滑块拖不动"。
     *
     * 现在改成绑定完成后主动取一次，UI 只是被动接收，不存在时序竞态。
     */
    /**
     * 这台设备的超广角物理相机 ID，没有则 null。
     *
     * ## 为什么走 physical camera，而不是找另一颗逻辑相机
     *
     * 上一版试过"在 CameraX 的相机列表里挑 zoom 下限最小的那颗"，结论是
     * 拿不到：`CameraManager.cameraIdList` 只返回 2 个（id 0 后置、id 1 前置），
     * 两者 zoomRatioRange 都是 1.0–10.0。那颗 0.6 起的相机没有作为独立
     * 逻辑相机暴露给三方应用。
     *
     * 但它的**物理 ID 是可见的**：这颗后置逻辑相机的
     * `android.logicalMultiCamera.physicalIds = [3, 0]`，
     * 其中 0 是主摄、3 是超广角。Camera2 允许在请求里指定
     * `CaptureRequest.PHYSICAL_CAMERA_ID`，让这颗逻辑相机的某个物理子相机
     * 单独出图 —— 这就是系统相机做 0.6× 的方式。
     */
    private fun findUltraWidePhysicalId(): String? {
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        }.getOrNull() ?: return null
        val backId = runCatching { manager.cameraIdList }
            .getOrDefault(emptyArray())
            .firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                }.getOrNull() == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return null

        val chars = runCatching { manager.getCameraCharacteristics(backId) }.getOrNull()
            ?: return null
        // `getPhysicalCameraIds()` 是公开 API，返回这颗逻辑相机的物理子相机。
        // 实测这台机器返回 [0, 3]：0 是主摄、3 是超广角。
        val physical: Set<String> = runCatching { chars.physicalCameraIds }.getOrNull()
            ?: return null

        // 逐个物理 ID 看焦距：超广角的焦距明显更短。
        // 主摄 4.84mm，超广角 1.66mm —— 差近 3 倍，不用猜。
        var best: Pair<String, Float>? = null
        for (id in physical) {
            val pc = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val focal = runCatching {
                pc.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            }.getOrNull()?.firstOrNull() ?: continue
            Log.i(TAG, "physical camera id=$id focal=${focal}mm")
            if (best == null || focal < best!!.second) best = id to focal
        }
        val picked = best?.first
        Log.i(TAG, "findUltraWidePhysicalId: $picked (focal=${best?.second}mm)")
        return picked
    }

    /**
     * 切换超广角物理相机。
     *
     * 传 null 表示回到主摄（清掉物理相机覆盖）。
     *
     * ## 实现约束（重要）
     *
     * `CaptureRequest.PHYSICAL_CAMERA_ID` 在公开 SDK 里**不存在** —— 它是
     * `CameraCharacteristics.getAvailablePhysicalCameraRequestKeys()` 暴露的
     * 运行时 key，必须从那里取，不能写成常量。
     *
     * 所以这里先问设备"你能接受哪些物理相机请求 key"，找到名字含
     * `PHYSICAL_CAMERA_ID` 的那个再用。找不到就说明这颗逻辑相机不支持
     * 单独驱动物理子相机，如实返回 false，而不是假装成功。
     */
    suspend fun setUltraWide(enabled: Boolean): Boolean {
        val c = camera ?: return false
        return try {
            val ext = Camera2CameraControl.from(c.cameraControl)
            if (!enabled) {
                ext.clearCaptureRequestOptions().get()
                Log.i(TAG, "ultra-wide off (main camera)")
                return true
            }
            val id = findUltraWidePhysicalId() ?: run {
                Log.w(TAG, "no ultra-wide physical camera")
                return false
            }
            val key = physicalCameraIdKey() ?: run {
                Log.w(TAG, "device exposes no PHYSICAL_CAMERA_ID request key")
                return false
            }
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(key, id)
                .build()
            ext.setCaptureRequestOptions(opts).get()
            Log.i(TAG, "ultra-wide on (physical id=$id via ${key.name})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setUltraWide($enabled) failed", e)
            false
        }
    }

    /**
     * 从设备暴露的物理相机请求 key 里找出 `PHYSICAL_CAMERA_ID`。
     *
     * 这个 key 不在公开 SDK 常量表里，只能按名字匹配。找不到返回 null ——
     * 有些机型确实不支持单独驱动物理子相机。
     */
    private fun physicalCameraIdKey(): CaptureRequest.Key<String>? {
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        }.getOrNull() ?: return null
        val backId = runCatching { manager.cameraIdList }
            .getOrDefault(emptyArray())
            .firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                }.getOrNull() == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
        val chars = runCatching { manager.getCameraCharacteristics(backId) }.getOrNull()
            ?: return null
        @Suppress("UNCHECKED_CAST")
        return chars.availablePhysicalCameraRequestKeys
            ?.firstOrNull { it.name.contains("PHYSICAL_CAMERA_ID", ignoreCase = true) }
                as? CaptureRequest.Key<String>
    }

    /** 设备是否有可用的超广角物理相机。 */
    fun hasUltraWide(): Boolean = findUltraWidePhysicalId() != null

    fun capabilitySnapshot(): CapabilitySnapshot {
        val zoom = zoomRange()
        val exp = exposureRange()
        val step = exposureStepEv()
        Log.i(
            TAG,
            "capabilities: zoom=${zoom.first}..${zoom.second} exposure=$exp step=$step",
        )
        return CapabilitySnapshot(
            zoomMin = zoom.first,
            zoomMax = zoom.second,
            exposureRange = exp,
            hasUltraWide = zoom.first < 0.999f,
            exposureStepEv = step,
            ultraWideBlocked = ultraWideUnavailable,
            ultraWideAvailable = findUltraWidePhysicalId() != null,
            minFocusDiopters = minFocusDiopters(),
        )
    }

    /**
     * 设备每档曝光补偿的 EV 数。
     *
     * CameraX 的 `exposureState` 只给整数索引范围，不给每档多少 EV ——
     * 而各机型并不统一：常见 1/3 EV，也有 1/2、1/4，这台实测 1/6。
     * 从 Camera2 特性里直接读 `CONTROL_AE_COMPENSATION_STEP`，读不到才退回 1/3。
     *
     * 之前界面写死 `index / 3.0`，在 1/6 步长的机器上读数会差一倍 ——
     * 显示 "+1.3 EV" 实际只补偿了 0.7 EV。
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
     * 曝光补偿。单位是"档"，每档通常是 1/3 EV，具体由设备决定。
     *
     * 这是唯一一个既影响观感、又影响测量读数的参数 —— 所以它被明确标注，
     * 并且改变它会清空标定（和切 GC 同理：参考白是在另一个曝光下测的）。
     */
    suspend fun setExposureCompensation(index: Int): Boolean {
        val c = camera ?: return false
        val info = c.cameraInfo.exposureState
        if (!info.isExposureCompensationSupported) return false
        val clamped = index.coerceIn(
            info.exposureCompensationRange.lower,
            info.exposureCompensationRange.upper,
        )
        return runCatching { c.cameraControl.setExposureCompensationIndex(clamped).get() }
            .isSuccess
    }

    fun exposureRange(): IntRange {
        val info = camera?.cameraInfo?.exposureState ?: return 0..0
        if (!info.isExposureCompensationSupported) return 0..0
        return info.exposureCompensationRange.lower..info.exposureCompensationRange.upper
    }

    /** 点按对焦 / 测光。x,y 是 view 内归一化坐标（0..1）。 */
    suspend fun focusAt(previewView: PreviewView, x: Float, y: Float): Boolean {
        val c = camera ?: return false
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
        return runCatching { c.cameraControl.startFocusAndMetering(action).get().isFocusSuccessful }
            .getOrDefault(false)
    }

    /**
     * 点按对焦（按归一化坐标，不依赖 PreviewView）。
     *
     * 我们的取景器是自绘的 SurfaceView，不是 PreviewView，所以不能用
     * `previewView.meteringPointFactory`。CameraX 提供
     * `CameraControl.startFocusAndMetering` 需要 `MeteringPoint`，
     * 用 `MeteringPointFactory` 的两个工厂方法按像素坐标建点：
     * 这里按取景器的实际像素尺寸构造，再把归一化坐标换算过去。
     *
     * 取景器是中心裁剪填充的（见 shader 的 fill-crop），所以点按位置到
     * 传感器坐标还要考虑裁剪 —— 用 `FillCropMeteringPointFactory`
     * 的行为自己算，保证"点哪里对焦哪里"。
     */
    suspend fun focusAtPoint(viewW: Int, viewH: Int, nx: Float, ny: Float): Boolean {
        val c = camera ?: return false
        if (viewW <= 0 || viewH <= 0) return false
        val display = previewSurfaceView?.display ?: return false
        // `DisplayOrientedMeteringPointFactory(display, cameraInfo, width, height)`：
        // 它按 Display 的旋转和相机朝向把 view 坐标换算到传感器坐标，
        // **不需要 PreviewView** —— 正好适合我们自绘的取景器。
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
        ).setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build()
        return runCatching {
            c.cameraControl.startFocusAndMetering(action).get().isFocusSuccessful
        }.getOrDefault(false)
    }

    /**
     * 手动对焦距离（屈光度，0 = 无穷远，越大越近）。
     *
     * 走 Camera2 的 `LENS_FOCUS_DISTANCE`，先关掉 AF 才生效。
     * 传 null 交回自动对焦。
     */
    suspend fun setManualFocus(diopters: Float?): Boolean {
        val c = camera ?: return false
        return try {
            val ext = Camera2CameraControl.from(c.cameraControl)
            val b = CaptureRequestOptions.Builder()
            if (diopters == null) {
                b.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
            } else {
                b.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                )
                // diopter -> 焦距(米) = 1/diopter；Camera2 要的是米。
                val meters = if (diopters <= 0.01f) 0f else 1f / diopters
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, meters)
            }
            ext.setCaptureRequestOptions(b.build()).get()
            Log.d(TAG, "manual focus diopters=$diopters")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setManualFocus failed", e)
            false
        }
    }

    /** 最近对焦距离（屈光度上界），用于手动对焦滑杆的量程。 */
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

    /**
     * 锁定或解锁自动曝光与自动白平衡。
     *
     * 这是重复性最关键的一步。AE/AWB 自由运行时，先拍白参考再拍暗样品，
     * 两次之间曝光和通道增益都会变 —— 那个除法就在悄悄比较两台不同的相机。
     * 锁定之后参考和样品共享同一个物理状态。
     *
     * CameraX 没有直接 API，所以走 Camera2Interop。
     */
    suspend fun setAutoLock(locked: Boolean): Boolean {
        val camera = camera ?: return false
        return try {
            val ext = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)

            // setCaptureRequestOptions 返回 ListenableFuture。忽略它等于把异步失败
            // 当成成功，调用方随后会对着一个**没锁**的相机拍白参考。
            val future = ext.setCaptureRequestOptions(builder.build())
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                future.addListener(
                    {
                        try {
                            future.get()
                            if (cont.isActive) cont.resume(Unit)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    },
                    mainExecutor,
                )
            }
            Log.d(TAG, "AE/AWB lock set to $locked")
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot set AE/AWB lock to $locked", e)
            false
        }
    }

    /**
     * 手动 ISO / 快门。仅在设备支持时生效。
     *
     * 用 Camera2Interop 直接下发 SENSOR_SENSITIVITY 与 SENSOR_EXPOSURE_TIME。
     * 传 null 表示交回自动。
     */
    suspend fun setManualExposure(iso: Int?, exposureNs: Long?): Boolean {
        val camera = camera ?: return false
        return try {
            val ext = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            if (iso != null) {
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY, iso,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF,
                )
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON,
                )
            }
            if (exposureNs != null) {
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs,
                )
            }
            val future = ext.setCaptureRequestOptions(builder.build())
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                future.addListener(
                    {
                        try {
                            future.get()
                            if (cont.isActive) cont.resume(Unit)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    },
                    mainExecutor,
                )
            }
            Log.d(TAG, "manual exposure iso=$iso exp=$exposureNs")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setManualExposure failed", e)
            false
        }
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
            }
            onEvent(event)
        }
        isRecording = true
        Log.i(TAG, "recording started audio=$hasAudio")
        return true
    }

    fun stopRecording() {
        activeRecording?.let {
            runCatching { it.stop() }
            activeRecording = null
            isRecording = false
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
                                    val bmp = FrameDecoder.imageProxyToBitmap(image)
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
                // 先试超广角：CameraX 的 infos 里可能根本没有它，
                // 那就用 Camera2 枚举出来的 cameraId 去匹配。
                val ultraId = findUltraWideCameraId(lensFacing)
                if (ultraId != null) {
                    val match = infos.firstOrNull { info ->
                        runCatching {
                            androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                                .cameraId == ultraId
                        }.getOrDefault(false)
                    }
                    if (match != null) {
                        Log.i(TAG, "pickSelector: matched ultra-wide cameraId=$ultraId")
                        return@addCameraFilter listOf(match)
                    }
                    // CameraX 确实没暴露这颗 —— 记下来，让 UI 说明原因，
                    // 而不是静默退回 1.0 让用户以为功能没做。
                    Log.w(
                        TAG,
                        "pickSelector: ultra-wide cameraId=$ultraId not exposed by CameraX " +
                            "(available=${infos.size})",
                    )
                    ultraWideUnavailable = true
                }

                val same = infos.filter { info ->
                    runCatching { info.lensFacing }.getOrNull() == lensFacing
                }
                if (same.isEmpty()) {
                    Log.w(TAG, "pickSelector: no camera for facing=$lensFacing")
                    return@addCameraFilter infos
                }
                val withZoom = same.mapNotNull { info ->
                    zoomRangeOf(info)?.let { info to it }
                }
                Log.i(
                    TAG,
                    "pickSelector: ${infos.size} total, ${same.size} facing=$lensFacing, zoom=" +
                        withZoom.joinToString { "${it.second.first}..${it.second.second}" },
                )
                if (withZoom.isEmpty()) return@addCameraFilter same

                // 下限最小的那颗 = 视野最广的那颗。
                val widest = withZoom.minByOrNull { it.second.first } ?: return@addCameraFilter same
                Log.i(
                    TAG,
                    "pickSelector: picked ${widest.second.first}..${widest.second.second}",
                )
                if (widest.second.first >= 0.999f) same else listOf(widest.first)
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
    fun updateTargetRotation(rotation: Int): Boolean {
        var changed = false
        runCatching {
            if (preview?.targetRotation != rotation) {
                preview?.targetRotation = rotation
                changed = true
            }
            if (imageCapture?.targetRotation != rotation) {
                imageCapture?.targetRotation = rotation
                changed = true
            }
            if (videoCapture?.targetRotation != rotation) {
                videoCapture?.targetRotation = rotation
                changed = true
            }
        }.onFailure { Log.w(TAG, "updateTargetRotation($rotation) failed", it) }

        // 渲染器不需要任何旋转参数 —— 方向全由 SurfaceTexture 矩阵决定。
        // 改完 use case 的 targetRotation 后，CameraX 会重算矩阵，渲染器
        // 下一帧自动跟上。
        if (changed) Log.i(TAG, "targetRotation -> $rotation")
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
        /** 最近对焦距离（屈光度），0 表示不支持手动对焦。 */
        val minFocusDiopters: Float = 0f,
    )

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
