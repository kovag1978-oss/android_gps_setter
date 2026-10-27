package io.github.jqssun.gpssetter.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.repository.FavoriteRepository
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.VoiceCommandText
import io.github.jqssun.gpssetter.utils.VoiceCommandText.containsPhrase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class VoiceCommandResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_RESULTS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val phrases = intent
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    .orEmpty()
                    .map(VoiceCommandText::normalize)
                    .filter(String::isNotEmpty)

                if (phrases.isEmpty()) {
                    showMessage(context, R.string.voice_no_matches)
                    return@launch
                }

                if (PrefManager.isSeparateControlEnabled) {
                    val target = PrefManager.pendingVoiceTarget
                    if (target == null) {
                        if (phrases.any { it in VoiceCommandText.stopCommands(context) }) {
                            stopAllSpoofing(context)
                        } else if (!applyFavoriteToAllManagedApps(context, phrases)) {
                            selectTarget(context, phrases)
                        }
                    } else {
                        processTargetCommand(context, target, phrases)
                    }
                } else {
                    PrefManager.pendingVoiceTarget = null
                    processGlobalCommand(context, phrases)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.e(exception, "Failed to process voice location command")
                PrefManager.pendingVoiceTarget = null
                showMessage(context, R.string.voice_command_failed)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun applyFavoriteToAllManagedApps(
        context: Context,
        phrases: List<String>
    ): Boolean {
        val favorites = favoriteRepository(context).getAllFavoritesSnapshot()
        val favoriteByPhrase = favorites.mapNotNull { favorite ->
            favorite.address?.let(VoiceCommandText::normalize)?.takeIf(String::isNotEmpty)
                ?.let { normalizedAddress -> normalizedAddress to favorite }
        }
        val favorite = phrases.asSequence()
            .mapNotNull { phrase ->
                favoriteByPhrase
                    .filter { (address, _) -> phrase.containsPhrase(address) }
                    .singleOrNull()
                    ?.second
            }
            .firstOrNull { it.lat != null && it.lng != null }
            ?: return false

        val profiles = PrefManager.appLocationProfiles.filter { it.enabled }
        if (profiles.isEmpty()) {
            showMessage(context, R.string.no_managed_apps_enabled)
            return true
        }

        val latitude = requireNotNull(favorite.lat)
        val longitude = requireNotNull(favorite.lng)
        PrefManager.setAppLocations(profiles.map { it.packageName }.toSet(), latitude, longitude)
        PrefManager.pendingVoiceTarget = null
        profiles.forEach { profile ->
            showActiveLocationNotification(
                context,
                profile.packageName,
                "${profile.appName}: ${favorite.address.orEmpty()}"
            )
        }
        VoiceCommandTileService.requestStateUpdate(context)
        showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
        return true
    }

    private suspend fun selectTarget(context: Context, phrases: List<String>) {
        val profiles = PrefManager.appLocationProfiles.filter {
            it.enabled && it.voiceCommand.isNotBlank()
        }
        for (phrase in phrases) {
            val target = profiles.singleOrNull {
                VoiceCommandText.normalize(it.voiceCommand) == phrase
            }?.packageName ?: continue

            PrefManager.pendingVoiceTarget = target
            val label = profiles.first { it.packageName == target }.appName
            showMessage(
                context,
                R.string.voice_target_selected,
                label,
                context.getString(R.string.voice_stop_command)
            )
            delay(SECOND_STEP_DELAY_MILLIS)
            startNextRecognition(context)
            return
        }

        showMessage(context, R.string.voice_no_matches)
        startNextRecognition(context)
    }

    private suspend fun processTargetCommand(
        context: Context,
        target: String,
        phrases: List<String>
    ) {
        if (phrases.any { it in VoiceCommandText.stopCommands(context) }) {
            if (target == SYSTEM_TARGET) {
                disableGlobalSpoofing(context)
            } else {
                PrefManager.setAppLocation(target, start = false)
                NotificationsChannel().cancelManagedLocationNotification(context, target)
                PrefManager.pendingVoiceTarget = null
                updateTileAndNotification(context)
            }
            showMessage(context, R.string.voice_location_stopped)
            return
        }

        val favorites = favoriteRepository(context).getAllFavoritesSnapshot()
        if (favorites.isEmpty()) {
            PrefManager.pendingVoiceTarget = null
            showMessage(context, R.string.voice_no_favorites)
            return
        }

        val favoriteByPhrase = favorites.mapNotNull { favorite ->
            favorite.address?.let(VoiceCommandText::normalize)?.takeIf(String::isNotEmpty)
                ?.let { normalizedAddress -> normalizedAddress to favorite }
        }
        for (phrase in phrases) {
            val favorite = favoriteByPhrase
                .filter { (address, _) -> phrase.containsPhrase(address) }
                .singleOrNull()
                ?.second ?: continue
            val latitude = favorite.lat ?: continue
            val longitude = favorite.lng ?: continue

            val appProfile = if (target == SYSTEM_TARGET) {
                null
            } else {
                PrefManager.appLocationProfiles.firstOrNull {
                    it.packageName == target && it.enabled
                } ?: run {
                    PrefManager.pendingVoiceTarget = null
                    showMessage(context, R.string.app_profile_unavailable)
                    return
                }
            }

            if (appProfile == null) {
                PrefManager.update(start = true, la = latitude, ln = longitude)
                PrefManager.pendingVoiceTarget = null
                showActiveLocationNotification(context, favorite.address.orEmpty())
            } else {
                PrefManager.setAppLocation(target, start = true, latitude, longitude)
                PrefManager.pendingVoiceTarget = null
                showActiveLocationNotification(
                    context,
                    target,
                    "${appProfile.appName}: ${favorite.address.orEmpty()}"
                )
            }
            VoiceCommandTileService.requestStateUpdate(context)
            showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
            return
        }

        showMessage(context, R.string.voice_no_matches)
        startNextRecognition(context)
    }

    private suspend fun processGlobalCommand(context: Context, phrases: List<String>) {
        if (phrases.any { it in VoiceCommandText.stopCommands(context) }) {
            disableGlobalSpoofing(context)
            showMessage(context, R.string.voice_location_stopped)
            return
        }

        val favorites = favoriteRepository(context).getAllFavoritesSnapshot()
        if (favorites.isEmpty()) {
            showMessage(context, R.string.voice_no_favorites)
            return
        }

        val favoriteByPhrase = favorites.mapNotNull { favorite ->
            favorite.address?.let(VoiceCommandText::normalize)?.takeIf(String::isNotEmpty)
                ?.let { normalizedAddress -> normalizedAddress to favorite }
        }
        for (phrase in phrases) {
            val favorite = favoriteByPhrase
                .filter { (address, _) -> phrase.containsPhrase(address) }
                .singleOrNull()
                ?.second ?: continue
            val latitude = favorite.lat ?: continue
            val longitude = favorite.lng ?: continue

            PrefManager.update(start = true, la = latitude, ln = longitude)
            showActiveLocationNotification(context, favorite.address.orEmpty())
            VoiceCommandTileService.requestStateUpdate(context)
            showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
            return
        }

        showMessage(context, R.string.voice_no_matches)
        startNextRecognition(context)
    }

    private suspend fun disableGlobalSpoofing(context: Context) {
        PrefManager.update(start = false, la = PrefManager.getLat, ln = PrefManager.getLng)
        PrefManager.pendingVoiceTarget = null
        NotificationsChannel().cancelLocationNotification(context)
        updateTileAndNotification(context)
    }

    private suspend fun stopAllSpoofing(context: Context) {
        val notifications = NotificationsChannel()
        PrefManager.update(start = false, la = PrefManager.getLat, ln = PrefManager.getLng)
        PrefManager.appLocationProfiles
            .filter { it.started }
            .forEach { notifications.cancelManagedLocationNotification(context, it.packageName) }
        PrefManager.stopAllAppLocations()
        PrefManager.pendingVoiceTarget = null
        notifications.cancelLocationNotification(context)
        updateTileAndNotification(context)
        showMessage(context, R.string.all_locations_stopped)
    }

    private suspend fun updateTileAndNotification(context: Context) {
        withContext(Dispatchers.Main) {
            VoiceCommandTileService.requestStateUpdate(context)
        }
    }

    private suspend fun showActiveLocationNotification(context: Context, address: String) {
        showActiveLocationNotification(context, null, address)
    }

    private suspend fun showActiveLocationNotification(
        context: Context,
        packageName: String?,
        address: String
    ) {
        withContext(Dispatchers.Main) {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationsChannel().showLocationNotification(context, packageName, address)
            }
        }
    }

    private suspend fun showMessage(context: Context, messageResId: Int, vararg formatArgs: Any) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(messageResId, *formatArgs),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun startNextRecognition(context: Context) {
        withContext(Dispatchers.Main) {
            context.startActivity(
                VoiceCommandTileService.createRecognitionIntent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun favoriteRepository(context: Context): FavoriteRepository =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            VoiceCommandEntryPoint::class.java
        ).favoriteRepository()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VoiceCommandEntryPoint {
        fun favoriteRepository(): FavoriteRepository
    }

    companion object {
        private const val SECOND_STEP_DELAY_MILLIS = 2_500L
        private const val SYSTEM_TARGET = "@system"
        const val ACTION_PROCESS_RESULTS =
            "io.github.kovag1978_oss.gpssetter.action.PROCESS_VOICE_RESULTS"
    }
}
