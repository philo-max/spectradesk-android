package com.spectradesk.camera

/**
 * 录像与取景分辨率档位（从 720p 滑动到 8K）。
 *
 * - 只保存纯 Kotlin 的像素宽高 [width] × [height]，不直接持有 Android/CameraX 类型，
 *   以保证纯 JVM 单元测试加载 [CameraSettings] 时不会触发 `NoClassDefFoundError`。
 * - 在真机运行时由 [CameraController] 向当前设备的 `SCALER_STREAM_CONFIGURATION_MAP`
 *   与 `VideoCapabilities` 实时查询硬件原生支持情况：
 *   支持 8K (7680×4320) 或 2K (2560×1440) 的设备自动解锁无 `*` 标记；
 *   超出当前设备/镜头硬件上限的档位统一标 `*`，底层以设备最高原生画质采集并叠加 CAS 超清算法增强。
 */
enum class VideoResolution(
    val label: String,
    val width: Int,
    val height: Int,
) {
    HD("720p", 1280, 720),
    FHD("1080p", 1920, 1080),
    QHD("2K", 2560, 1440),
    UHD("4K", 3840, 2160),
    UHD_8K("8K", 7680, 4320),
    ;
}

/**
 * 软件支持的帧率滑动档位（最高 240fps）。
 *
 * 具体哪几档在当前设备/镜头上可用，完全由 [CameraController] 在绑定相机时
 * 向硬件实时查询，不写死任何机型规则：当前设备不支持的档位统一在界面标 `*`。
 */
val VIDEO_FPS_TIERS = listOf(24, 30, 60, 90, 120, 240)

/**
 * 算法高清 / 超清画质增强档位（参考抖音 BMF HydraSharp V2 + PackageDenoiseV4 + OneKeyHDR 管线）。
 *
 * 通过 GPU 着色器单遍完成：
 * 1. **Y 亮度域 3×3 双边保边降噪（PackageDenoise）**：平抑暗部与平坦区 CMOS 噪点，不糊边缘；
 * 2. **HydraSharp 各向同性 3×3 自适应锐化**：带 `threshold` 噪声死区门限、`lc_weight_thr` 局部反差自适应权重、
 *    以及 `over_ratio` 抗光晕过冲钳位（彻底消除传统锐化的白边/黑边光晕与斜线锯齿）；
 * 3. **多尺度微反差重构（SuperRes Multi-Scale）**：2.0× 邻域中频纹理提振；
 * 4. **OneKeyHDR 感知动态范围增强（AesBoost）**：暗部细节提亮 + 高光柔和滚降 + 自适应自然饱和度。
 */
enum class ClarityMode(val label: String, val desc: String, val strength: Float) {
    OFF("标准", "原画直出，不做算法增强", 0f),
    HD("高清", "HydraSharp 亮度域自适应锐化 + 3×3 双边降噪", 0.55f),
    ULTRA("超清", "多尺度微反差重构 + 抗光晕过冲抑制（4K/8K推荐）", 1.0f),
    HDR_ENHANCE("超清HDR", "HydraSharp 超清 + OneKeyHDR 暗部提亮与质感增强", 1.35f),
}

/**
 * 闪光灯 / 补光模式（对标系统相机 `FrontDisplayFlash` 与抖音拍摄补光逻辑）：
 * - 后置镜头（有硬件 LED）：支持 关闭 / 自动闪光 / 拍照闪光(录像自动转常亮) / 常亮手电补光。
 * - 前置镜头（无物理 LED）：自动切换为「屏幕柔光环补光」（拉满屏幕峰值亮度 + 环形柔光边框 + 快门瞬间补光脉冲）。
 */
enum class FlashMode(val label: String, val shortLabel: String) {
    OFF("关闭", "⚡ 关"),
    AUTO("自动", "⚡ 自动"),
    ON("开启", "⚡ 开启"),
    TORCH("常亮补光", "🔦 常亮"),
}

/**
 * 前置屏幕柔光环色温档位（对标 `com.motorola.camera.ui.widgets.gl.FrontDisplayFlash.mFlashColors` 与抖音前置补光色温）。
 */
enum class ScreenFlashTone(val label: String, val argb: Long) {
    WARM("暖阳柔光", 0xFFFFF1DC),
    NATURAL("自然冷白", 0xFFFFFFFF),
    PEACH("蜜桃粉光", 0xFFFFE4EC),
}

/** 参数面板分类。顺序就是 Tab 顺序：最常用的放最前。 */
enum class ParamsTab(val label: String) {
    LENS("镜头"),
    EXPOSURE("曝光"),
    VIDEO("录像"),
    LOOK("画面"),
    WATERMARK("水印"),
}

/**
 * 取景器上的对焦标记。
 *
 * [id] 每次点按递增 —— 同一个位置连点两次也要重新开始"2.5 秒后淡出"的计时。
 */
data class FocusMark(
    val x: Float,
    val y: Float,
    /** 长按锁定：不自动淡出，AE/AF 不再自动取消。 */
    val locked: Boolean,
    /** null = 还在对焦中；true/false = 结果。 */
    val success: Boolean?,
    val id: Long,
)
