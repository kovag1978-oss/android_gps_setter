package io.github.jqssun.gpssetter.utils

import org.json.JSONArray
import org.json.JSONObject

data class AppLocationProfile(
    val packageName: String,
    val appName: String,
    val voiceCommand: String = "",
    val enabled: Boolean = false,
    val started: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

object AppLocationProfileCodec {
    fun decode(value: String?): List<AppLocationProfile> {
        if (value.isNullOrBlank()) return emptyList()

        val profiles = JSONArray(value)
        return (0 until profiles.length()).map { index ->
            val profile = profiles.getJSONObject(index)
            AppLocationProfile(
                packageName = profile.getString("packageName"),
                appName = profile.getString("appName"),
                voiceCommand = profile.optString("voiceCommand"),
                enabled = profile.optBoolean("enabled"),
                started = profile.optBoolean("started"),
                latitude = profile.optDouble("latitude"),
                longitude = profile.optDouble("longitude")
            )
        }
    }

    fun encode(profiles: List<AppLocationProfile>): String =
        JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject()
                        .put("packageName", profile.packageName)
                        .put("appName", profile.appName)
                        .put("voiceCommand", profile.voiceCommand)
                        .put("enabled", profile.enabled)
                        .put("started", profile.started)
                        .put("latitude", profile.latitude)
                        .put("longitude", profile.longitude)
                )
            }
        }.toString()
}
