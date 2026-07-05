package com.example.deepseekbalance.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

/**
 * 迷你趋势线绘制器。
 * 生成一张 Sparkline Bitmap，用于 Widget RemoteViews.setImageViewBitmap()。
 */
object SparklineDrawer {

    /**
     * 绘制 sparkline 并返回 Bitmap。
     *
     * @param values 数据点（按时间顺序）
     * @param width 输出位图宽度 px
     * @param height 输出位图高度 px
     * @param lineColor 线条颜色 (ARGB int)
     * @param fillColor 填充渐变起点的颜色
     */
    fun draw(
        values: List<Float>,
        width: Int,
        height: Int,
        lineColor: Int,
        fillColor: Int
    ): Bitmap? {
        if (values.size < 2 || width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val minVal = values.min()
        val maxVal = values.max()
        val range = maxVal - minVal
        val padY = height * 0.1f  // 10% padding top/bottom

        // 将值映射到像素坐标
        fun valueToY(value: Float): Float {
            return if (range == 0f) {
                height / 2f
            } else {
                height - padY - ((value - minVal) / range) * (height - 2 * padY)
            }
        }

        val stepX = width.toFloat() / (values.size - 1)

        // 构建折线 Path
        val linePath = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = valueToY(v)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        // 构建填充 Path（折线 + 底部闭合）
        val fillPath = Path(linePath)
        fillPath.lineTo(width.toFloat(), height.toFloat())
        fillPath.lineTo(0f, height.toFloat())
        fillPath.close()

        // 渐变：顶部半透明 → 底部全透明
        val gradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            fillColor,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        // 绘制填充
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = gradient
        }
        canvas.drawPath(fillPath, fillPaint)

        // 绘制线条
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = lineColor
            strokeWidth = 1.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(linePath, linePaint)

        return bitmap
    }
}
