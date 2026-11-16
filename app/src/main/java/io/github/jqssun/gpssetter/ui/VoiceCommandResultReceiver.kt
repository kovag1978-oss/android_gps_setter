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

    private var mapResult: MapCommandResult? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_RESULTS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processResults(
                    context,
                    intent
                        .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        .orEmpty()
                ) { startNextRecognition(context) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processRecognizedPhrases(
        context: Context,
        recognizedPhrases: List<String>,
        startNextRecognition: suspend () -> Unit
    ): MapCommandResult? {
        try {
            val phrases = recognizedPhrases
                .map(VoiceCommandText::normalize)
                .filter(String::isNotEmpty)

            if (phrases.isEmpty()) {
                showMessage(context, R.string.voice_no_matches)
                return null
            }

            if (PrefManager.isSeparateControlEnabled) {
                val target = PrefManager.pendingVoiceTarget
                if (target == null) {
                    if (VoiceCommandText.containsSystemCommand(phrases, VoiceCommandText.systemCommand(context))) {
                        selectSystemTarget(context, startNextRecognition)
                    } else if (phrases.any { it in VoiceCommandText.stopCommands(context) }) {
                        stopAllSpoofing(context)
                    } else if (!applyFavoriteToAllManagedApps(context, phrases)) {
                        selectTarget(context, phrases, startNextRecognition)
                    }
                } else {
                    processTargetCommand(context, target, phrases, startNextRecognition)
                }
            } else {
                PrefManager.pendingVoiceTarget = null
                processGlobalCommand(context, phrases, startNextRecognition)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Timber.e(exception, "Failed to process voice location command")
            PrefManager.pendingVoiceTarget = null
            showMessage(context, R.string.voice_command_failed)
        }
        return mapResult
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
        val latitude = requireNotNull(favorite.lat)
        val longitude = requireNotNull(favorite.lng)
        val targets = profiles.map { it.packageName }.toSet()
        val previous = activeLocationForTargets(targets + SYSTEM_TARGET)
        PrefManager.applyLocations(targets, true, latitude, longitude)
        mapResult = MapCommandResult.Applied(latitude, longitude, previous)
        PrefManager.pendingVoiceTarget = null
        showActiveLocationNotification(context, favorite.address.orEmpty())
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

    private suspend fun selectSystemTarget(context: Context, startNextRecognition: suspend () -> Unit) {
        PrefManager.pendingVoiceTarget = SYSTEM_TARGET
        if (PrefManager.appLocationProfiles.any {
                it.enabled && VoiceCommandText.normalize(it.voiceCommand) == VoiceCommandText.systemCommand(context)
            }) {
            showMessage(context, R.string.app_voice_system_conflict)
        }
        showMessage(context, R.string.voice_target_selected, context.getString(R.string.voice_system_target),
            context.getString(R.string.voice_stop_command))
        delay(SECOND_STEP_DELAY_MILLIS)
        startNextRecognition()
    }

    private suspend fun selectTarget(
        context: Context,
        phrases: List<String>,
        startNextRecognition: suspend () -> Unit
    ) {
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
            startNextRecognition()
            return
        }

        showMessage(context, R.string.voice_no_matches)
        startNextRecognition()
    }

    private suspend fun processTargetCommand(
        context: Context,
        target: String,
        phrases: List<String>,
        startNextRecognition: suspend () -> Unit
    ) {
        if (phrases.any { it in VoiceCommandText.stopCommands(context) }) {
            val previous = activeLocationForTargets(setOf(target))
            if (target == SYSTEM_TARGET) {
                disableGlobalSpoofing(context)
            } else {
                PrefManager.setAppLocation(target, start = false)
                NotificationsChannel().cancelManagedLocationNotification(context, target)
                PrefManager.pendingVoiceTarget = null
                updateTileAndNotification(context)
            }
            showMessage(context, R.string.voice_location_stopped)
            mapResult = MapCommandResult.Stopped(previous)
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

            val previous = activeLocationForTargets(setOf(target))
            if (appProfile == null) {
                PrefManager.applyLocations(emptySet(), true, latitude, longitude)
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
            mapResult = MapCommandResult.Applied(latitude, longitude, previous)
            showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
            return
        }

        showMessage(context, R.string.voice_no_matches)
        startNextRecognition()
    }

    private suspend fun processGlobalCommand(
        context: Context,
        phrases: List<String>,
        startNextRecognition: suspend () -> Unit
    ) {
        if (phrases.any { it in VoiceCommandText.stopCommands(context) }) {
            val previous = activeLocationForTargets(setOf(SYSTEM_TARGET))
            disableGlobalSpoofing(context)
            showMessage(context, R.string.voice_location_stopped)
            mapResult = MapCommandResult.Stopped(previous)
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

            val previous = activeLocationForTargets(setOf(SYSTEM_TARGET))
            PrefManager.update(start = true, la = latitude, ln = longitude)
            showActiveLocationNotification(context, favorite.address.orEmpty())
            VoiceCommandTileService.requestStateUpdate(context)
            mapResult = MapCommandResult.Applied(latitude, longitude, previous)
            showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
            return
        }

        showMessage(context, R.string.voice_no_matches)
        startNextRecognition()
    }

    private suspend fun disableGlobalSpoofing(context: Context) {
        PrefManager.update(start = false, la = PrefManager.getLat, ln = PrefManager.getLng)
        PrefManager.pendingVoiceTarget = null
        NotificationsChannel().cancelLocationNotification(context)
        updateTileAndNotification(context)
    }

    private suspend fun stopAllSpoofing(context: Context) {
        val notifications = NotificationsChannel()
        val previous = activeLocationForTargets(allActiveTargets())
        PrefManager.update(start = false, la = PrefManager.getLat, ln = PrefManager.getLng)
        PrefManager.appLocationProfiles
            .filter { it.started }
            .forEach { notifications.cancelManagedLocationNotification(context, it.packageName) }
        PrefManager.stopAllAppLocations()
        PrefManager.pendingVoiceTarget = null
        notifications.cancelLocationNotification(context)
        updateTileAndNotification(context)
        mapResult = MapCommandResult.Stopped(previous)
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
                Intent(context, VoiceCommandActivity::class.java)
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
        internal suspend fun processResults(
            context: Context,
            recognizedPhrases: List<String>,
            startNextRecognition: suspend () -> Unit
        ): MapCommandResult? {
            return VoiceCommandResultReceiver().processRecognizedPhrases(
                context,
                recognizedPhrases,
                startNextRecognition
            )
        }

        private const val SECOND_STEP_DELAY_MILLIS = 2_500L
        private const val SYSTEM_TARGET = "@system"
        const val ACTION_PROCESS_RESULTS =
            "io.github.kovag1978_oss.gpssetter.action.PROCESS_VOICE_RESULTS"
    }
}
