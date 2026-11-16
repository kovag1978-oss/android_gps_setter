package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.jqssun.gpssetter.utils.PrefManager
import timber.log.Timber

internal class RealLocationTracker(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var startedAt = 0L
    private var last: Location? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!PrefManager.isStarted || !PrefManager.isSystemHooked) {
                if (!location.isFromMockProvider && location.elapsedRealtimeNanos >= startedAt) {
                    last = Location(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        manager.removeUpdates(listener)
        last = null
        startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter(manager::isProviderEnabled)
                .forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
        } catch (exception: SecurityException) {
            Timber.w(exception, "Cannot track real location")
            manager.removeUpdates(listener)
        } catch (exception: IllegalArgumentException) {
            Timber.w(exception, "Cannot track real location")
            manager.removeUpdates(listener)
        }
    }

    fun current(): MapCoordinates? {
        if (PrefManager.isStarted && PrefManager.isSystemHooked) return null
        val location = last ?: return null
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age < 0 || age > 30_000_000_000L) return null
        return MapCoordinates(location.latitude, location.longitude)
    }

    fun stop() {
        manager.removeUpdates(listener)
        last = null
    }
}
