package com.spectradesk.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * 设备物理朝向监听，用于横竖屏自适应。
 *
 * ## 为什么不用 Activity 的 configChanges 旋转
 *
 * 系统旋转有三个问题：
 *
 * 1. **慢。** 要走 Activity 重建或配置变更回调，用户手腕一转，画面要等
 *    几百毫秒才跟上，中间还会看到一次布局跳变。
 * 2. **会重建相机。** 配置变更触发 `LaunchedEffect` 重跑、`bind()` 重来，
 *    取景器黑一下。相机应用最忌讳这个。
 * 3. **用户锁了旋转就不工作。** 相机 App 必须无视系统旋转锁 —— 拍横构图
 *    是相机自己的事，和桌面方向无关。
 *
 * 所以这里直接读**重力/加速度传感器**，算出手机现在是竖的还是横的，
 * 自己决定 UI 布局和照片旋转。这也是所有相机应用的做法。
 *
 * ## 为什么用加速度计而不是旋转矢量
 *
 * 加速度计的重力分量足够判断四个方向，且延迟低、耗电小。旋转矢量需要
 * 融合磁力计，在相机旁边（有磁铁）会飘。
 */
class OrientationTracker(context: Context) : SensorEventListener {

    /** 当前朝向，只有四个值 —— 相机不需要倒置（180°）以外的判断。 */
    enum class DeviceOrientation(val degrees: Int, val label: String) {
        PORTRAIT(0, "竖屏"),
        LANDSCAPE_LEFT(90, "横屏·左"),
        PORTRAIT_REVERSED(180, "竖屏·倒"),
        LANDSCAPE_RIGHT(270, "横屏·右"),
        ;

        val isLandscape: Boolean
            get() = this == LANDSCAPE_LEFT || this == LANDSCAPE_RIGHT

        /** 对应的 Surface.ROTATION_* 常量，给 CameraX 的 targetRotation 用。 */
        val surfaceRotation: Int
            get() = when (this) {
                PORTRAIT -> Surface.ROTATION_0
                LANDSCAPE_LEFT -> Surface.ROTATION_90
                PORTRAIT_REVERSED -> Surface.ROTATION_180
                LANDSCAPE_RIGHT -> Surface.ROTATION_270
            }

        companion object {
            fun fromDegrees(d: Int): DeviceOrientation = when (((d % 360) + 360) % 360) {
                in 45..134 -> LANDSCAPE_LEFT
                in 135..224 -> PORTRAIT_REVERSED
                in 225..314 -> LANDSCAPE_RIGHT
                else -> PORTRAIT
            }
        }
    }

    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var listener: ((DeviceOrientation) -> Unit)? = null

    @Volatile
    var orientation: DeviceOrientation = DeviceOrientation.PORTRAIT
        private set

    /**
     * 开始监听。[onChange] 只在实际朝向变化时回调，不会每帧刷。
     */
    fun start(onChange: (DeviceOrientation) -> Unit) {
        listener = onChange
        val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        manager?.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        // 用 atan2 把重力向量换成角度。手机竖直握持时 y ≈ 9.8、x ≈ 0。
        // 角度按"屏幕上方指向"定义，所以取 (x, y) 而不是 (y, x)。
        val degrees = Math.toDegrees(kotlin.math.atan2(-x.toDouble(), -y.toDouble())).toInt()
        val next = DeviceOrientation.fromDegrees(degrees)
        if (next != orientation) {
            orientation = next
            listener?.invoke(next)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
