package io.github.jqssun.gpssetter.ui

import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.AppLocationProfile

internal sealed class MapCommandResult {
    abstract val previous: MapCoordinates?
    data class Applied(val latitude: Double, val longitude: Double, override val previous: MapCoordinates? = null) : MapCommandResult()
    data class Stopped(override val previous: MapCoordinates? = null) : MapCommandResult()
}

data class MapCoordinates(val latitude: Double, val longitude: Double)

internal sealed class MapFocus {
    data class Applied(val point: MapCoordinates) : MapFocus()
    data class Remaining(val points: List<MapCoordinates>) : MapFocus()
    object RealLocation : MapFocus()
}

internal fun mapFocusFor(
    result: MapCommandResult,
    remaining: List<MapCoordinates>
): MapFocus = when (result) {
    is MapCommandResult.Applied ->
        MapFocus.Applied(MapCoordinates(result.latitude, result.longitude))
    is MapCommandResult.Stopped -> remaining.distinct().let { points ->
        if (points.isEmpty()) MapFocus.RealLocation else MapFocus.Remaining(points)
    }
}

internal const val SYSTEM_TARGET = "@system"

internal fun chooseActiveLocation(
    targets: Set<String>,
    system: MapCoordinates?,
    profiles: List<AppLocationProfile>
): MapCoordinates? {
    if (SYSTEM_TARGET in targets && system != null) {
        return system
    }
    return profiles
        .asSequence()
        .filter { it.enabled && it.started && it.packageName in targets }
        .sortedBy { it.packageName }
        .firstOrNull()
        ?.let { MapCoordinates(it.latitude, it.longitude) }
}

internal fun activeLocationForTargets(targets: Set<String>): MapCoordinates? =
    chooseActiveLocation(
        targets,
        if (PrefManager.isStarted) MapCoordinates(PrefManager.getLat, PrefManager.getLng) else null,
        PrefManager.appLocationProfiles
    )

internal fun allActiveTargets(): Set<String> =
    PrefManager.appLocationProfiles.filter { it.enabled && it.started }
        .map { it.packageName }.toSet() + SYSTEM_TARGET
