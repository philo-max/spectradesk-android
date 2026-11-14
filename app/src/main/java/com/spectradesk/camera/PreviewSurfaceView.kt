package com.spectradesk.camera

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 取景器用的 SurfaceView。
 *
 * ## 为什么不用 PreviewView
 *
 * 接入 `SurfaceProcessor`（实时滤镜）后，CameraX 交给 PreviewView 的裁剪信息是
 * 竖屏的，而 effect 的输出缓冲是横向的 —— 两者互为转置。PreviewView 不知道
 * 画面已经被 GL 处理过，会再套一次自己的 `PreviewTransformation`，把已经画满的
 * 画面按竖屏比例又做了一次信箱（letterbox），结果就是上下两条黑边。
 *
 * 这个冲突在 PreviewView 内部无解。所以取景器换成自己持有 Surface 的
 * SurfaceView，几何 100% 由 GL 管线决定。
 *
 * ## 为什么是 SurfaceView 而不是 TextureView
 *
 * 输出已经是 EGL 窗口表面（零拷贝直接合成），TextureView 会强制多一次
 * 纹理拷贝到 View 层，多一次 1080p 上传，纯属浪费。
 *
 * ## 表面生命周期的坑
 *
 * `surfaceCreated` 往往在 GL 处理器就绪**之前**就触发（视图先布局，相机后绑定）。
 * 所以这里必须记住当前表面，等外部准备好时主动来取，而不是假设回调时机。
 */
class PreviewSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** 当前有效的表面；null 表示还没就绪或已销毁。 */
    @Volatile
    var currentSurface: android.view.Surface? = null
        private set

    /** 当前表面的像素尺寸。 */
    @Volatile
    var surfaceWidth: Int = 0
        private set

    @Volatile
    var surfaceHeight: Int = 0
        private set

    /** 表面就绪或尺寸变化时回调。注册时会立即用当前值回调一次。 */
    var onSurfaceAvailable: ((android.view.Surface, Int, Int) -> Unit)? = null
        set(value) {
            field = value
            // 注册时补发一次：调用方通常晚于 surfaceCreated 才准备好。
            val s = currentSurface
            if (value != null && s != null && surfaceWidth > 0 && surfaceHeight > 0) {
                value(s, surfaceWidth, surfaceHeight)
            }
        }

    /** 表面尺寸变化。 */
    var onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null

    init {
        holder.addCallback(this)
        // 不让系统按缩放自己挑缓冲区尺寸：我们要它严格等于 View 的像素尺寸，
        // 否则画面只铺中间一块，上下露出背景（看起来就是黑边）。
        holder.setSizeFromLayout()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val f = holder.surfaceFrame
        currentSurface = holder.surface
        surfaceWidth = f.width()
        surfaceHeight = f.height()
        android.util.Log.i(
            "PreviewSurfaceView",
            "surfaceCreated frame=${f.width()}x${f.height()} view=${width}x$height",
        )
        onSurfaceAvailable?.invoke(holder.surface, surfaceWidth, surfaceHeight)
    }    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        currentSurface = holder.surface
        surfaceWidth = width
        surfaceHeight = height
        android.util.Log.i(
            "PreviewSurfaceView",
            "surfaceChanged ${width}x$height view=${this.width}x${this.height}",
        )
        onSurfaceSizeChanged?.invoke(width, height)
        // 尺寸变化后也要让外部重新挂一遍（EGL 表面要按新尺寸重建）。
        onSurfaceAvailable?.invoke(holder.surface, width, height)
    }

    /**
     * 让 Surface 的缓冲区尺寸等于 View 的实际像素尺寸。
     *
     * SurfaceView 默认按窗口的缩放/布局自己挑一个缓冲区尺寸，可能比 View 小
     * （实测 View 是 1016x1724，缓冲区却只有 1016x1294），于是画面只占中间
     * 一块，上下露出 View 的背景色 —— 看起来就是黑边。
     *
     * `setFixedSize` 把缓冲区钉死成 View 的尺寸，EGL 就能铺满整个 View。
     */
    fun matchBufferToView() {
        if (width > 0 && height > 0) {
            holder.setFixedSize(width, height)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 布局尺寸变了，缓冲区跟着变，否则又会留边。
        matchBufferToView()
        // 有些机型改了 fixedSize 之后不会立刻回调 surfaceChanged，
        // 这里主动通知一次，保证 EGL 按新尺寸重画。
        if (w > 0 && h > 0) {
            surfaceWidth = w
            surfaceHeight = h
            onSurfaceSizeChanged?.invoke(w, h)
            currentSurface?.let { onSurfaceAvailable?.invoke(it, w, h) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        currentSurface = null
        surfaceWidth = 0
        surfaceHeight = 0
    }
}
