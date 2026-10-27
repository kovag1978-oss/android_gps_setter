package io.github.jqssun.gpssetter.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil

object LocationClusterIcon {
    fun create(hues: List<Float>, count: Int, density: Float): Bitmap {
        val width = (76f * density).toInt()
        val height = (84f * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = density
        val centerX = width / 2f
        val badgeRadius = 34f * scale

        canvas.drawCircle(
            centerX,
            badgeRadius + 2f * scale,
            badgeRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        canvas.drawCircle(
            centerX,
            badgeRadius + 2f * scale,
            badgeRadius - 2f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 45, 54) }
        )
        canvas.drawPath(
            Path().apply {
                moveTo(centerX - 10f * scale, 59f * scale)
                lineTo(centerX + 10f * scale, 59f * scale)
                lineTo(centerX, 76f * scale)
                close()
            },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        canvas.drawPath(
            Path().apply {
                moveTo(centerX - 7f * scale, 57f * scale)
                lineTo(centerX + 7f * scale, 57f * scale)
                lineTo(centerX, 70f * scale)
                close()
            },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 45, 54) }
        )

        val visibleHues = hues.distinct().take(MAX_VISIBLE_COLORS)
        val rows = ceil(visibleHues.size / COLUMN_COUNT.toFloat()).toInt()
        val dotSize = 18f * scale
        val horizontalGap = 21f * scale
        val verticalGap = 21f * scale
        val firstCenterY = if (rows == 1) 23f * scale else 16f * scale
        visibleHues.forEachIndexed { index, hue ->
            val row = index / COLUMN_COUNT
            val column = index % COLUMN_COUNT
            val rowCount = minOf(COLUMN_COUNT, visibleHues.size - row * COLUMN_COUNT)
            val rowWidth = (rowCount - 1) * horizontalGap
            val x = centerX - rowWidth / 2f + column * horizontalGap
            val y = firstCenterY + row * verticalGap
            val color = Color.HSVToColor(floatArrayOf(hue, 1f, 0.9f))
            canvas.drawCircle(
                x,
                y,
                dotSize / 2f + scale,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
            )
            canvas.drawCircle(
                x,
                y,
                dotSize / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            )
        }

        val label = if (hues.distinct().size > MAX_VISIBLE_COLORS) {
            "+${hues.distinct().size - MAX_VISIBLE_COLORS}"
        } else {
            count.toString()
        }
        canvas.drawText(
            label,
            centerX,
            55f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f * scale
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        return bitmap
    }

    private const val COLUMN_COUNT = 3
    private const val MAX_VISIBLE_COLORS = 6
}
