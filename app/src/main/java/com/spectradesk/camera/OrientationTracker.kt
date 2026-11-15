package com.spectradesk.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 设备物理重力朝向与电子水平仪监听（对标 Moto Camera `MultiSensorOrientationEventListener`
 * + `RotateLayout` + `LevelerView` 与抖音拍摄页重力传感器适配架构）。
 *
 * ## 核心设计原则
 *
 * 1. **Activity 保持竖屏锁定 (`screenOrientation="portrait"`)**：
 *    手机旋转时绝不触发系统 Activity 销毁重建，从而保证：
 *    - 正在录制的视频绝不中断；
 *    - OpenGL 渲染管线与 CameraX 会话不黑屏、不重绑；
 *    - 即使系统下拉栏里的「自动旋转」被用户锁死，相机内部依然能根据重力感应自由切换横竖屏！
 * 2. **双传感器融合 (`TYPE_GRAVITY` 优先 + `TYPE_ACCELEROMETER` 低通滤波兜底)**：
 *    优先使用硬件融合的重力传感器消除手部线性抖动；无重力传感器机型自动退回一阶低通滤波加速度计。
 * 3. **防抖迟滞死区 (`±15°` Hysteresis) + 平放天顶保护 (`onPlanarPositionChanged`)**：
 *    - 只有旋转越过 `55°` 阈值才切入新象限，彻底杜绝在 `45°` 临界角附近手抖导致 UI 疯狂横跳；
 *    - 当手机平放在桌面或俯拍（`hypot(gx, gy) * 2 < |gz|`）时自动锁定当前横竖屏朝向并进入「天顶俯拍」模式。
 * 4. **实时输出 `LevelerView` 连续倾角 (`tiltDegrees`)**：
 *    输出相对当前基准轴（0°/90°/180°/270°）的精确倾角偏差，驱动取景器内的电子水平仪。
 */
class OrientationTracker(context: Context) : SensorEventListener {

    /**
     * 设备的四个基准握持朝向。
     *
     * @param degrees 物理顺时针转角（0 / 90 / 180 / 270）
     * @param uiRotationDegrees 竖屏锁定窗口内 UI 控件需要补偿旋转的角度（0f / +90f / 180f / -90f）
     * @param label 状态栏与面板完整文案
     * @param shortLabel 取景器角标精简文案
     */
    enum class DeviceOrientation(
        val degrees: Int,
        val uiRotationDegrees: Float,
        val label: String,
        val shortLabel: String,
    ) {
        PORTRAIT(0, 0f, "竖屏", "竖屏 0°"),
        LANDSCAPE_LEFT(90, 90f, "横屏·左", "横屏 90°"),
        PORTRAIT_REVERSED(180, 180f, "竖屏·倒", "倒竖 180°"),
        LANDSCAPE_RIGHT(270, -90f, "横屏·右", "横屏 270°"),
        ;

        val isLandscape: Boolean
            get() = this == LANDSCAPE_LEFT || this == LANDSCAPE_RIGHT

        /** 对应的 `Surface.ROTATION_*` 常量，供 CameraX `ImageCapture` / `VideoCapture` 写入正确方向。 */
        val surfaceRotation: Int
            get() = when (this) {
                PORTRAIT -> Surface.ROTATION_0
                LANDSCAPE_LEFT -> Surface.ROTATION_90
                PORTRAIT_REVERSED -> Surface.ROTATION_180
                LANDSCAPE_RIGHT -> Surface.ROTATION_270
            }

        companion object {
            /** 无状态角度映射（45° 分界）。 */
            fun fromDegrees(d: Int): DeviceOrientation = when (((d % 360) + 360) % 360) {
                in 45..134 -> LANDSCAPE_LEFT
                in 135..224 -> PORTRAIT_REVERSED
                in 225..314 -> LANDSCAPE_RIGHT
                else -> PORTRAIT
            }

            /**
             * 带迟滞死区（Hysteresis）的象限判定：
             * 距离当前朝向中心未超过 55°（即 45° + 10° 死区）时保持原朝向，防止临界角抖动。
             */
            fun fromDegreesWithHysteresis(
                normDegrees: Float,
                current: DeviceOrientation,
                hysteresisDeg: Float = 10f,
            ): DeviceOrientation {
                val diffFromCurrent = angularDistance(normDegrees, current.degrees.toFloat())
                if (diffFromCurrent <= 45f + hysteresisDeg) {
                    return current
                }
                return fromDegrees(normDegrees.roundToInt())
            }

            fun angularDistance(a: Float, b: Float): Float {
                val raw = abs(((a - b) % 360f + 540f) % 360f - 180f)
                return raw
            }
        }
    }

    /**
     * 实时重力感应快照，驱动横竖屏 UI 旋转与电子水平仪。
     */
    data class GravityState(
        val orientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
        /** 相对当前 0°/90°/180°/270° 基准线的水平倾角偏差（-45° .. +45°）。 */
        val tiltDegrees: Float = 0f,
        /** 是否已处于精确水平状态（误差 <= 1.5° 且非平放）。 */
        val isLeveled: Boolean = true,
        /** 手机是否处于桌面平放 / 垂直俯拍状态（onPlanarPositionChanged）。 */
        val isFlat: Boolean = false,
    )

    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var orientationListener: ((DeviceOrientation) -> Unit)? = null
    private var gravityListener: ((GravityState) -> Unit)? = null

    @Volatile
    var orientation: DeviceOrientation = DeviceOrientation.PORTRAIT
        private set

    @Volatile
    var gravityState: GravityState = GravityState()
        private set

    private var lpfInitialized = false
    private var lpfX = 0f
    private var lpfY = 9.81f
    private var lpfZ = 0f
    private var lastEmitMs = 0L

    /**
     * 启动重力感应监听。优先绑定硬件 `TYPE_GRAVITY`，不支持时回退 `TYPE_ACCELEROMETER`。
     */
    fun start(
        onChange: (DeviceOrientation) -> Unit = {},
        onGravity: ((GravityState) -> Unit)? = null,
    ) {
        orientationListener = onChange
        gravityListener = onGravity
        lpfInitialized = false
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(this)
        orientationListener = null
        gravityListener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        if (type != Sensor.TYPE_GRAVITY && type != Sensor.TYPE_ACCELEROMETER) return

        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = if (event.values.size > 2) event.values[2] else 0f

        // TYPE_ACCELEROMETER 含手部线性加速度噪声，用一阶低通滤波平滑重力分量
        val gx: Float
        val gy: Float
        val gz: Float
        if (type == Sensor.TYPE_ACCELEROMETER) {
            if (!lpfInitialized) {
                lpfX = rawX
                lpfY = rawY
                lpfZ = rawZ
                lpfInitialized = true
            } else {
                val alpha = 0.22f
                lpfX += alpha * (rawX - lpfX)
                lpfY += alpha * (rawY - lpfY)
                lpfZ += alpha * (rawZ - lpfZ)
            }
            gx = lpfX
            gy = lpfY
            gz = lpfZ
        } else {
            gx = rawX
            gy = rawY
            gz = rawZ
        }

        // 平放检测（对应 Moto Camera MultiSensorOrientationEventListener.onPlanarPositionChanged）：
        // 当水平面投影不足 Z 轴分量的 1/2（倾角 < ~26°）时视为平放俯拍，锁定上一次的横竖屏朝向不乱跳。
        val xyMag = hypot(gx.toDouble(), gy.toDouble()).toFloat()
        val flat = xyMag * 2.0f < abs(gz)

        val nextOrientation: DeviceOrientation
        val tilt: Float
        if (flat || xyMag < 0.8f) {
            nextOrientation = orientation
            // 平放时用 X 轴微倾角反映左右水平度
            tilt = (atan2(gx.toDouble(), abs(gz).coerceAtLeast(1f).toDouble()) * (180.0 / Math.PI))
                .toFloat()
                .coerceIn(-45f, 45f)
        } else {
            // Android 传感器坐标系：
            // 竖直正握 (PORTRAIT) 时：gx ≈ 0, gy ≈ +9.81 → atan2(gx, gy) = 0°
            // 逆时针横握 90° (LANDSCAPE_LEFT，手机顶部朝左) 时：gx ≈ +9.81, gy ≈ 0 → atan2(gx, gy) = +90°
            // 倒置竖握 180° (PORTRAIT_REVERSED) 时：gx ≈ 0, gy ≈ -9.81 → atan2(gx, gy) = 180°
            // 顺时针横握 90° (LANDSCAPE_RIGHT，手机顶部朝右) 时：gx ≈ -9.81, gy ≈ 0 → atan2(gx, gy) = -90° (270°)
            val exactDeg = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
            val normDeg = ((exactDeg % 360f) + 360f) % 360f
            nextOrientation = DeviceOrientation.fromDegreesWithHysteresis(normDegrees = normDeg, current = orientation)

            var delta = normDeg - nextOrientation.degrees.toFloat()
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            tilt = delta.coerceIn(-45f, 45f)
        }

        val leveled = !flat && abs(tilt) <= 1.5f
        val orientationChanged = nextOrientation != orientation
        if (orientationChanged) {
            orientation = nextOrientation
            orientationListener?.invoke(nextOrientation)
        }

        val now = System.currentTimeMillis()
        val prev = gravityState
        val tiltChangedEnough = abs(tilt - prev.tiltDegrees) >= 0.35f
        if (orientationChanged || flat != prev.isFlat || leveled != prev.isLeveled ||
            (tiltChangedEnough && now - lastEmitMs >= 40L)
        ) {
            lastEmitMs = now
            val nextState = GravityState(
                orientation = nextOrientation,
                tiltDegrees = tilt,
                isLeveled = leveled,
                isFlat = flat,
            )
            gravityState = nextState
            gravityListener?.invoke(nextState)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
