package io.github.jqssun.gpssetter.utils

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jqssun.gpssetter.R
class NotificationsChannel{

    private fun createChannelIfNeeded(context: Context) {
        NotificationChannelCompat.Builder("active.location", NotificationManager.IMPORTANCE_HIGH).apply {
            setName(context.getString(R.string.active_location))
            setDescription(context.getString(R.string.des))
        }.build().also {
            NotificationManagerCompat.from(context).createNotificationChannel(it)
        }
    }

    private fun createNotification(context: Context, options: (NotificationCompat.Builder) -> Unit): Notification {
        createChannelIfNeeded(context)
        return NotificationCompat.Builder(context, "active.location").apply { options(this) }.build()
    }

    fun showNotification(context: Context, options: (NotificationCompat.Builder) -> Unit): Notification {
        val notification = createNotification(context, options)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ACTIVE_LOCATION_NOTIFICATION_ID, notification)
        return notification
    }

    fun showLocationNotification(context: Context, address: String) {
        showLocationNotification(context, null, address)
    }

    fun showLocationNotification(context: Context, packageName: String?, address: String) {
        val notification = createNotification(context) {
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(context.getString(R.string.active_location))
            it.setContentText(address)
            it.setOngoing(true)
            it.setCategory(Notification.CATEGORY_SERVICE)
            it.priority = NotificationCompat.PRIORITY_MAX
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (packageName == null) {
            notificationManager.notify(ACTIVE_LOCATION_NOTIFICATION_ID, notification)
        } else {
            notificationManager.notify(managedLocationNotificationTag(packageName), ACTIVE_LOCATION_NOTIFICATION_ID, notification)
        }
    }

    fun cancelLocationNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ACTIVE_LOCATION_NOTIFICATION_ID)
    }

    fun cancelManagedLocationNotification(context: Context, packageName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(managedLocationNotificationTag(packageName), ACTIVE_LOCATION_NOTIFICATION_ID)
    }

    companion object {
        private const val ACTIVE_LOCATION_NOTIFICATION_ID = 123
        private fun managedLocationNotificationTag(packageName: String) = "managed_location:$packageName"
    }
}