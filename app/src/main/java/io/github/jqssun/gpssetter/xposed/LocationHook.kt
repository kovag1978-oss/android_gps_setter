package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    private const val pi = 3.14159265359
    private const val COORDINATE_SCALE = 10_000_000.0
    private var accuracy: Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0
    private var appSpoofEnabled = false
    private var systemSpoofEnabled = false
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)
    private data class SyntheticUpdate(val handler: Handler, val runnable: Runnable)
    private val syntheticUpdates = Collections.synchronizedMap(
        IdentityHashMap<Any, SyntheticUpdate>()
    )

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }

    private fun updateLocation(latitude: Double = settings.getLat, longitude: Double = settings.getLng) {
        try {
            mLastUpdated = System.currentTimeMillis()
            if (settings.isRandomPosition) {
                val distance = sqrt(rand.nextDouble()) * settings.randomPositionRadius
                val bearing = rand.nextDouble() * 2.0 * pi
                val angularDistance = distance / earth
                val latitudeRadians = Math.toRadians(latitude)
                val longitudeRadians = Math.toRadians(longitude)
                val offsetLatitude = asin(
                    sin(latitudeRadians) * cos(angularDistance) +
                        cos(latitudeRadians) * sin(angularDistance) * cos(bearing)
                )
                val offsetLongitude = longitudeRadians + atan2(
                    sin(bearing) * sin(angularDistance) * cos(latitudeRadians),
                    cos(angularDistance) - sin(latitudeRadians) * sin(offsetLatitude)
                )
                newlat = Math.toDegrees(offsetLatitude)
                newlng = (Math.toDegrees(offsetLongitude) + 540.0) % 360.0 - 180.0
            } else {
                newlat = latitude
                newlng = longitude
            }
            newlat = round(newlat * COORDINATE_SCALE) / COORDINATE_SCALE
            newlng = round(newlng * COORDINATE_SCALE) / COORDINATE_SCALE
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

    private fun createSyntheticLocation(packageName: String, provider: String): Location? {
        if (!updateAppLocationIfNeeded(packageName)) return null
        return Location(provider).apply {
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            latitude = newlat
            longitude = newlng
            altitude = 0.0
            speed = 0F
            accuracy = this@LocationHook.accuracy
            speedAccuracyMetersPerSecond = 0F
        }
    }

    private fun startSyntheticLocationUpdates(
        callback: Any,
        packageName: String,
        provider: String,
        looper: Looper?,
        executor: Executor?,
        deliver: (Location) -> Unit
    ) {
        val handler = Handler(looper ?: Looper.getMainLooper())
        val firstDelivery = AtomicBoolean(true)
        lateinit var update: SyntheticUpdate
        val runnable = object : Runnable {
            override fun run() {
                if (synchronized(syntheticUpdates) { syntheticUpdates[callback] !== update }) return
                val location = createSyntheticLocation(packageName, provider)
                if (location == null) {
                    stopSyntheticLocationUpdates(callback)
                    return
                }
                try {
                    val delivery = Runnable {
                        try {
                            deliver(location)
                            if (firstDelivery.compareAndSet(true, false)) {
                                XposedBridge.log(
                                    "LocationHook: first synthetic location delivered in $packageName"
                                )
                            }
                        } catch (exception: Throwable) {
                            XposedBridge.log(
                                "LocationHook: synthetic callback failed in $packageName $exception"
                            )
                            stopSyntheticLocationUpdates(callback)
                        }
                    }
                    if (executor != null) executor.execute(delivery) else handler.post(delivery)
                } catch (exception: Throwable) {
                    XposedBridge.log("LocationHook: synthetic update failed in $packageName $exception")
                    stopSyntheticLocationUpdates(callback)
                    return
                }
                handler.postDelayed(this, SYNTHETIC_LOCATION_INTERVAL_MILLIS)
            }
        }
        update = SyntheticUpdate(handler, runnable)
        synchronized(syntheticUpdates) {
            syntheticUpdates.remove(callback)?.let { previous ->
                previous.handler.removeCallbacks(previous.runnable)
            }
            syntheticUpdates[callback] = update
        }
        handler.post(runnable)
        XposedBridge.log("LocationHook: synthetic location updates enabled in $packageName")
    }

    private fun stopSyntheticLocationUpdates(callback: Any) {
        val update = synchronized(syntheticUpdates) { syntheticUpdates.remove(callback) } ?: return
        update.handler.removeCallbacks(update.runnable)
    }

    private fun locationCallbackMethod(callback: Any): String? {
        val methods = callback.javaClass.methods
        return when {
            methods.any { it.name == "onLocationResult" && it.parameterTypes.size == 1 } ->
                "onLocationResult"
            methods.any {
                it.name == "onLocationChanged" &&
                    it.parameterTypes.contentEquals(arrayOf(Location::class.java))
            } -> "onLocationChanged"
            else -> null
        }
    }

    private fun isLocationRequestCancelled(arguments: Array<Any?>): Boolean =
        arguments.filterNotNull().any { argument ->
            val methodName = when {
                argument.javaClass.methods.any {
                    it.name == "isCancellationRequested" && it.parameterTypes.isEmpty()
                } -> "isCancellationRequested"
                argument.javaClass.methods.any {
                    it.name == "isCanceled" && it.parameterTypes.isEmpty()
                } -> "isCanceled"
                else -> return@any false
            }
            try {
                XposedHelpers.callMethod(argument, methodName) as? Boolean == true
            } catch (exception: Throwable) {
                XposedBridge.log("LocationHook: cannot inspect location cancellation state $exception")
                false
            }
        }

    private fun hookLocationManagerUpdates(lpparam: XC_LoadPackage.LoadPackageParam) {
        val locationManagerClass = XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)
        locationManagerClass.declaredMethods
            .filter { it.name == "requestLocationUpdates" || it.name == "removeUpdates" }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        if (method.name == "removeUpdates") {
                            param.args.filterNotNull().forEach(::stopSyntheticLocationUpdates)
                            return
                        }
                        val listener = param.args.firstOrNull { it is LocationListener } ?: return
                        startSyntheticLocationUpdates(
                            listener,
                            lpparam.packageName,
                            LocationManager.GPS_PROVIDER,
                            param.args.filterIsInstance<Looper>().firstOrNull(),
                            param.args.filterIsInstance<Executor>().firstOrNull()
                        ) { location ->
                            XposedHelpers.callMethod(listener, "onLocationChanged", location)
                        }
                    }
                })
            }
    }

    private fun hookLocationManagerCurrentLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val locationManagerClass = XposedHelpers.findClass("android.location.LocationManager", lpparam.classLoader)
        locationManagerClass.declaredMethods
            .filter { it.name == "getCurrentLocation" }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || isLocationRequestCancelled(param.args)) return
                        val consumer = param.args.firstOrNull {
                            it != null && it.javaClass.methods.any { callback ->
                                callback.name == "accept" && callback.parameterTypes.size == 1
                            }
                        } ?: return
                        val location = createSyntheticLocation(
                            lpparam.packageName,
                            LocationManager.GPS_PROVIDER
                        ) ?: return
                        val delivery = Runnable {
                            try {
                                XposedHelpers.callMethod(consumer, "accept", location)
                            } catch (exception: Throwable) {
                                XposedBridge.log(
                                    "LocationHook: synthetic current-location callback failed in " +
                                        "${lpparam.packageName} $exception"
                                )
                            }
                        }
                        val executor = param.args.filterIsInstance<Executor>().firstOrNull()
                        if (executor != null) {
                            try {
                                executor.execute(delivery)
                            } catch (exception: Throwable) {
                                XposedBridge.log(
                                    "LocationHook: synthetic current-location dispatch failed in " +
                                        "${lpparam.packageName} $exception"
                                )
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post(delivery)
                        }
                    }
                })
            }
    }

    private fun hookFusedLocationClient(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clientClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.FusedLocationProviderClient",
            lpparam.classLoader
        ) ?: return
        val methods = mutableListOf<java.lang.reflect.Method>()
        var currentClass: Class<*>? = clientClass
        while (currentClass != null) {
            methods += currentClass.declaredMethods
            currentClass = currentClass.superclass
        }
        var hookedMethods = 0
        methods.distinctBy {
            it.name + it.parameterTypes.joinToString { parameter -> parameter.name }
        }.filterNot { java.lang.reflect.Modifier.isAbstract(it.modifiers) }.forEach { method ->
            when (method.name) {
                "requestLocationUpdates" -> {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null || isLocationRequestCancelled(param.args)) return
                            val callback = param.args.firstOrNull {
                                it != null && locationCallbackMethod(it) != null
                            } ?: return
                            val callbackMethod = locationCallbackMethod(callback) ?: return
                            startSyntheticLocationUpdates(
                                callback,
                                lpparam.packageName,
                                "fused",
                                param.args.filterIsInstance<Looper>().firstOrNull(),
                                param.args.filterIsInstance<Executor>().firstOrNull()
                            ) { location ->
                                if (callbackMethod == "onLocationResult") {
                                    val resultClass = XposedHelpers.findClass(
                                        "com.google.android.gms.location.LocationResult",
                                        lpparam.classLoader
                                    )
                                    val result = XposedHelpers.callStaticMethod(
                                        resultClass,
                                        "create",
                                        listOf(location)
                                    )
                                    XposedHelpers.callMethod(callback, callbackMethod, result)
                                } else {
                                    XposedHelpers.callMethod(callback, callbackMethod, location)
                                }
                            }
                        }
                    })
                    hookedMethods++
                }
                "removeLocationUpdates" -> {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable == null) {
                                param.args.filterNotNull().forEach(::stopSyntheticLocationUpdates)
                            }
                        }
                    })
                    hookedMethods++
                }
                "getLastLocation", "getCurrentLocation" -> {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null ||
                                (method.name == "getCurrentLocation" && isLocationRequestCancelled(param.args))
                            ) return
                            val location = createSyntheticLocation(lpparam.packageName, "fused") ?: return
                            try {
                                val tasksClass = XposedHelpers.findClass(
                                    "com.google.android.gms.tasks.Tasks",
                                    lpparam.classLoader
                                )
                                param.result = XposedHelpers.callStaticMethod(tasksClass, "forResult", location)
                            } catch (exception: Throwable) {
                                XposedBridge.log(
                                    "LocationHook: unable to return synthetic fused location in " +
                                        "${lpparam.packageName} $exception"
                                )
                            }
                        }
                    })
                    hookedMethods++
                }
            }
        }
        if (hookedMethods > 0) {
            XposedBridge.log("LocationHook: hooked $hookedMethods fused location method(s) in ${lpparam.packageName}")
        }
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

            // Last hop before the location leaves system_server, covers every listener and PendingIntent.
            val transportMethods = managerClass.declaredClasses
                .flatMap { it.declaredMethods.toList() }
                .filter { method ->
                    method.name == "deliverOnLocationChanged" && !java.lang.reflect.Modifier.isAbstract(method.modifiers) &&
                        method.parameterTypes.any { resultClass.isAssignableFrom(it) }
                }
            transportMethods.forEach { XposedBridge.hookMethod(it, hook) }
            XposedBridge.log("LocationHook: hooked ${transportMethods.size} deliverOnLocationChanged")
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
            hookRadioSignals(lpparam)
            if (!ignorePkg.contains(lpparam.packageName)) {
                hookLocationManagerUpdates(lpparam)
                hookLocationManagerCurrentLocation(lpparam)
                hookFusedLocationClient(lpparam)
            }
        }
    }

    // MapKit-like locators also position by Wi-Fi scans and cell towers; hide them while spoofing.
    private fun hookRadioSignals(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        var count = 0
        fun hideAll(className: String, methodName: String, result: Any?) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                val hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (updateAppLocationIfNeeded(pkg)) param.result = result
                    }
                }
                count += XposedBridge.hookAllMethods(clazz, methodName, hook).size
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: radio hook $className.$methodName failed $e")
            }
        }

        hideAll("android.net.wifi.WifiManager", "getScanResults", emptyList<Any>())
        hideAll("android.net.wifi.WifiManager", "startScan", false)
        hideAll("android.telephony.TelephonyManager", "getAllCellInfo", emptyList<Any>())
        hideAll("android.telephony.TelephonyManager", "getNeighboringCellInfo", emptyList<Any>())
        hideAll("android.telephony.TelephonyManager", "getCellLocation", null)
        hideAll("android.telephony.PhoneStateListener\$IPhoneStateListenerStub", "onCellInfoChanged", null)
        hideAll("android.telephony.PhoneStateListener\$IPhoneStateListenerStub", "onCellLocationChanged", null)
        hideAll("android.telephony.TelephonyCallback\$IPhoneStateListenerStub", "onCellInfoChanged", null)
        hideAll("android.telephony.TelephonyCallback\$IPhoneStateListenerStub", "onCellLocationChanged", null)

        try {
            val clazz = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!updateAppLocationIfNeeded(pkg)) return
                    val executor = param.args.getOrNull(0) as? java.util.concurrent.Executor ?: return
                    val callback = param.args.getOrNull(1) ?: return
                    executor.execute {
                        try {
                            XposedHelpers.callMethod(callback, "onCellInfo", emptyList<Any>())
                        } catch (e: Throwable) {
                            XposedBridge.log("LocationHook: cell callback failed $e")
                        }
                    }
                    param.result = null
                }
            }
            count += XposedBridge.hookAllMethods(clazz, "requestCellInfoUpdate", hook).size
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: requestCellInfoUpdate hook failed $e")
        }
        XposedBridge.log("LocationHook: hooked $count radio method(s) in $pkg")
    }

    private const val SYNTHETIC_LOCATION_INTERVAL_MILLIS = 1_000L
}
