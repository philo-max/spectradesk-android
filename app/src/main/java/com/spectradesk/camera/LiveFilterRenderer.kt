package com.spectradesk.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real-time viewfinder filter renderer.
 *
 * ## Why OpenGL and not a pixel loop
 *
 * The two shipping camera apps analysed for this feature
 * (InstaShot `GPU3DLookupFilter.glsl`, Google Photos `filter.fsh`) both do one
 * GPU texture fetch per pixel. That is the whole trick: a 1080p frame is 2
 * million pixels, and the per-frame budget at 30 fps is 33 ms. A Java loop over
 * that many pixels costs tens of milliseconds on its own, before any colour
 * maths; a fragment shader does it in well under a millisecond because the work
 * is spread across GPU cores.
 *
 * So the pipeline is the standard one every camera app uses:
 *
 * ```
 *   Camera  ->  OES external texture  ->  fragment shader (LUT / matrix)
 *           ->  screen surface
 * ```
 *
 * `GL_TEXTURE_EXTERNAL_OES` is required for camera frames: they arrive as a
 * `SurfaceTexture` and cannot be sampled as a normal 2D texture.
 *
 * ## The measurement boundary
 *
 * This renderer only ever draws to the screen. The still capture used for
 * measurement goes through `ImageCapture`, a completely separate CameraX use
 * case, so no filter can reach a number. That separation is deliberate and is
 * the reason a live filter is safe to ship in a measuring tool at all.
 */
class LiveFilterRenderer(
    private val context: Context,
    private val onFrameAvailable: () -> Unit,
    /**
     * Called once, on the GL thread, when the SurfaceTexture exists.
     *
     * The camera bind must wait for this: CameraX refuses a surface request
     * whose provider returns null, and every filter silently disappears. The
     * caller uses it to flip a Compose state that the bind effect observes.
     */
    private val onSurfaceReady: () -> Unit = {},
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile
    var filter: LiveFilter = LiveFilter.NONE

    /** Strength of the effect, 0 = original, 1 = full. Maps to shader `u_intensity`. */
    @Volatile
    var intensity: Float = 1f

    private var surfaceTexture: SurfaceTexture? = null
    private var textureId = 0
    private var program = 0
    private var uMatrixLoc = 0
    private var uFitLoc = 0
    private var uFilterLoc = 0
    private var uIntensityLoc = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0

    private val matrix = FloatArray(16)
    private var frameAvailable = false
    private val frameLock = Object()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var frameWidth = 0
    private var frameHeight = 0

    /** Rotation the camera applies to the frame, from CameraX. */
    @Volatile
    private var rotationDegrees = 0

    /** 16-float matrix that stretches the camera frame to fill the viewport. */
    private val fitMatrix = FloatArray(16).apply {
        // Identity to start; updated when the first frame arrives.
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTICES)
            position(0)
        }

    /** The camera-facing SurfaceTexture, handed to CameraX as the preview surface. */
    fun surfaceTexture(): SurfaceTexture? = surfaceTexture

    // ------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "a_position")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "a_texCoord")
        uMatrixLoc = GLES20.glGetUniformLocation(program, "u_matrix")
        uFitLoc = GLES20.glGetUniformLocation(program, "u_fit")
        uFilterLoc = GLES20.glGetUniformLocation(program, "u_filter")
        uIntensityLoc = GLES20.glGetUniformLocation(program, "u_intensity")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        surfaceTexture = SurfaceTexture(textureId).apply {
            // The default buffer size is what the camera will actually render at.
            // Setting it explicitly (rather than accepting the camera default)
            // means the fit matrix and the real frame agree, which is what stops
            // the preview from being stretched.
            setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            setOnFrameAvailableListener(this@LiveFilterRenderer)
        }
        frameWidth = PREVIEW_WIDTH
        frameHeight = PREVIEW_HEIGHT
        updateFitMatrix()
        // Error level survives log filtering, which is why this is not Log.d:
        // diagnosing a black preview needs this line to be visible on a release
        // build where debug output is stripped.
        Log.e(TAG, "GL surface created, texture=$textureId")
        onSurfaceReady()
        onFrameAvailable()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return

        synchronized(frameLock) {
            if (frameAvailable) {
                st.updateTexImage()
                st.getTransformMatrix(matrix)
                frameAvailable = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        // Recompute the crop every frame: `matrix` only becomes non-identity after
        // the first updateTexImage, and the rotation it carries decides which axis
        // the crop has to scale.
        updateFitMatrix()

        // The crop is applied to the texture coordinates; `matrix` (the camera's
        // own rotation) is applied afterwards, so the two compose cleanly.
        GLES20.glUniformMatrix4fv(uMatrixLoc, 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(uFitLoc, 1, false, fitMatrix, 0)
        // The enum ordinal selects the branch inside the shader. Passing an int
        // keeps one program instead of recompiling per filter, which is what makes
        // switching filters feel instant.
        GLES20.glUniform1i(uFilterLoc, filter.ordinal)
        GLES20.glUniform1f(uIntensityLoc, intensity)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(
            aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer
        )

        vertexBuffer.position(VERTICES.size / 2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameLock) { frameAvailable = true }
    }

    /**
     * Recompute the centre-crop UV transform.
     *
     * ## Why the old approach was wrong
     *
     * It derived the crop from the buffer size alone, assuming
     * `SurfaceTexture.getTransformMatrix()` always carries the device rotation.
     * Whether it does depends on `TransformationInfo.hasCameraTransform()`: when
     * the camera has already rotated the frame the matrix is identity, and when
     * it has not, the matrix rotates by 90 or 270 degrees. Guessing either way
     * crops the wrong axis and the image tears diagonally.
     *
     * ## What it does now
     *
     * The rotation is supplied by CameraX via [setCameraFrame], so the effective
     * (post-rotation) frame size is known exactly. The crop is then expressed in
     * *texture* space and applied to the UVs, which composes cleanly with
     * whatever `matrix` the SurfaceTexture happens to hold: rotate first, crop
     * second. `PreviewView` solves the same problem with its own
     * `PreviewTransformation`, also driven by `TransformationInfo`.
     */
    private fun updateFitMatrix() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (frameWidth <= 0 || frameHeight <= 0) return

        val swapped = rotationDegrees == 90 || rotationDegrees == 270
        val effW = if (swapped) frameHeight else frameWidth
        val effH = if (swapped) frameWidth else frameHeight

        val viewAspect = viewportWidth.toFloat() / viewportHeight
        val frameAspect = effW.toFloat() / effH.toFloat()

        val sx: Float
        val sy: Float
        if (frameAspect > viewAspect) {
            // Frame is wider than the view: crop the sides.
            sx = viewAspect / frameAspect
            sy = 1f
        } else {
            // Frame is taller than the view: crop top and bottom.
            sx = 1f
            sy = viewAspect / frameAspect
        }

        android.opengl.Matrix.setIdentityM(fitMatrix, 0)
        fitMatrix[0] = sx
        fitMatrix[5] = sy
    }

    /**
     * Tell the renderer what the camera is actually sending.
     *
     * Called from the CameraX surface provider, which is the only place that
     * knows the rotation and the crop. Guessing these from the buffer size is
     * what produced a torn preview.
     */
    fun setCameraFrame(resolution: android.util.Size, rotationDegrees: Int, cropRect: android.graphics.Rect) {
        frameWidth = resolution.width
        frameHeight = resolution.height
        this.rotationDegrees = rotationDegrees
        Log.d(TAG, "camera frame " + frameWidth + "x" + frameHeight + ", rotation=" + rotationDegrees + ", crop=" + cropRect)
    }

    // ---------------------------------------------------------------- shaders

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)

        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("shader link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val TAG = "LiveFilterRenderer"

        /**
         * Preview buffer size. 1280x720 matches the sensor aspect the phone
         * reports and is small enough to keep the GL upload cheap; the display
         * is filled by scaling, not by more pixels.
         */
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720

        /** Full-screen quad: position (x,y) then texture coordinate (u,v). */
        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 a_position;
            attribute vec4 a_texCoord;
            uniform mat4 u_matrix;   // SurfaceTexture transform (rotation)
            uniform mat4 u_fit;      // centre-crop to the viewport aspect ratio
            varying vec2 v_texCoord;
            void main() {
                // The quad always fills the viewport; the crop is applied to
                // the texture coordinates instead. Scaling UVs crops the image,
                // scaling the quad distorts it, and only one of those composes
                // correctly with the rotation that u_matrix carries.
                gl_Position = a_position;
                vec2 cropped = (u_fit * vec4(a_texCoord.xy, 0.0, 1.0)).xy;
                v_texCoord = (u_matrix * vec4(cropped, 0.0, 1.0)).xy;
            }
        """

        /**
         * One shader, seven looks.
         *
         * Colour operations run in *linear* light: the frame is decoded from sRGB,
         * filtered, then re-encoded. Applying the matrix directly to sRGB values -
         * which most filter implementations do because it looks punchier - is not a
         * colour operation, and on a measuring tool it would shift the very
         * differences the instrument exists to report.
         */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_texCoord;
            uniform samplerExternalOES u_texture;
            uniform int u_filter;
            uniform float u_intensity;

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
                    outColor = vec3(sCurve(clamp(y + (lin.r - y) * 1.35, 0.0, 1.0)),
                                    sCurve(clamp(y + (lin.g - y) * 1.35, 0.0, 1.0)),
                                    sCurve(clamp(y + (lin.b - y) * 1.35, 0.0, 1.0)));
                } else if (u_filter == 3) {       // COOL
                    outColor = lin * vec3(0.93, 0.99, 1.10);
                } else if (u_filter == 4) {       // WARM
                    outColor = lin * vec3(1.10, 1.00, 0.90);
                } else if (u_filter == 5) {       // CONTRAST
                    float y = dot(lin, LUMA);
                    float c = sCurve(sCurve(sCurve(y)));
                    outColor = vec3(c);
                } else if (u_filter == 6) {       // INVERT (keep hue)
                    float y = max(dot(lin, LUMA), 0.0001);
                    outColor = lin * ((1.0 - y) / y);
                } else if (u_filter == 7) {       // CLIP warning
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

                // Intensity mixes back toward the original: the same `mix(color,
                // filtered, intensity)` every LUT shader uses, which is what makes
                // a filter slider feel natural.
                vec3 mixed = mix(lin, outColor, u_intensity);
                gl_FragColor = vec4(linearToSrgb(mixed), src.a);
            }
        """
    }
}
