package com.fieldlight.aihdr

import android.media.Image
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class SceneLuminance(
    val centerMean: Double,
    val borderMean: Double,
    val median: Int,
    val p95: Int,
    val clippedHighlightPercent: Double,
    val deepShadowPercent: Double,
    val targetEv: Double
) {
    fun summary(): String = buildString {
        append("CENTER ")
        append(centerMean.toInt())
        append("  BACKGROUND ")
        append(borderMean.toInt())
        append("  HIGHLIGHTS ")
        append(p95)
    }
}

/**
 * Lightweight regional luminance metering. It samples the Y plane only and
 * deliberately avoids allocating a bitmap or running a neural network.
 */
class AdaptiveSceneMeter {
    fun analyze(image: Image): SceneLuminance {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Scene meter requires a YUV_420_888 image."
        }
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val histogram = IntArray(256)
        var samples = 0
        var centerSum = 0L
        var centerCount = 0
        var borderSum = 0L
        var borderCount = 0
        var clipped = 0
        var shadows = 0

        // 4-pixel sampling keeps the analysis comfortably below a frame budget.
        val step = 4
        val centerLeft = width * 30 / 100
        val centerRight = width * 70 / 100
        val centerTop = height * 25 / 100
        val centerBottom = height * 75 / 100

        var y = 0
        while (y < height) {
            var x = 0
            val row = y * rowStride
            while (x < width) {
                val value = buffer.get(row + x * pixelStride).toInt() and 0xff
                histogram[value]++
                samples++
                if (value >= 245) clipped++
                if (value <= 12) shadows++
                if (x in centerLeft until centerRight && y in centerTop until centerBottom) {
                    centerSum += value
                    centerCount++
                } else {
                    borderSum += value
                    borderCount++
                }
                x += step
            }
            y += step
        }

        fun percentile(fraction: Double): Int {
            val target = (samples * fraction).toInt().coerceAtLeast(1)
            var accumulated = 0
            for (i in histogram.indices) {
                accumulated += histogram[i]
                if (accumulated >= target) return i
            }
            return 255
        }

        val centerMean = if (centerCount > 0) centerSum.toDouble() / centerCount else 112.0
        val borderMean = if (borderCount > 0) borderSum.toDouble() / borderCount else centerMean
        val median = percentile(0.50)
        val p95 = percentile(0.95)
        val clippedPercent = if (samples > 0) clipped * 100.0 / samples else 0.0
        val shadowPercent = if (samples > 0) shadows * 100.0 / samples else 0.0

        // Center-weighted exposure, with a highlight penalty to avoid sacrificing
        // the entire background when the center subject is darker.
        val centerEv = ln(112.0 / max(24.0, centerMean)) / ln(2.0)
        val globalEv = ln(112.0 / max(24.0, median.toDouble())) / ln(2.0)
        val backlightDifference = (borderMean - centerMean) / 100.0
        val highlightPenalty = when {
            p95 >= 252 && clippedPercent > 4.0 -> 0.55
            p95 >= 245 && clippedPercent > 1.5 -> 0.32
            p95 >= 235 -> 0.16
            else -> 0.0
        }
        val subjectBias = (backlightDifference * 0.18).coerceIn(-0.18, 0.32)
        val targetEv = (0.72 * centerEv + 0.28 * globalEv + subjectBias - highlightPenalty)
            .coerceIn(-1.3, 1.3)

        return SceneLuminance(
            centerMean = centerMean,
            borderMean = borderMean,
            median = median,
            p95 = p95,
            clippedHighlightPercent = clippedPercent,
            deepShadowPercent = shadowPercent,
            targetEv = targetEv
        )
    }
}
