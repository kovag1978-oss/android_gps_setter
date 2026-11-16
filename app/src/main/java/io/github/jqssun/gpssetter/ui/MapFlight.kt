package io.github.jqssun.gpssetter.ui

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin

internal data class FlightFrame(val latitude: Double, val longitude: Double, val zoom: Double)

internal class MapFlight(
    private val from: MapCoordinates,
    private val to: MapCoordinates,
    width: Int,
    height: Int
) {
    private fun mercator(latitude: Double): Double {
        val rad = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        return (1.0 - ln((1.0 + sin(rad)) / (1.0 - sin(rad))) / (2.0 * PI)) / 2.0
    }

    private val startY = mercator(from.latitude)
    private val endY = mercator(to.latitude)
    private val longitudeDelta = ((to.longitude - from.longitude + 540.0) % 360.0) - 180.0
    private val overviewZoom = minOf(
        12.0,
        if (longitudeDelta == 0.0) 16.0 else log2(width.coerceAtLeast(1) * 0.7 / (256.0 * kotlin.math.abs(longitudeDelta) / 360.0)),
        if (startY == endY) 16.0 else log2(height.coerceAtLeast(1) * 0.7 / (256.0 * kotlin.math.abs(endY - startY)))
    ).coerceIn(0.0, 16.0)

    fun frame(progress: Float): FlightFrame {
        val t = progress.toDouble().coerceIn(0.0, 1.0)
        fun smooth(value: Double): Double = value * value * (3.0 - 2.0 * value)
        val travel = smooth(((t - 0.35) / 0.4).coerceIn(0.0, 1.0))
        val zoom = when {
            t < 0.10 -> 16.0
            t < 0.35 -> 16.0 + (overviewZoom - 16.0) * smooth((t - 0.1) / 0.25)
            t < 0.75 -> overviewZoom
            else -> overviewZoom + (16.0 - overviewZoom) * smooth((t - 0.75) / 0.25)
        }
        val y = startY + (endY - startY) * travel
        val latitude = Math.toDegrees(2.0 * atan(exp(PI * (1.0 - 2.0 * y))) - PI / 2.0)
        val longitude = ((from.longitude + longitudeDelta * travel + 540.0) % 360.0) - 180.0
        return FlightFrame(latitude, longitude, zoom)
    }
}
