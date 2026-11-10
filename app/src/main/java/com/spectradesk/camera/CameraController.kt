package com.spectradesk.camera

import android.content.Context
import android.util.Log
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper over CameraX for preview + still capture.
 *
 * The wrapper exists for four reasons:
 *
 * * **Shutter semantics.**  `takePicture` is asynchronous and re-entrant; a
 *   double-tapped shutter must not start two overlapping captures with
 *   interleaved state.  [capture] returns `null` while one is in flight.
 * * **Cancellation.**  A cancelled coroutine must still release the image proxy,
 *   or the camera pipeline stalls after a few cancelled shots.
 * * **Determinism.**  Auto-exposure and auto-white-balance are not touched after
 *   binding: the reference and the sample must be measured under the same gains,
 *   or the division is meaningless.
 * * **Preview wiring.**  Binding a `Preview` use case to the `PreviewView`
 *   surface is easy to get subtly wrong (surface provider after bind, or a stale
 *   provider after rebind), and the failure looks like a black screen rather than
 *   an exception.
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "SpectraDesk"
    }

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    @Volatile
    private var capturing = false

    val isCapturing: Boolean get() = capturing

    /**
     * Bind preview + still capture to the given view.
     *
     * Re-binding is allowed and expected (lens switch, resume from background);
     * the previous session is unbound first so the camera is never opened twice.
     */
    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    ) {
        val cameraProvider = awaitProvider()
        provider = cameraProvider

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val previewUseCase = Preview.Builder().build().also {
            // The surface provider must be attached to *this* preview instance;
            // reusing an old provider after a rebind is the classic black-screen
            // bug.
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val capture = ImageCapture.Builder()
            // Minimise latency: a measurement is taken by pointing at a sample,
            // and a 1.5 s lag makes the operator move the phone before the
            // shutter actually fires.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        cameraProvider.unbindAll()
        // Keep the Camera object: the AE/AWB lock needs it for interop controls,
        // and the use cases alone cannot express that.
        camera = cameraProvider.bindToLifecycle(owner, selector, previewUseCase, capture)

        preview = previewUseCase
        imageCapture = capture
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    /**
     * Lock or unlock auto-exposure and auto-white-balance.
     *
     * This is the single most important control for repeatability.  With AE/AWB
     * free-running, taking a white reference and then measuring a darker sample
     * changes the exposure and the channel gains between the two shots - so the
     * division silently compares two different cameras.  Locking them makes the
     * reference and the samples share one physical state.
     *
     * CameraX exposes no direct API for this, so it goes through Camera2Interop,
     * which is why it needs the Camera2-backed camera object rather than the
     * use case alone.
     */
    suspend fun setAutoLock(locked: Boolean): Boolean {
        val camera = camera ?: return false
        return try {
            val ext = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            // `CONTROL_AE_LOCK` / `CONTROL_AWB_LOCK` are typed `Key<Boolean>` in
            // this SDK, so the value is a Boolean rather than the camera2
            // metadata integer.
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)

            // `setCaptureRequestOptions` returns a ListenableFuture.  Ignoring it
            // means an asynchronous failure is reported as success, and the caller
            // then takes a white reference against an *unlocked* camera - the
            // reference and every later sample are then taken under different
            // exposures, and the division silently produces wrong colour.  Wait
            // for the future and let its exception propagate.
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
            android.util.Log.d(TAG, "AE/AWB lock set to $locked")
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cannot set AE/AWB lock to $locked", e)
            false
        }
    }

    /**
     * Bind preview + still capture, with the preview drawn through a custom renderer.
     *
     * This is the path that makes real-time filters possible. CameraX's
     * `PreviewView` blits the camera surface straight to the screen and offers no
     * hook to touch pixels, so a filtered viewfinder needs the preview to render
     * into a `SurfaceTexture` we own, which an OpenGL thread then samples and
     * draws with a fragment shader.
     *
     * Both shipping apps analysed for this feature do exactly this (InstaShot via
     * GPUImage, Google Photos via its own GL pipeline): one texture fetch per
     * pixel, all colour maths on the GPU.
     */
    suspend fun bindWithRenderer(
        owner: LifecycleOwner,
        renderer: LiveFilterRenderer,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    ) {
        val cameraProvider = awaitProvider()
        provider = cameraProvider

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val previewUseCase = Preview.Builder().build().also { preview ->
            preview.setSurfaceProvider { request ->
                val st = renderer.surfaceTexture()
                if (st == null) {
                    Log.w(
                        TAG,
                        "surface provider called before the GL texture existed; " +
                            "request resolution=${request.resolution}",
                    )
                    request.willNotProvideSurface()
                    return@setSurfaceProvider
                }

                // `TransformationInfo` is NOT available synchronously here - it
                // arrives through a listener, which is why the old code that read
                // it directly did not compile. The buffer size is known now, so it
                // is applied immediately and the rotation is patched in when the
                // listener fires. Without that second step the crop is computed
                // against an unrotated size and the preview tears.
                renderer.setCameraFrame(
                    resolution = request.resolution,
                    rotationDegrees = 0,
                    cropRect = request.resolution.let { android.graphics.Rect(0, 0, it.width, it.height) },
                )

                request.setTransformationInfoListener(mainExecutor) { info ->
                    renderer.setCameraFrame(
                        resolution = request.resolution,
                        rotationDegrees = info.rotationDegrees,
                        cropRect = info.cropRect,
                    )
                }

                Log.d(TAG, "providing surface ${request.resolution}")
                request.provideSurface(
                    android.view.Surface(st),
                    mainExecutor,
                ) { /* surface released by CameraX */ }
            }
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(owner, selector, previewUseCase, capture)
        preview = previewUseCase
        imageCapture = capture
    }

    /** Current camera object, used for interop controls. */
    var camera: androidx.camera.core.Camera? = null
        private set

    /**
     * The most recent captured still, as a bitmap.
     *
     * Kept so the UI can re-render it through a different filter without taking
     * another picture.  That is what makes the filter bar feel live: switching
     * filters after a shot re-processes this frame instead of asking the operator
     * to hold still and shoot again.
     */
    var lastShot: android.graphics.Bitmap? = null
        private set

    /**
     * Apply a filter to the last captured frame and return a new bitmap.
     *
     * Filters run here rather than in the preview stream.  A live filtered
     * viewfinder would need a `SurfaceProcessor` and a GL pipeline; processing
     * the still instead gives the same visual result for the thing that matters
     * (examining a sample you have already framed) at a fraction of the
     * complexity and with no risk of the preview and the measurement diverging.
     *
     * Returns `null` when there is nothing to filter.  The caller must then leave
     * the live preview visible: covering a live viewfinder with a filter applied
     * to a *stale or absent* frame hides the scene the operator is trying to
     * frame, which is worse than showing no filter at all.
     */
    fun renderFiltered(kind: FilterKind): android.graphics.Bitmap? {
        val source = lastShot ?: return null
        if (kind == FilterKind.NONE || kind == FilterKind.GRID) return null
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        Filters.apply(kind, pixels, w, h)
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Take one still and decode it.
     *
     * Returns `null` when a capture is already running, so a double tap is
     * ignored rather than queued.
     */
    /**
     * Take one still and decode it.
     *
     * Returns `null` when a capture is already running, so a double tap is
     * ignored rather than queued.  Decoding runs on [Dispatchers.Default]: a
     * 12 MP JPEG takes hundreds of milliseconds to decode, and doing that on the
     * main thread freezes the preview for the whole time.
     */
    suspend fun capture(gammaCorrected: Boolean): Capture? {
        val capture = imageCapture ?: throw IllegalStateException("相机未就绪")
        if (capturing) return null
        capturing = true
        try {
            android.util.Log.d(TAG, "takePicture requested")
            val proxy = takePicture(capture)
            try {
                android.util.Log.d(TAG, "decoding ${proxy.width}x${proxy.height}")
                val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val bitmap = FrameDecoder.imageProxyToBitmap(proxy)
                    val mean = FrameDecoder.sampleCentrePatch(
                        bitmap,
                        FrameDecoder.PATCH_FRACTION,
                        gammaCorrected,
                    )
                    // No full-frame linear conversion: the measurement only needs
                    // the patch mean, and converting 3 M pixels costs ~100 ms and
                    // 70 MB for data nothing reads.
                    Triple(bitmap, mean, DoubleArray(0))
                }
                val (bitmap, mean, _) = decoded
                // Remember the frame so the filter bar can re-render it without
                // asking the operator to shoot again.
                lastShot = bitmap
                android.util.Log.d(
                    TAG,
                    "decoded ${bitmap.width}x${bitmap.height} mean=${mean.joinToString { "%.4f".format(it) }}",
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
            android.util.Log.d(TAG, "capture finished")
        }
    }

    private suspend fun takePicture(capture: ImageCapture): ImageProxy =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        android.util.Log.d(TAG, "onCaptureSuccess active=${cont.isActive}")
                        if (cont.isActive) cont.resume(image) else image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        android.util.Log.e(TAG, "onError code=${exception.imageCaptureError}", exception)
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

    fun release() {
        provider?.unbindAll()
        provider = null
        imageCapture = null
        preview = null
    }
}
