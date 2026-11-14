package com.spectradesk.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 把水印画进照片。
 *
 * ## 为什么预览和存盘要共用这个类
 *
 * 预览用的是 Compose 的 overlay（文字在 UI 层），存盘必须把同样的内容
 * 画进 JPEG。如果两套代码各写一遍排版，边距、字号、行距迟早不一致，
 * 用户会发现"预览在左下，成片偏了一点"。
 *
 * 所以这里只负责**几何**：给定画布尺寸和水印内容，算出每一行的位置、
 * 字号、底板矩形。Compose 那边按同一套比例画，[draw] 这边按同一套比例
 * 落到位图上。
 *
 * ## 为什么用相对尺寸
 *
 * 照片可能是 4096×2304，也可能是 1920×1080，预览更小。所有尺寸都按
 * 短边的比例算（[SIZE_RATIO] 等常量），所以同一份布局在任何分辨率下
 * 观感一致 —— 这也是水印相机的做法。
 */
object WatermarkRenderer {

    /** 字号相对短边的比例。实测 0.028 在 4096 宽的照片上约 64px，手机上正好。 */
    private const val SIZE_RATIO = 0.028f
    /** 边距相对短边。 */
    private const val MARGIN_RATIO = 0.035f
    /** 行距倍数。 */
    private const val LINE_SPACING = 1.35f
    /** 卡片底板内边距。 */
    private const val PAD_RATIO = 0.014f
    /** 卡片圆角。 */
    private const val RADIUS_RATIO = 0.018f

    /** 计算出的单行排版信息。 */
    data class Line(val text: String, val baselineY: Float)

    /**
     * 排版结果：字号、行位置、以及（卡片版式的）底板矩形。
     *
     * 预览层也读这个结构，所以两边不会漂移。
     */
    data class Layout(
        val textSize: Float,
        val margin: Float,
        val lines: List<Line>,
        /** 卡片底板；简洁版式为 null。 */
        val card: RectF?,
        /** 文字锚点 X（右对齐时是右边界）。 */
        val anchorX: Float,
        val rightAligned: Boolean,
    )

    fun layout(
        width: Int,
        height: Int,
        w: Watermark,
        content: WatermarkContent,
    ): Layout? {
        val lines = content.lines(w)
        if (lines.isEmpty()) return null
        val short = minOf(width, height).toFloat()
        val textSize = short * SIZE_RATIO
        val margin = short * MARGIN_RATIO
        val lineHeight = textSize * LINE_SPACING
        val pad = short * PAD_RATIO

        // 纵向：从底部或顶部往内排。
        val totalH = lineHeight * lines.size
        val firstBaseline = if (w.corner.isBottom) {
            height - margin - totalH + textSize
        } else {
            margin + textSize
        }
        val rows = lines.mapIndexed { i, s ->
            Line(s, firstBaseline + i * lineHeight)
        }

        val card = if (w.style == Watermark.Style.CARD) {
            // 底板宽度按最长行估算：中文字宽约等于字号，英文约 0.55。
            // 用 0.62 的保守系数，宁可宽一点也不要字露在底板外。
            val longest = lines.maxOf { it.length }
            val estW = longest * textSize * 0.62f
            val left = if (w.corner.isRight) width - margin - estW - pad * 2
            else margin - pad
            val top = rows.first().baselineY - textSize - pad
            RectF(left, top, left + estW + pad * 2, top + totalH + pad * 2)
        } else {
            null
        }

        val anchorX = if (w.corner.isRight) width - margin else margin
        return Layout(textSize, margin, rows, card, anchorX, w.corner.isRight)
    }

    /**
     * 把水印画进位图。返回新位图（不改原图，因为原图还要用于测量）。
     *
     * @param location 地点文本，null 表示不显示地点行
     */
    fun draw(
        source: Bitmap,
        w: Watermark,
        content: WatermarkContent,
    ): Bitmap {
        if (!w.enabled) return source
        val l = layout(source.width, source.height, w, content) ?: return source

        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val canvas = Canvas(out)

        // 卡片底板：半透明黑，圆角。
        l.card?.let { r ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 0, 0, 0)
            }
            val radius = minOf(source.width, source.height) * RADIUS_RATIO
            canvas.drawRoundRect(r, radius, radius, p)
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = l.textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            color = Color.WHITE
            textAlign = if (l.rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
            // 描边：纯白字在浅色背景上看不清，加一层半透明黑边保证可读。
            // 卡片版式已经有底板，但描边仍然有用（照片本身可能过曝）。
            style = Paint.Style.FILL
            setShadowLayer(l.textSize * 0.12f, 0f, 0f, Color.argb(200, 0, 0, 0))
        }
        for (row in l.lines) {
            canvas.drawText(row.text, l.anchorX, row.baselineY, text)
        }
        return out
    }
}
