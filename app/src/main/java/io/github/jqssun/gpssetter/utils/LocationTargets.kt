package io.github.jqssun.gpssetter.utils

internal fun updatedLocationProfiles(
    profiles: List<AppLocationProfile>,
    packageNames: Set<String>,
    includeSystem: Boolean,
    latitude: Double,
    longitude: Double
): List<AppLocationProfile> {
    require(includeSystem || packageNames.isNotEmpty()) { "At least one location target is required" }
    val matched = profiles.filter { it.enabled && it.packageName in packageNames }
        .map { it.packageName }.toSet()
    check(matched == packageNames) { "One or more selected app profiles are missing or disabled" }
    return profiles.map {
        if (it.packageName in packageNames) it.copy(started = true, latitude = latitude, longitude = longitude)
        else it
    }
}
