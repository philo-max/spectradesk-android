package com.spectradesk.camera

/**
 * 手动调色参数 —— 参考剪映「调节」与醒图「调节」的分项组织。
 *
 * ## 为什么单独一层，不和 [LiveFilter] 混在一起
 *
 * [LiveFilter] 是**预设**：选一个名字，整张画面的观感就定了，内部是色彩运算。
 * [ColorGrade] 是**滑杆**：用户逐项微调，作用在预设之后的成品画面上。
 * 两者串联（预设 → 调色）才是剪辑软件的实际顺序，也符合用户心智：
 * 先挑风格，再调细节。
 *
 * ## 为什么在线性光之后、sRGB 域里做
 *
 * 这是本文件最重要的一个决定，值得写清楚。
 *
 * [LiveFilters] 与 GL 滤镜都在**线性光**里算，因为那是色彩学上正确的域：
 * 饱和度提升、S 曲线这类操作在线性光里才有物理意义，测色仪器必须这样。
 *
 * 但滑杆不是色彩学，是**感知控制**。剪映里「对比度 +50」和这里「对比度 +50」
 * 要看起来一样，而剪映是在 sRGB 域里做的。把同一个对比度公式搬到线性光里，
 * 中灰会被推得面目全非 —— 用户拉一下就会觉得"这滑杆不对"。
 *
 * 所以分工是：
 * * 预设滤镜 → 线性光（色彩保真）
 * * 手动调色 → sRGB 域（匹配用户预期）
 *
 * 这一层永远在**最后**作用于成品像素，而且**绝不进入测量**：测量读的是
 * ImageCapture 的原始输出，连 GL 管线都不经过。
 *
 * ## 默认值即恒等
 *
 * 所有字段默认 0（或 0f），[isIdentity] 为 true 时 shader 里整段跳过 ——
 * 不加调色时管线开销和以前完全一样。
 */
data class ColorGrade(
    /** 亮度，-1..1。sRGB 域加性偏移。 */
    val brightness: Float = 0f,
    /** 对比度，-1..1。围绕 0.5 中灰缩放。 */
    val contrast: Float = 0f,
    /** 饱和度，-1..1。-1 完全去色，+1 约 2 倍。 */
    val saturation: Float = 0f,
    /** 色温，-1..1。负=偏蓝，正=偏黄。 */
    val temperature: Float = 0f,
    /** 色调，-1..1。负=偏绿，正=偏品红。 */
    val tint: Float = 0f,
    /** 高光，-1..1。只作用于亮部，压高光救回天空。 */
    val highlights: Float = 0f,
    /** 阴影，-1..1。只作用于暗部，提阴影看清明暗细节。 */
    val shadows: Float = 0f,
    /** 锐化，0..1。 */
    val sharpen: Float = 0f,
    /** 暗角，0..1。 */
    val vignette: Float = 0f,
    /** 颗粒，0..1。胶片质感。 */
    val grain: Float = 0f,
) {
    /** 全零 = 不做任何事，shader 跳过整段。 */
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f &&
            temperature == 0f && tint == 0f && highlights == 0f &&
            shadows == 0f && sharpen == 0f && vignette == 0f && grain == 0f

    /** 有几项被改动过，用于界面上"已调色"的提示。 */
    val activeCount: Int
        get() = listOf(
            brightness, contrast, saturation, temperature, tint,
            highlights, shadows, sharpen, vignette, grain,
        ).count { it != 0f }

    /** 一键还原。 */
    fun reset(): ColorGrade = ColorGrade()

    /**
     * 每个参数一项，供 UI 用循环生成滑杆，避免十段几乎一样的代码。
     *
     * [get]/[set] 用函数而不是反射：这个类要能在 JVM 单测里跑，
     * 反射在 Android 上还有 R8 混淆的风险。
     */
    enum class Channel(
        val label: String,
        val min: Float,
        val max: Float,
        val get: (ColorGrade) -> Float,
        val set: (ColorGrade, Float) -> ColorGrade,
    ) {
        BRIGHTNESS("亮度", -1f, 1f, { it.brightness }, { g, v -> g.copy(brightness = v) }),
        CONTRAST("对比度", -1f, 1f, { it.contrast }, { g, v -> g.copy(contrast = v) }),
        SATURATION("饱和度", -1f, 1f, { it.saturation }, { g, v -> g.copy(saturation = v) }),
        TEMPERATURE("色温", -1f, 1f, { it.temperature }, { g, v -> g.copy(temperature = v) }),
        TINT("色调", -1f, 1f, { it.tint }, { g, v -> g.copy(tint = v) }),
        HIGHLIGHTS("高光", -1f, 1f, { it.highlights }, { g, v -> g.copy(highlights = v) }),
        SHADOWS("阴影", -1f, 1f, { it.shadows }, { g, v -> g.copy(shadows = v) }),
        SHARPEN("锐化", 0f, 1f, { it.sharpen }, { g, v -> g.copy(sharpen = v) }),
        VIGNETTE("暗角", 0f, 1f, { it.vignette }, { g, v -> g.copy(vignette = v) }),
        GRAIN("颗粒", 0f, 1f, { it.grain }, { g, v -> g.copy(grain = v) }),
        ;

        companion object {
            fun fromName(name: String?): Channel? = entries.firstOrNull { it.name == name }
        }
    }

    companion object {
        val NONE = ColorGrade()

        /**
         * 剪映/醒图风格的「一键」预设，方便用户不用逐项拉。
         *
         * 这些值不是拍脑袋：亮度/对比度各 +0.1 左右是肉眼刚好能看出但不"过"的量，
         * 色温 ±0.15 对应约 500K 的观感偏移。
         */
        val presets: List<Pair<String, ColorGrade>> = listOf(
            "原片" to ColorGrade(),
            "通透" to ColorGrade(contrast = 0.12f, saturation = 0.10f, highlights = -0.15f, shadows = 0.12f),
            "奶油" to ColorGrade(brightness = 0.06f, contrast = -0.08f, saturation = -0.05f,
                temperature = 0.12f, shadows = 0.18f, grain = 0.08f),
            "冷峻" to ColorGrade(contrast = 0.18f, saturation = -0.12f, temperature = -0.18f,
                highlights = -0.10f, shadows = -0.08f),
            "胶片" to ColorGrade(contrast = 0.10f, saturation = -0.08f, temperature = 0.08f,
                shadows = 0.14f, grain = 0.22f, vignette = 0.18f),
            "锐利" to ColorGrade(contrast = 0.14f, saturation = 0.06f, sharpen = 0.45f),
            "夜行" to ColorGrade(brightness = -0.06f, contrast = 0.20f, saturation = -0.18f,
                temperature = -0.12f, shadows = -0.12f, vignette = 0.30f),
        )
    }
}
