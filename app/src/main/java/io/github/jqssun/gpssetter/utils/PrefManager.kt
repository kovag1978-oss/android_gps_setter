package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp


@SuppressLint("WorldReadableFiles")
object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val SEPARATE_CONTROL = "separate_control"
    private const val APP_PROFILES = "app_location_profiles"
    private const val PENDING_VOICE_TARGET = "pending_voice_target"


    private val pref: SharedPreferences by lazy {
        try {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_WORLD_READABLE
            )
        }catch (e:SecurityException){
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_PRIVATE
            )
        }

    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    val getLat : Double
        get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
        get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    var isSystemHooked : Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, true)
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM,value).apply() }

    var isRandomPosition :Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    var mapType : Int
        get() = pref.getInt(MAP_TYPE,1)
        set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    var isSeparateControlEnabled: Boolean
        get() = pref.getBoolean(SEPARATE_CONTROL, false)
        set(value) {
            pref.edit()
                .putBoolean(SEPARATE_CONTROL, value)
                .remove(PENDING_VOICE_TARGET)
                .apply()
            if (!value) {
                appLocationProfiles
                    .filter { it.started }
                    .forEach { setAppLocation(it.packageName, start = false) }
            }
        }

    val appLocationProfiles: List<AppLocationProfile>
        get() = AppLocationProfileCodec.decode(pref.getString(APP_PROFILES, null))

    val hasActiveAppProfile: Boolean
        get() = appLocationProfiles.any { it.enabled && it.started }

    var pendingVoiceTarget: String?
        get() = pref.getString(PENDING_VOICE_TARGET, null)
        set(value) {
            val editor = pref.edit()
            if (value == null) {
                editor.remove(PENDING_VOICE_TARGET)
            } else {
                editor.putString(PENDING_VOICE_TARGET, value)
            }
            editor.apply()
        }

    fun saveAppLocationProfile(profile: AppLocationProfile) {
        val profiles = appLocationProfiles
            .filterNot { it.packageName == profile.packageName }
            .plus(profile)
        check(
            pref.edit()
                .putString(APP_PROFILES, AppLocationProfileCodec.encode(profiles))
                .commit()
        ) { "Failed to save app location profile" }
    }

    fun setAppLocation(packageName: String, start: Boolean, latitude: Double? = null, longitude: Double? = null) {
        val profile = appLocationProfiles.firstOrNull { it.packageName == packageName }
            ?: throw IllegalArgumentException("No location profile for $packageName")
        saveAppLocationProfile(
            profile.copy(
                started = start,
                latitude = latitude ?: profile.latitude,
                longitude = longitude ?: profile.longitude
            )
        )
    }

    fun setAppLocations(packageNames: Set<String>, latitude: Double, longitude: Double) {
        require(packageNames.isNotEmpty()) { "At least one app profile is required" }
        val profiles = appLocationProfiles
        val matchedPackages = profiles
            .filter { it.packageName in packageNames && it.enabled }
            .map { it.packageName }
            .toSet()
        check(matchedPackages == packageNames) {
            "One or more selected app profiles are missing or disabled"
        }
        saveAppLocationProfiles(
            profiles.map { profile ->
                if (profile.packageName in packageNames) {
                    profile.copy(started = true, latitude = latitude, longitude = longitude)
                } else {
                    profile
                }
            }
        )
    }

    fun stopAllAppLocations() {
        saveAppLocationProfiles(
            appLocationProfiles.map { profile ->
                if (profile.started) profile.copy(started = false) else profile
            }
        )
    }

    private fun saveAppLocationProfiles(profiles: List<AppLocationProfile>) {
        check(
            pref.edit()
                .putString(APP_PROFILES, AppLocationProfileCodec.encode(profiles))
                .commit()
        ) { "Failed to save app location profiles" }
    }

    fun update(start:Boolean, la: Double, ln: Double) {
        pref.edit()
            .putFloat(LATITUDE, la.toFloat())
            .putFloat(LONGITUDE, ln.toFloat())
            .putBoolean(START, start)
            .apply()
    }

}