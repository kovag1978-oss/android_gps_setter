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
                if (recognizedPhrases.isEmpty()) {
                    disableSpoofing(context)
                    showMessage(context, R.string.voice_command_failed)
                    return@launch
                }

                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    VoiceCommandEntryPoint::class.java
                )
                val favorites = entryPoint.favoriteRepository().getAllFavoritesSnapshot()
                val normalizedPhrases = recognizedPhrases
                    .map(::normalize)
                    .filter(String::isNotEmpty)
                    .toSet()
                val matches = favorites.filter { favorite ->
                    favorite.address?.let(::normalize)?.let(normalizedPhrases::contains) == true
                }
                val favorite = matches.singleOrNull()
                val latitude = favorite?.lat
                val longitude = favorite?.lng
                if (favorite == null || latitude == null || longitude == null) {
                    disableSpoofing(context)
                    showMessage(context, R.string.voice_command_failed)
                    return@launch
                }

                PrefManager.update(start = true, la = latitude, ln = longitude)
                showActiveLocationNotification(context, favorite.address.orEmpty())
                VoiceCommandTileService.requestStateUpdate(context)
                showMessage(context, R.string.voice_location_set, favorite.address.orEmpty())
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VoiceCommandEntryPoint {
        fun favoriteRepository(): FavoriteRepository
    }

    companion object {
        const val ACTION_PROCESS_RESULTS =
            "io.github.kovag1978oss.gpssetter.action.PROCESS_VOICE_RESULTS"
    }
}
