package io.github.jqssun.gpssetter.utils

import android.content.SharedPreferences

internal fun commitLocationPreferences(
    pref: SharedPreferences,
    profilesKey: String,
    profiles: String?,
    startKey: String,
    latitudeKey: String,
    longitudeKey: String,
    includeSystem: Boolean,
    latitude: Float,
    longitude: Float
): Boolean {
    val oldProfiles = pref.getString(profilesKey, null)
    val hadStart = pref.contains(startKey)
    val oldStart = pref.getBoolean(startKey, false)
    val hadLatitude = pref.contains(latitudeKey)
    val oldLatitude = pref.getFloat(latitudeKey, 0f)
    val hadLongitude = pref.contains(longitudeKey)
    val oldLongitude = pref.getFloat(longitudeKey, 0f)
    val editor = pref.edit()
    if (profiles != null) editor.putString(profilesKey, profiles)
    if (includeSystem) {
        editor.putBoolean(startKey, true).putFloat(latitudeKey, latitude).putFloat(longitudeKey, longitude)
    }
    if (editor.commit()) return true

    val rollback = pref.edit()
    if (profiles != null) {
        if (oldProfiles == null) rollback.remove(profilesKey) else rollback.putString(profilesKey, oldProfiles)
    }
    if (includeSystem) {
        if (hadStart) rollback.putBoolean(startKey, oldStart) else rollback.remove(startKey)
        if (hadLatitude) rollback.putFloat(latitudeKey, oldLatitude) else rollback.remove(latitudeKey)
        if (hadLongitude) rollback.putFloat(longitudeKey, oldLongitude) else rollback.remove(longitudeKey)
    }
    check(rollback.commit()) { "Failed to save locations and restore previous preferences" }
    return false
}
