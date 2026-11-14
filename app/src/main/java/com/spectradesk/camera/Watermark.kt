package com.spectradesk.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印样式。
 *
 * ## 为什么是"贴纸"而不是"烧进像素"
 *
 * 参考水印相机类应用（今日水印相机、马克水印相机）的做法：水印是**独立的
 * 绘制层**，不是把字画进相机帧。三个理由：
 *
 * 1. **能改。** 拍完发现时间写错、地点漏了，可以重渲染；烧进像素就回不去了。
 * 2. **不进测量。** 这台设备本职是测色，水印绝不能让一个色块进数字。
 * 3. **可关。** 用户想拍干净照片时一键关掉，不用重拍。
 *
 * 但**存盘时**必须真的合成进 JPEG —— 否则分享出去就没了。所以这里是
 * "预览用 overlay 画，存盘用 Canvas 合成"，两条路共用同一份
 * [WatermarkLayout] 布局参数，保证所见即所得。
 *
 * ## 为什么分四角 + 两种版式
 *
 * 水印相机类应用的共同点：位置可选（四角/居中）、版式分「单行」和
 * 「卡片」（带半透明底板）。卡片版式在杂乱背景上可读性远好于纯文字，
 * 这是它成为默认的原因。
 */
data class Watermark(
    val enabled: Boolean = false,
    /** 显示哪些字段。 */
    val showTime: Boolean = true,
    val showLocation: Boolean = true,
    val showCustom: Boolean = true,
    /** 自定义文字（项目名、备注等）。 */
    val customText: String = "",
    /** 位置。 */
    val corner: Corner = Corner.BOTTOM_LEFT,
    /** 版式。 */
    val style: Style = Style.CARD,
    /** 时间格式。 */
    val timePattern: String = "yyyy-MM-dd HH:mm:ss",
) {
    enum class Corner(val label: String) {
        TOP_LEFT("左上"),
        TOP_RIGHT("右上"),
        BOTTOM_LEFT("左下"),
        BOTTOM_RIGHT("右下"),
        ;

        /** 归一化锚点：0 = 左/上，1 = 右/下。 */
        val isRight: Boolean get() = this == TOP_RIGHT || this == BOTTOM_RIGHT
        val isBottom: Boolean get() = this == BOTTOM_LEFT || this == BOTTOM_RIGHT
    }

    enum class Style(val label: String) {
        /** 纯文字 + 描边，轻。 */
        PLAIN("简洁"),
        /** 半透明底板 + 圆角，杂乱背景上也能读。 */
        CARD("卡片"),
        ;

        companion object {
            fun fromName(name: String?): Style =
                entries.firstOrNull { it.name == name } ?: CARD
        }
    }

    companion object {
        fun cornerFromName(name: String?): Corner =
            Corner.entries.firstOrNull { it.name == name } ?: Corner.BOTTOM_LEFT
    }
}

/**
 * 把 [Watermark] + 当前时间/地点渲染成要画的几行文字。
 *
 * 单独抽出来是为了**预览和存盘共用同一份内容**。如果两边各拼一次字符串，
 * 迟早会出现"预览显示 12:30，成片写着 12:29"这种差一秒的问题 ——
 * 时间是在这里取一次的，两边都用它。
 */
data class WatermarkContent(
    val timeLine: String,
    val locationLine: String,
    val customLine: String,
) {
    /** 实际要画的行，按顺序。空行自动省略。 */
    fun lines(w: Watermark): List<String> = buildList {
        if (w.showTime && timeLine.isNotEmpty()) add(timeLine)
        if (w.showLocation && locationLine.isNotEmpty()) add(locationLine)
        if (w.showCustom && customLine.isNotEmpty()) add(customLine)
    }

    val isEmpty: Boolean
        get() = timeLine.isEmpty() && locationLine.isEmpty() && customLine.isEmpty()

    companion object {
        /**
         * 按当前时刻生成。存盘时调用一次，把结果同时喂给预览和合成，
         * 保证两边时间戳一致。
         */
        fun now(
            w: Watermark,
            location: String? = null,
            clock: Date = Date(),
        ): WatermarkContent {
            val fmt = runCatching { SimpleDateFormat(w.timePattern, Locale.getDefault()) }
                .getOrElse { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
            return WatermarkContent(
                timeLine = fmt.format(clock),
                locationLine = location ?: "",
                customLine = w.customText.trim(),
            )
        }
    }
}
