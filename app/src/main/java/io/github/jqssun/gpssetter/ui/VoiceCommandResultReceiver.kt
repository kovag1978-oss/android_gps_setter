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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale

class VoiceCommandResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_RESULTS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val recognizedPhrases = intent
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    .orEmpty()
                val normalizedPhrases = recognizedPhrases
                    .map(::normalize)
                    .filter(String::isNotEmpty)
                if (normalizedPhrases.isEmpty()) {
                    showMessage(context, R.string.voice_no_matches)
                    return@launch
                }

                val favorites = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    VoiceCommandEntryPoint::class.java
                ).favoriteRepository().getAllFavoritesSnapshot()
                if (favorites.isEmpty()) {
                    showMessage(context, R.string.voice_no_favorites)
                    return@launch
                }

                val favoriteByPhrase = favorites.mapNotNull { favorite ->
                    favorite.address?.let(::normalize)?.takeIf(String::isNotEmpty)
                        ?.let { normalizedAddress -> normalizedAddress to favorite }
                }
                for (phrase in normalizedPhrases) {
                    if (phrase in STOP_COMMANDS) {
                        disableSpoofing(context)
                        showMessage(context, R.string.voice_location_stopped)
                        return@launch
                    }

                    val matches = favoriteByPhrase.filter { (address, _) ->
                        phrase.containsPhrase(address)
                    }
                    val favorite = matches.singleOrNull()?.second ?: continue
                    val latitude = favorite.lat
                    val longitude = favorite.lng
                    if (latitude == null || longitude == null) continue

                    PrefManager.update(start = true, la = latitude, ln = longitude)
                    showActiveLocationNotification(context, favorite.address.orEmpty())
                    VoiceCommandTileService.requestStateUpdate(context)
                    showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
                    return@launch
                }

                showMessage(context, R.string.voice_no_matches)
                withContext(Dispatchers.Main) {
                    context.startActivity(
                        VoiceCommandTileService.createRecognitionIntent(context)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.e(exception, "Failed to process voice location command")
                disableSpoofing(context)
                showMessage(context, R.string.voice_command_failed)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun disableSpoofing(context: Context) {
        PrefManager.update(start = false, la = PrefManager.getLat, ln = PrefManager.getLng)
        withContext(Dispatchers.Main) {
            NotificationsChannel().cancelLocationNotification(context)
            VoiceCommandTileService.requestStateUpdate(context)
        }
    }

    private suspend fun showActiveLocationNotification(context: Context, address: String) {
        withContext(Dispatchers.Main) {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationsChannel().showLocationNotification(context, address)
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

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
            .trim()

    private fun String.containsPhrase(phrase: String): Boolean =
        this == phrase || " $this ".contains(" $phrase ")

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VoiceCommandEntryPoint {
        fun favoriteRepository(): FavoriteRepository
    }

    companion object {
        private val STOP_COMMANDS = setOf(
            "стоп",
            "stop",
            "выключи геолокацию",
            "выключить геолокацию",
            "отключи геолокацию",
            "отключить геолокацию",
            "останови подмену",
            "остановить подмену",
            "turn off location",
            "disable location",
            "stop location"
        )

        const val ACTION_PROCESS_RESULTS =
            "io.github.kovag1978_oss.gpssetter.action.PROCESS_VOICE_RESULTS"
    }
}
