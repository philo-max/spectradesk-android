package com.spectradesk.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * 实时滤镜的 GL 处理器：一条管线同时喂预览和录像。
 *
 * ## 为什么不用 PreviewView
 *
 * 上一版用 `PreviewView` + `CameraEffect`，预览上下出现黑边。逐项排查的结果：
 *
 * * GL 管线本身是对的 —— `updateTransformMatrix` 返回的矩阵已经含裁剪
 *   （实测 `scaleY = -0.785`，正好是 ViewPort 的裁剪比例）。
 * * 黑边不是 GL 画出来的 —— 三次对照实验（有 ViewPort / 无 ViewPort /
 *   改 targetRotation）黑边位置一模一样。
 * * **根因在 PreviewView**：接入 `SurfaceProcessor` 后，CameraX 交给
 *   PreviewView 的裁剪信息是竖屏 `754×1280`，而 effect 的输出缓冲是横向
 *   `1280×960`，两者互为转置。PreviewView 不知道画面已经被 GL 处理过，
 *   会再套一次自己的 `PreviewTransformation`，把已经画满的画面按竖屏比例
 *   又做了一次信箱（letterbox）—— 就是那两条黑边。
 *
 * 这个冲突在 PreviewView 内部无解。所以取景器换成自己持有 Surface 的
 * [PreviewSurfaceView]，几何 100% 由本类决定：旋转、裁剪、填满一次做完，
 * 没有第二个人再动它。
 *
 * ## 数据流
 *
 * ```
 *   Camera ──► Preview use case ──► SurfaceRequest
 *                                        │
 *                              createInputSurface()
 *                                        ▼
 *                              SurfaceTexture (OES)
 *                                        │
 *                                   fragment shader（滤镜）
 *                                        ▼
 *                        ┌───────────────┴───────────────┐
 *                        ▼                               ▼
 *              取景器 SurfaceView              录像编码器 Surface
 *                 （自己持有）                  （CameraEffect 给）
 * ```
 *
 * 预览和录像看到的是**同一帧处理结果**，所以录下来的就是你在取景器里看到的。
 *
 * ## 线程模型
 *
 * 所有 GL 调用都在一个专用 HandlerThread 上串行执行 —— EGL 上下文不能跨线程用。
 */
class GlFilterProcessor : SurfaceProcessor {

    companion object {
        private const val TAG = "GlFilterProcessor"

        /**
         * 满屏四边形：位置 (x,y) 然后纹理坐标 (u,v)。
         *
         * 放在 companion 里而不是实例属性：实例属性按声明顺序初始化，
         * 而缓冲区在声明处就要用它们，顺序一错就是
         * "Variable must be initialized"。
         */
        private val VERTICES = floatArrayOf(
            -1f, -1f, // 0 左下
            1f, -1f,  // 1 右下
            -1f, 1f,  // 2 左上
            1f, 1f,   // 3 右上
        )

        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
    }

    /** 渲染参数，UI 线程写、GL 线程读。 */
    @Volatile
    var filter: LiveFilter = LiveFilter.NONE

    /** 滤镜强度，0 = 原图，1 = 全量。 */
    @Volatile
    var intensity: Float = 1f

    /**
     * 手动调色，作用在**预设滤镜之后**的成品像素上。
     *
     * 与 [filter] 的区别：filter 是预设（线性光里做色彩运算），
     * grade 是滑杆（sRGB 域里做感知调整）。两者串联，顺序和剪辑软件一致。
     *
     * 默认全零时 shader 整段跳过，管线开销与不调色完全相同。
     */
    @Volatile
    var grade: ColorGrade = ColorGrade.NONE

    /**
     * 水平镜像。前置摄像头打开，后置关闭。
     *
     * 为什么在顶点着色器里翻而不是在 SurfaceView 上做 scaleX=-1：
     * SurfaceView 是独立图层，`setScaleX(-1)` 只翻窗口内容，不翻 GL 渲染的
     * 缓冲区，而且会让取景器边框和实际画面错位。在 shader 里翻 u 坐标是
     * 真正的像素翻转，录像拿到的也是翻转后的帧 —— 所见即所录。
     */
    @Volatile
    var mirror: Boolean = false

    // 注意：这里**没有** rotationDegrees / targetRotationDegrees 字段。
    //
    // 曾经加过，想用 TransformationInfo.rotationDegrees 来"补正"方向，
    // 结果是转两遍 —— 因为 `getTransformMatrix()` 返回的矩阵已经包含了
    // 传感器朝向与 targetRotation。实测 rotationDegrees 会在 90/180/270
    // 之间变化，而矩阵里那份是固定的，两者叠加导致画面角度完全错乱。
    //
    // 结论：方向问题全部交给矩阵，shader 不参与。

    private val glThread = HandlerThread("gl-filter").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { glHandler.post(it) }

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var tempSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var currentSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false
    private var released = false

    // 预览输入（我们自己的 SurfaceTexture，由 Preview use case 供给）
    private var previewTextureId = 0
    private var previewTexture: SurfaceTexture? = null
    private var previewInputSurface: Surface? = null

    // 录像输入（CameraEffect 给）
    private var videoTextureId = 0
    private var videoTexture: SurfaceTexture? = null
    private var videoInputSurface: Surface? = null
    private var videoOutput: SurfaceOutput? = null

    // 输出：Surface -> EGLSurface
    private val outputEglSurfaces = HashMap<Surface, EGLSurface>()
    private val outputSizes = HashMap<Surface, Size>()
    private var previewSurface: Surface? = null

    // 着色器
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMatrixLoc = 0
    private var uViewSizeLoc = 0
    private var uFrameSizeLoc = 0
    private var uMirrorLoc = 0
    private var uRotateLoc = 0
    private var uFilterLoc = 0
    private var uIntensityLoc = 0
    private var uGradeOnLoc = 0
    private var uGradeALoc = 0
    private var uGradeBLoc = 0
    private var uGradeCLoc = 0
    private var uTexelLoc = 0
    /** 每帧自增，给颗粒噪声当种子。 */
    private var frameSeed = 0f

    private val positionBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(VERTICES); position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(TEX_COORDS); position(0) }

    private val identity = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
    }
    private val transform = FloatArray(16)
    private val updatedTransform = FloatArray(16)

    /** 相机输入帧的尺寸，shader 用它算填充裁剪。 */
    @Volatile
    private var frameW = 0
    @Volatile
    private var frameH = 0
    private var loggedPreviewMatrix = false

    /**
     * 预览需要的旋转角（度，顺时针）。
     *
     * ## 为什么预览要自己算，录像不用
     *
     * 录像走 `SurfaceOutput.updateTransformMatrix()` —— CameraX 直接给出
     * 权威变换，方向一定对。
     *
     * 预览没有 SurfaceOutput（我们自己是 SurfaceProvider），只有
     * `SurfaceTexture.getTransformMatrix()`。那个矩阵**只描述缓冲区到纹理的
     * 映射**，不含"传感器朝向 + 显示旋转"这一层。实测：
     *
     * ```
     * 后置 sensor.orientation = 90
     * 前置 sensor.orientation = 270
     * 竖屏 display rotation = 0
     * ```
     *
     * 所以必须自己按 Camera2 的标准公式算：
     *
     * ```
     * 后置：rotation = (sensorOrientation - displayDegrees + 360) % 360
     * 前置：rotation = (sensorOrientation + displayDegrees) % 360，再镜像
     * ```
     *
     * 这个公式来自 Camera2 官方文档的 `setDisplayOrientation` 说明，是
     * 所有相机应用都在用的那一份。
     */
    @Volatile
    var previewRotation: Int = 0
        set(value) {
            if (field != value) {
                field = value
                Log.i(TAG, "previewRotation -> $value")
            }
        }

    /** 前置：矩阵里不含镜像，要自己翻。 */
    @Volatile
    private var previewMirror: Boolean = false

    /** 由 controller 在绑定后调用，按传感器与显示方向算好预览旋转。 */
    fun configurePreviewOrientation(
        sensorOrientation: Int,
        displayDegrees: Int,
        frontFacing: Boolean,
    ) {
        val r = if (frontFacing) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
        previewRotation = r
        previewMirror = frontFacing
        Log.i(
            TAG,
            "preview orientation: sensor=$sensorOrientation display=$displayDegrees " +
                "front=$frontFacing -> rotation=$r",
        )
    }

    // ------------------------------------------------------------ 预览输入

    /**
     * 作为 `Preview.SurfaceProvider` 使用。
     *
     * CameraX 把相机帧交给我们自己的 SurfaceTexture，我们处理后画到
     * [PreviewSurfaceView] 的 Surface 上。这样预览完全不经过 PreviewView，
     * 也就没有"二次变换"导致的信箱黑边。
     */
    fun providePreviewInput(request: SurfaceRequest) {
        glExecutor.execute {
            if (released) {
                request.willNotProvideSurface()
                return@execute
            }
            try {
                ensureGl()
                releasePreviewInput()

                val size = request.resolution
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                previewTextureId = ids[0]
                configureOesTexture(previewTextureId)

                val st = SurfaceTexture(previewTextureId)
                st.setDefaultBufferSize(size.width, size.height)
                st.setOnFrameAvailableListener({ onPreviewFrame() }, glHandler)
                previewTexture = st
                frameW = size.width
                frameH = size.height

                val surface = Surface(st)
                previewInputSurface = surface

                // 不再监听 TransformationInfo。
                //
                // 曾经用它拿 rotationDegrees 去 shader 里补旋转，结果与
                // `getTransformMatrix()` 里已有的那份叠加，画面角度错乱。
                // 方向完全交给矩阵，这里只提供 Surface。
                request.provideSurface(surface, glExecutor) {
                    glExecutor.execute { releasePreviewInput() }
                }
                Log.i(TAG, "preview input ${size.width}x${size.height}")
            } catch (t: Throwable) {
                Log.e(TAG, "providePreviewInput failed", t)
                request.willNotProvideSurface()
            }
        }
    }

    private fun onPreviewFrame() {
        val st = previewTexture ?: return
        if (released) return
        val target = previewSurface ?: run {
            // 取景器还没就绪，把帧消费掉，否则 SurfaceTexture 会卡住。
            runCatching { st.updateTexImage() }
            return
        }
        val egl = outputEglSurfaces[target] ?: run {
            runCatching { st.updateTexImage() }
            return
        }
        try {
            st.updateTexImage()
            // 预览的方向要由 CameraX 的 TransformationInfo 给出，不能只靠
            // SurfaceTexture 自带的矩阵。
            //
            // `getTransformMatrix` 只包含**缓冲区到纹理**的映射（比如传感器
            // 读出顺序），它不知道这颗传感器装反了 180°。实测这台机器：
            //   后置 sensor.orientation = 90
            //   前置 sensor.orientation = 270
            // 前置比后置多 180°，只靠 SurfaceTexture 矩阵就会把前置画成倒的。
            //
            // `TransformationInfo.getRotationDegrees()` 是 CameraX 算好的
            // 「还要再转多少度才正」，包含 targetRotation 与传感器朝向的差值。
            st.getTransformMatrix(transform)
            System.arraycopy(transform, 0, updatedTransform, 0, 16)
            // 打印一次矩阵，用于确认预览这条路拿到的到底是什么。
            // 录像那条路走 output.updateTransformMatrix()（CameraX 权威变换），
            // 预览只有 SurfaceTexture 自己的矩阵 —— 两者可能差一次旋转。
            if (!loggedPreviewMatrix) {
                loggedPreviewMatrix = true
                Log.i(
                    TAG,
                    "preview matrix = [" + transform.joinToString(", ") { "%.3f".format(it) } + "]",
                )
            }
            drawTo(listOf(egl), previewTextureId, target, st.timestamp, isPreview = true)
        } catch (t: Throwable) {
            Log.e(TAG, "preview frame failed", t)
        }
    }

    // ------------------------------------------------------------ 录像（CameraEffect）

    override fun onInputSurface(request: SurfaceRequest) {
        glExecutor.execute {
            if (released) {
                request.willNotProvideSurface()
                return@execute
            }
            try {
                ensureGl()
                releaseVideoInput()

                val size = request.resolution
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                videoTextureId = ids[0]
                configureOesTexture(videoTextureId)

                val st = SurfaceTexture(videoTextureId)
                st.setDefaultBufferSize(size.width, size.height)
                st.setOnFrameAvailableListener({ onVideoFrame() }, glHandler)
                videoTexture = st

                val surface = Surface(st)
                videoInputSurface = surface
                request.provideSurface(surface, glExecutor) {
                    glExecutor.execute { releaseVideoInput() }
                }
                Log.i(TAG, "video input ${size.width}x${size.height}")
            } catch (t: Throwable) {
                Log.e(TAG, "onInputSurface failed", t)
                request.willNotProvideSurface()
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        glExecutor.execute {
            if (released) {
                output.close()
                return@execute
            }
            try {
                ensureGl()
                var surface: Surface? = null
                surface = output.getSurface(glExecutor) {
                    val s = surface
                    glExecutor.execute {
                        if (s != null) unregisterOutput(s, output) else output.close()
                    }
                }
                registerOutput(surface, output.size)
                videoOutput = output
                Log.i(TAG, "video output ${output.size.width}x${output.size.height}")
            } catch (t: Throwable) {
                Log.e(TAG, "onOutputSurface failed", t)
                output.close()
            }
        }
    }

    private fun onVideoFrame() {
        val st = videoTexture ?: return
        if (released) return
        val output = videoOutput
        val target = output?.let { s ->
            // 找到这个 SurfaceOutput 对应的 Surface 与 EGL 表面。
            outputEglSurfaces.keys.firstOrNull { key ->
                outputSizes[key] == s.size && key != previewSurface
            }
        }
        val egl = target?.let { outputEglSurfaces[it] }
        if (target == null || egl == null) {
            runCatching { st.updateTexImage() }
            return
        }
        try {
            st.updateTexImage()
            st.getTransformMatrix(transform)
            // 录像这一路是 CameraX 给的，问它要权威变换。
            output.updateTransformMatrix(updatedTransform, transform)
            drawTo(listOf(egl), videoTextureId, target, st.timestamp)
        } catch (t: Throwable) {
            Log.e(TAG, "video frame failed", t)
        }
    }

    // ------------------------------------------------------------ 取景器表面

    fun attachPreviewSurface(surface: Surface, width: Int, height: Int) {
        glExecutor.execute {
            if (released) return@execute
            try {
                ensureGl()
                registerOutput(surface, Size(width, height))
                previewSurface = surface
                Log.i(TAG, "preview surface ${width}x${height}")
            } catch (t: Throwable) {
                Log.e(TAG, "attachPreviewSurface failed", t)
            }
        }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        glExecutor.execute {
            val s = previewSurface ?: return@execute
            // SurfaceView 改尺寸后，旧 EGL 窗口表面的底层缓冲会失效，
            // 继续往上画只会得到旧尺寸的一块。必须销毁重建，否则画面
            // 会缩在中间、四周露出背景（看起来就是黑边）。
            val old = outputEglSurfaces.remove(s)
            if (old != null && old != EGL14.EGL_NO_SURFACE) {
                if (currentSurface == old) makeCurrent(tempSurface)
                EGL14.eglDestroySurface(eglDisplay, old)
            }
            outputSizes[s] = Size(width, height)
            registerOutput(s, Size(width, height))
            Log.i(TAG, "preview resized ${width}x$height (egl surface rebuilt)")
        }
    }

    fun detachPreviewSurface() {
        glExecutor.execute {
            val s = previewSurface ?: return@execute
            previewSurface = null
            outputEglSurfaces.remove(s)?.let { eglSurface ->
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    if (currentSurface == eglSurface) makeCurrent(tempSurface)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
            }
            outputSizes.remove(s)
        }
    }

    // ------------------------------------------------------------ 绘制

    private fun drawTo(
        surfaces: List<EGLSurface>,
        textureId: Int,
        key: Surface,
        timestampNs: Long,
        isPreview: Boolean = false,
    ) {
        val size = outputSizes[key] ?: return
        for (eglSurface in surfaces) {
            makeCurrent(eglSurface)
            // 用 EGL 表面真实尺寸设 viewport：缓冲区尺寸和窗口尺寸可能不同，
            // 用错会让画面只铺一部分，剩下是 glClear 的黑。
            val vw = querySurface(eglSurface, EGL14.EGL_WIDTH).takeIf { it > 0 } ?: size.width
            val vh = querySurface(eglSurface, EGL14.EGL_HEIGHT).takeIf { it > 0 } ?: size.height
            GLES20.glViewport(0, 0, vw, vh)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(uMatrixLoc, 1, false, updatedTransform, 0)
            GLES20.glUniform2f(uViewSizeLoc, vw.toFloat(), vh.toFloat())
            // 传**旋转之后**的帧尺寸：u_matrix 常含 90° 旋转（横拍的传感器帧
            // 转成竖屏显示），旋转后纵横比会倒过来。用未旋转的尺寸算填充裁剪
            // 会让画面被拉长 —— 这正是上一版变形的原因。
            val rotated = isQuarterTurn(updatedTransform)
            val aw = if (rotated) frameH else frameW
            val ah = if (rotated) frameW else frameH
            GLES20.glUniform2f(uFrameSizeLoc, aw.toFloat(), ah.toFloat())
            GLES20.glUniform1f(uMirrorLoc, if (mirror) 1f else 0f)
            // 预览补旋转；录像的矩阵已含旋转，传 0。
            val rotDeg = if (isPreview) previewRotation else 0
            GLES20.glUniform1f(uRotateLoc, Math.toRadians(rotDeg.toDouble()).toFloat())
            GLES20.glUniform1i(uFilterLoc, filter.ordinal)
            GLES20.glUniform1f(uIntensityLoc, intensity)

            // 调色：全零时 u_gradeOn=0，shader 整段跳过，开销与不调色相同。
            //
            // 十个参数拆成三个 vec4 传，比十次 glUniform1f 少九次 JNI 调用 ——
            // 这行代码每帧每个输出都要跑，30fps 下差 900 次/秒。
            val g = grade
            val gradeOn = !g.isIdentity
            GLES20.glUniform1f(uGradeOnLoc, if (gradeOn) 1f else 0f)
            if (gradeOn) {
                GLES20.glUniform4f(
                    uGradeALoc,
                    g.brightness, g.contrast, g.saturation, g.temperature,
                )
                GLES20.glUniform4f(
                    uGradeBLoc,
                    g.tint, g.highlights, g.shadows, g.sharpen,
                )
                // 第四分量放一个每帧变化的种子，给颗粒噪声用：
                // 固定种子会让颗粒在画面上"钉死"，看起来像脏点而不是胶片颗粒。
                GLES20.glUniform4f(uGradeCLoc, g.vignette, g.grain, frameSeed, 0f)
            }
            GLES20.glUniform2f(uTexelLoc, 1f / frameW, 1f / frameH)

            positionBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
        }
    }

    // ------------------------------------------------------------ 输出注册

    private fun registerOutput(surface: Surface, size: Size) {
        ensureGl()
        outputSizes[surface] = size
        if (!outputEglSurfaces.containsKey(surface)) {
            val attribs = intArrayOf(EGL14.EGL_NONE)
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, attribs, 0,
            )
            val err = EGL14.eglGetError()
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE || err != EGL14.EGL_SUCCESS) {
                Log.e(TAG, "eglCreateWindowSurface failed 0x${Integer.toHexString(err)}")
                return
            }
            outputEglSurfaces[surface] = eglSurface
        }
    }

    private fun unregisterOutput(surface: Surface, output: SurfaceOutput) {
        outputEglSurfaces.remove(surface)?.let { eglSurface ->
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                if (currentSurface == eglSurface) makeCurrent(tempSurface)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
        }
        outputSizes.remove(surface)
        if (videoOutput === output) videoOutput = null
        try {
            output.close()
        } catch (t: Throwable) {
            Log.w(TAG, "output.close failed", t)
        }
    }

    // ------------------------------------------------------------ EGL

    private fun ensureGl() {
        if (initialized) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // 录像要求：没有这个标志，编码器可能拒绝我们的输出 Surface。
            EGLExt.EGL_RECORDABLE_ANDROID, EGL14.EGL_TRUE,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0,
            ) || numConfigs[0] == 0
        ) {
            throw IllegalStateException("no suitable EGLConfig")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
        )
        checkEgl("eglCreateContext")

        tempSurface = createPbuffer(1, 1)
        makeCurrent(tempSurface)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "a_position")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "a_texCoord")
        uMatrixLoc = GLES20.glGetUniformLocation(program, "u_matrix")
        uViewSizeLoc = GLES20.glGetUniformLocation(program, "u_viewSize")
        uFrameSizeLoc = GLES20.glGetUniformLocation(program, "u_frameSize")
        uMirrorLoc = GLES20.glGetUniformLocation(program, "u_mirror")
        uRotateLoc = GLES20.glGetUniformLocation(program, "u_rotate")
        uFilterLoc = GLES20.glGetUniformLocation(program, "u_filter")
        uIntensityLoc = GLES20.glGetUniformLocation(program, "u_intensity")
        uGradeOnLoc = GLES20.glGetUniformLocation(program, "u_gradeOn")
        uGradeALoc = GLES20.glGetUniformLocation(program, "u_gradeA")
        uGradeBLoc = GLES20.glGetUniformLocation(program, "u_gradeB")
        uGradeCLoc = GLES20.glGetUniformLocation(program, "u_gradeC")
        uTexelLoc = GLES20.glGetUniformLocation(program, "u_texel")

        initialized = true
        Log.i(TAG, "GL ready")
    }

    private fun configureOesTexture(id: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
    }

    /**
     * 判断这个纹理矩阵是否把画面转了 90°（或 270°）。
     *
     * 列主序矩阵里，第一列 (m[0], m[1]) 是 u 轴映射后的方向。若它主要指向 v 轴
     * （即 |m[0]| 明显小于 |m[1]|），说明发生了四分之一转，旋转后宽高互换。
     */
    private fun isQuarterTurn(m: FloatArray): Boolean =
        kotlin.math.abs(m[0]) < kotlin.math.abs(m[1])

    private fun createPbuffer(w: Int, h: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attribs, 0)
        checkEgl("eglCreatePbufferSurface")
        return s
    }

    private fun querySurface(eglSurface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        return if (EGL14.eglQuerySurface(eglDisplay, eglSurface, what, value, 0)) value[0] else 0
    }

    private fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException(
                "eglMakeCurrent failed 0x${Integer.toHexString(EGL14.eglGetError())}",
            )
        }
        currentSurface = eglSurface
    }

    private fun checkEgl(op: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) {
            throw IllegalStateException("$op: EGL error 0x${Integer.toHexString(err)}")
        }
    }

    // ------------------------------------------------------------ 着色器

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("compile failed: $log")
        }
        return s
    }

    // ------------------------------------------------------------ 释放

    private fun releasePreviewInput() {
        previewTexture?.setOnFrameAvailableListener(null)
        runCatching { previewTexture?.release() }
        previewTexture = null
        previewInputSurface?.release()
        previewInputSurface = null
        if (previewTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(previewTextureId), 0)
            previewTextureId = 0
        }
    }

    private fun releaseVideoInput() {
        videoTexture?.setOnFrameAvailableListener(null)
        runCatching { videoTexture?.release() }
        videoTexture = null
        videoInputSurface?.release()
        videoInputSurface = null
        if (videoTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
            videoTextureId = 0
        }
    }

    fun release() {
        glExecutor.execute {
            if (released) return@execute
            released = true
            releasePreviewInput()
            releaseVideoInput()
            for ((_, eglSurface) in outputEglSurfaces) {
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
            }
            outputEglSurfaces.clear()
            outputSizes.clear()
            videoOutput?.let {
                runCatching { it.close() }
            }
            videoOutput = null
            previewSurface = null

            if (initialized) {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (tempSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, tempSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                tempSurface = EGL14.EGL_NO_SURFACE
                initialized = false
            }
            glThread.quitSafely()
            Log.i(TAG, "released")
        }
    }

    private val VERTEX_SHADER = """
        attribute vec4 a_position;
        attribute vec4 a_texCoord;
        uniform mat4 u_matrix;       // SurfaceTexture 的相机帧变换（只含缓冲区映射）
        uniform vec2 u_viewSize;     // 取景器实际像素尺寸
        uniform vec2 u_frameSize;    // 相机输入帧像素尺寸
        uniform float u_rotate;      // 预览专用：还要转多少弧度才正（0 = 录像路径）
        uniform float u_mirror;      // 1.0 = 水平镜像（前置）
        varying vec2 v_texCoord;
        void main() {
            gl_Position = a_position;
            vec2 uv = (u_matrix * a_texCoord).xy;

            // 预览路径需要补旋转，录像路径不需要。
            //
            // 录像走 SurfaceOutput.updateTransformMatrix()，CameraX 已经把
            // 旋转算进矩阵里了（所以 u_rotate = 0）。
            // 预览只有 SurfaceTexture 自己的矩阵，它不含"传感器朝向 + 显示
            // 旋转"，所以要在这里按 Camera2 的标准公式补上。
            if (u_rotate != 0.0) {
                vec2 c = uv - 0.5;
                float s = sin(u_rotate);
                float co = cos(u_rotate);
                uv = vec2(c.x * co - c.y * s, c.x * s + c.y * co) + 0.5;
            }

            if (u_mirror > 0.5) uv.x = 1.0 - uv.x;

            // 中心裁剪填满取景框（等比，不拉伸）。
            //
            // 相机帧是横向 4:3，取景框是竖屏约 0.59。两者纵横比差很多：
            // 直接铺会严重变形，不铺就留黑边。这里算一个**单轴**缩放，
            // 只裁掉多余的那一维，另一维保持 1.0 —— 关键是不能两个轴都乘，
            // 那会得到非等比缩放，画面被压扁或拉长。
            float frameAspect = u_frameSize.x / u_frameSize.y;
            float viewAspect  = u_viewSize.x / u_viewSize.y;
            vec2 scale = vec2(1.0);
            if (viewAspect > frameAspect) {
                // 取景框更宽 → 画面上下有富余 → 裁上下
                scale.y = frameAspect / viewAspect;
            } else {
                // 取景框更高 → 画面左右有富余 → 裁左右
                scale.x = viewAspect / frameAspect;
            }
            v_texCoord = (uv - 0.5) * scale + 0.5;
        }
    """

    /**
     * 一个着色器，八种观感。
     *
     * 色彩运算在**线性光**里做：先把 sRGB 帧解码成线性，处理完再编码回去。
     * 直接把矩阵套在 sRGB 编码值上（多数滤镜实现这么做，因为看起来更"冲"）
     * 不是色彩运算，是美术处理。对一台要报数的仪器来说，线性版本才对。
     */
    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_texCoord;
        uniform samplerExternalOES u_texture;
        uniform int u_filter;
        uniform float u_intensity;

        // 手动调色（剪映/醒图式分项）。u_gradeOn=0 时整段跳过。
        uniform float u_gradeOn;
        uniform vec4 u_gradeA;   // 亮度 对比度 饱和度 色温
        uniform vec4 u_gradeB;   // 色调 高光 阴影 锐化
        uniform vec4 u_gradeC;   // 暗角 颗粒 噪声种子 -
        uniform vec2 u_texel;    // 1/帧宽, 1/帧高（锐化取邻居用）

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        vec3 srgbToLinear(vec3 c) {
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
        }
        vec3 linearToSrgb(vec3 c) {
            c = max(c, vec3(0.0));
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
        }
        float sCurve(float x) {
            float v = clamp(x, 0.0, 1.0);
            return v * v * (3.0 - 2.0 * v);
        }

        void main() {
            vec4 src = texture2D(u_texture, v_texCoord);
            vec3 lin = srgbToLinear(src.rgb);
            vec3 outColor = lin;

            if (u_filter == 1) {              // MONO
                outColor = vec3(dot(lin, LUMA));
            } else if (u_filter == 2) {       // VIVID
                float y = dot(lin, LUMA);
                outColor = vec3(
                    sCurve(clamp(y + (lin.r - y) * 1.35, 0.0, 1.0)),
                    sCurve(clamp(y + (lin.g - y) * 1.35, 0.0, 1.0)),
                    sCurve(clamp(y + (lin.b - y) * 1.35, 0.0, 1.0)));
            } else if (u_filter == 3) {       // COOL
                outColor = lin * vec3(0.93, 0.99, 1.10);
            } else if (u_filter == 4) {       // WARM
                outColor = lin * vec3(1.10, 1.00, 0.90);
            } else if (u_filter == 5) {       // CONTRAST
                float y = dot(lin, LUMA);
                outColor = vec3(sCurve(sCurve(sCurve(y))));
            } else if (u_filter == 6) {       // INVERT（保色相）
                float y = max(dot(lin, LUMA), 0.0001);
                outColor = lin * ((1.0 - y) / y);
            } else if (u_filter == 7) {       // CLIP 削波告警
                float hi = max(max(src.r, src.g), src.b);
                float lo = min(min(src.r, src.g), src.b);
                if (hi >= 0.98) {
                    outColor = srgbToLinear(vec3(1.0, 0.12, 0.12));
                } else if (lo <= 0.02 && hi <= 0.08) {
                    outColor = srgbToLinear(vec3(0.12, 0.35, 1.0));
                } else {
                    outColor = lin * 0.55;
                }
            }

            vec3 mixed = mix(lin, outColor, u_intensity);
            vec3 rgb = linearToSrgb(mixed);

            // ── 手动调色 ────────────────────────────────────────────────
            //
            // 这一段刻意放在 sRGB 域，不是线性光。原因写在 ColorGrade.kt 顶部：
            // 滑杆是感知控制，用户拿它和剪映对比，而剪映在 sRGB 域里做。
            // 把同一个对比度公式放进线性光，中灰会被推坏，滑杆就"不像"了。
            //
            // 顺序也照剪辑软件：明暗 → 色彩 → 质感 → 收尾。
            if (u_gradeOn > 0.5) {
                // 1) 高光/阴影：先做，因为它改变的是动态范围分配，
                //    必须在对比度之前 —— 反过来会把已压缩的高光再拉爆。
                float lum = dot(rgb, LUMA);
                if (u_gradeB.z != 0.0) {
                    // 阴影只抬暗部：权重 (1-lum)^2，亮部几乎不受影响。
                    float w = (1.0 - lum) * (1.0 - lum);
                    rgb += u_gradeB.z * 0.5 * w;
                }
                if (u_gradeB.y != 0.0) {
                    // 高光只压亮部：权重 lum^2。
                    float w = lum * lum;
                    rgb += u_gradeB.y * 0.5 * w;
                }

                // 2) 对比度：围绕 0.5 中灰缩放。系数 1+c 让 +1 到 2 倍、
                //    -1 到 0（纯灰）。和剪映的观感一致。
                if (u_gradeA.y != 0.0) {
                    float k = 1.0 + u_gradeA.y;
                    rgb = (rgb - 0.5) * k + 0.5;
                }

                // 3) 亮度：加性偏移。乘性会让暗部几乎没反应。
                if (u_gradeA.x != 0.0) {
                    rgb += u_gradeA.x * 0.35;
                }

                // 4) 饱和度：向亮度轴插值。-1 = 完全去色，+1 ≈ 2 倍。
                if (u_gradeA.z != 0.0) {
                    float g = dot(rgb, LUMA);
                    rgb = mix(vec3(g), rgb, 1.0 + u_gradeA.z);
                }

                // 5) 色温/色调：白平衡偏移。
                //    色温走 R↔B（冷暖），色调走 G↔品红，这是行业惯例。
                if (u_gradeA.w != 0.0) {
                    float t = u_gradeA.w * 0.12;
                    rgb += vec3(t, 0.0, -t);
                }
                if (u_gradeB.x != 0.0) {
                    float t = u_gradeB.x * 0.10;
                    rgb += vec3(t * 0.5, -t, t * 0.5);
                }

                // 6) 锐化：非锐化掩模。取四邻均值，按差值放大。
                //    只对亮度做，彩色边缘锐化会出彩边。
                if (u_gradeB.w > 0.0) {
                    vec3 n = (
                        texture2D(u_texture, v_texCoord + vec2(u_texel.x, 0.0)).rgb +
                        texture2D(u_texture, v_texCoord - vec2(u_texel.x, 0.0)).rgb +
                        texture2D(u_texture, v_texCoord + vec2(0.0, u_texel.y)).rgb +
                        texture2D(u_texture, v_texCoord - vec2(0.0, u_texel.y)).rgb
                    ) * 0.25;
                    // 邻居是 sRGB 原始值，先编码再比，两边同域。
                    vec3 nl = linearToSrgb(srgbToLinear(n));
                    float detail = dot(rgb - nl, LUMA);
                    rgb += detail * u_gradeB.w * 2.0;
                }

                // 7) 暗角：按到中心的归一化距离衰减。
                if (u_gradeC.x > 0.0) {
                    vec2 d = v_texCoord - 0.5;
                    float r = length(d) * 1.414;
                    float v = 1.0 - u_gradeC.x * smoothstep(0.45, 1.0, r);
                    rgb *= v;
                }

                // 8) 颗粒：哈希噪声，只加在亮度上。
                if (u_gradeC.y > 0.0) {
                    float n = fract(sin(dot(
                        v_texCoord * 1000.0 + u_gradeC.z,
                        vec2(12.9898, 78.233))) * 43758.5453);
                    rgb += (n - 0.5) * u_gradeC.y * 0.18;
                }

                rgb = clamp(rgb, 0.0, 1.0);
            }

            gl_FragColor = vec4(rgb, src.a);
        }
    """
}
