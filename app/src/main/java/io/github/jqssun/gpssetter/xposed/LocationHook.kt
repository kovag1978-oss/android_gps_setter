package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import kotlin.math.cos

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    private const val pi = 3.14159265359
    private var accuracy: Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0
    private var appSpoofEnabled = false
    private var systemSpoofEnabled = false
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }

    private fun updateLocation(latitude: Double = settings.getLat, longitude: Double = settings.getLng) {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * latitude / 180.0))
            newlat =
                if (settings.isRandomPosition) latitude + (dlat * 180.0 / pi) else latitude
            newlng =
                if (settings.isRandomPosition) longitude + (dlng * 180.0 / pi) else longitude
            accuracy = settings.accuracy!!.toFloat()

        } catch (e: Exception) {
            Timber.tag("GPS Setter")
                .e(e, "Failed to get XposedSettings for %s", context.packageName)
        }
    }

    private fun updateAppLocationIfNeeded(packageName: String): Boolean {
        if (ignorePkg.contains(packageName)) return false
        if (System.currentTimeMillis() - mLastUpdated > 80) {
            settings.reload()
            val profile = settings.appLocationProfile(packageName)
            appSpoofEnabled = profile != null || settings.isStarted
            if (appSpoofEnabled) {
                if (profile == null) {
                    updateLocation()
                } else {
                    updateLocation(profile.latitude, profile.longitude)
                }
            } else {
                mLastUpdated = System.currentTimeMillis()
            }
        }
        return appSpoofEnabled
    }

    private fun updateSystemLocationIfNeeded(): Boolean {
        if (System.currentTimeMillis() - mLastUpdated > 200) {
            settings.reload()
            systemSpoofEnabled = settings.isStarted && settings.isHookedSystem
            if (systemSpoofEnabled) {
                updateLocation()
            } else {
                mLastUpdated = System.currentTimeMillis()
            }
        }
        return systemSpoofEnabled
    }

    private fun spoofedCopy(origin: Location): Location {
        val location = Location(origin)
        location.latitude = newlat
        location.longitude = newlng
        location.altitude = 0.0
        location.speed = 0F
        location.accuracy = accuracy
        location.speedAccuracyMetersPerSecond = 0F
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            XposedBridge.log("LocationHook: unable to set mock $e")
        }
        return location
    }

    // Covers locations delivered to listeners (including PASSIVE requests), not only getLastLocation.
    private fun hookProviderDelivery(classLoader: ClassLoader) {
        try {
            val managerClass = XposedHelpers.findClass(
                "com.android.server.location.provider.LocationProviderManager",
                classLoader
            )
            val resultClass = XposedHelpers.findClass("android.location.LocationResult", classLoader)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val result = param.args.firstOrNull { resultClass.isInstance(it) } ?: return
                    if (!updateSystemLocationIfNeeded()) return
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val origins = XposedHelpers.callMethod(result, "asList") as List<Location>
                        val spoofed = origins.map { spoofedCopy(it) }
                        val index = param.args.indexOf(result)
                        param.args[index] = XposedHelpers.callStaticMethod(resultClass, "create", spoofed)
                    } catch (e: Throwable) {
                        XposedBridge.log("LocationHook: unable to spoof delivered location $e")
                    }
                }
            }
            val methods = managerClass.declaredMethods.filter { method ->
                method.name == "onReportLocation" && method.parameterTypes.any { resultClass.isAssignableFrom(it) }
            }
            methods.forEach { XposedBridge.hookMethod(it, hook) }
            XposedBridge.log("LocationHook: hooked ${methods.size} onReportLocation method(s)")

            // Cached real location is handed to new listeners right on registration; spoof it too.
            val lastHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val origin = param.result as? Location ?: return
                    if (!updateSystemLocationIfNeeded()) return
                    param.result = spoofedCopy(origin)
                }
            }
            val lastMethods = managerClass.declaredMethods.filter {
                it.name == "getLastLocationUnsafe" && Location::class.java.isAssignableFrom(it.returnType)
            }
            lastMethods.forEach { XposedBridge.hookMethod(it, lastHook) }

            val registrationClass = XposedHelpers.findClass(
                "com.android.server.location.provider.LocationProviderManager\$Registration",
                classLoader
            )
            val acceptMethods = managerClass.declaredClasses
                .filter { registrationClass.isAssignableFrom(it) }
                .flatMap { it.declaredMethods.toList() }
                .filter { method ->
                    method.name == "acceptLocationChange" && !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        method.parameterTypes.any { resultClass.isAssignableFrom(it) }
                }
            acceptMethods.forEach { XposedBridge.hookMethod(it, hook) }
            XposedBridge.log(
                "LocationHook: hooked ${lastMethods.size} getLastLocationUnsafe, ${acceptMethods.size} acceptLocationChange"
            )
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: provider delivery hook failed $e")
        }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        if (lpparam.packageName == "android") {
        settings.reload()
        if (settings.isHookedSystem && !ignorePkg.contains(lpparam.packageName)) {

            if (Build.VERSION.SDK_INT < 34) {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.LocationManagerService",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass, "getLastLocation",
                    LocationRequest::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!updateSystemLocationIfNeeded()) return
                        val location = Location(LocationManager.GPS_PROVIDER)
                            location.time = System.currentTimeMillis() - 300
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = 0F
                            location.accuracy = accuracy
                            location.speedAccuracyMetersPerSecond = 0F
                            param.result = location
                        }
                    }
                )

                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.returnType == Boolean::class.java) {
                        if (method.name == "addGnssBatchingCallback" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = false
                                    }
                                }
                            )
                        }
                    }
                }

                XposedHelpers.findAndHookMethod(
                    "com.android.server.LocationManagerService.Receiver", // com.android.server.LocationManagerService\$Receiver
                    lpparam.classLoader,
                    "callLocationChangedLocked",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!updateSystemLocationIfNeeded()) return
                            lateinit var location: Location
                            lateinit var originLocation: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                            } else {
                                originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.accuracy = accuracy
                                location.bearing = originLocation.bearing
                                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = 0F
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                )
            } else {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.location.LocationManagerService",
                    lpparam.classLoader
                )
                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                        // params: String::class.java, LastLocationRequest::class.java, String::class.java, String::class.java
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (!updateSystemLocationIfNeeded()) return
                                    val location = Location(LocationManager.GPS_PROVIDER)
                                    location.time = System.currentTimeMillis() - 300
                                    location.latitude = newlat
                                    location.longitude = newlng
                                    location.altitude = 0.0
                                    location.speed = 0F
                                    location.accuracy = accuracy
                                    location.speedAccuracyMetersPerSecond = 0F
                                    param.result = location
                                }
                            }
                        )
                    } else if (method.returnType == Void::class.java) {
                        if (method.name == "startGnssBatch" ||
                            method.name == "addGnssAntennaInfoListener" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = null
                                    }
                                }
                            )
                        }
                    }
                }
                hookProviderDelivery(lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass,
                    "injectLocation",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!updateSystemLocationIfNeeded()) return
                            lateinit var location: Location
                            lateinit var originLocation: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                            } else {
                                originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.accuracy = accuracy
                                location.bearing = originLocation.bearing
                                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = 0F
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                )
            }
        }
        } else { // application hook

            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )

            for (method in LocationClass.declaredMethods) {
                if (method.name == "getLatitude") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (updateAppLocationIfNeeded(lpparam.packageName)) {
                                    param.result = newlat
                                }
                            }
                        }
                    )
                } else if (method.name == "getLongitude") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (updateAppLocationIfNeeded(lpparam.packageName)) {
                                    param.result = newlng
                                }
                            }
                        }
                    )
                } else if (method.name == "getAccuracy") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (updateAppLocationIfNeeded(lpparam.packageName)) {
                                    param.result = accuracy
                                }
                            }
                        }
                    )
                }
            }

            // Locations leave this process through Parcel (e.g. GMS fused client -> app), bypassing getters.
            try {
                val parcelMethod = LocationClass.getDeclaredMethod(
                    "writeToParcel", android.os.Parcel::class.java, Int::class.javaPrimitiveType
                )
                XposedBridge.hookMethod(parcelMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!updateAppLocationIfNeeded(lpparam.packageName)) return
                        try {
                            val copy = Location(param.thisObject as Location)
                            copy.latitude = newlat
                            copy.longitude = newlng
                            copy.altitude = 0.0
                            copy.speed = 0F
                            copy.accuracy = accuracy
                            copy.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.invokeOriginalMethod(parcelMethod, copy, param.args)
                            param.result = null
                        } catch (e: Throwable) {
                            XposedBridge.log("LocationHook: unable to spoof parcel $e")
                        }
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: parcel hook failed $e")
            }

            XposedHelpers.findAndHookMethod(
                LocationClass,
                "set",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {

                        if (updateAppLocationIfNeeded(lpparam.packageName)) {
                            lateinit var location: Location
                            lateinit var originLocation: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                            } else {
                                originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.accuracy = accuracy
                                location.bearing = originLocation.bearing
                                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = 0F
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                lpparam.classLoader,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (updateAppLocationIfNeeded(lpparam.packageName)) {
                            val provider = param.args[0] as String
                            val location = Location(provider)
                            location.time = System.currentTimeMillis() - 300
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = 0F
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.result = location
                        }
                    }
                }
            )
        }
    }
}
