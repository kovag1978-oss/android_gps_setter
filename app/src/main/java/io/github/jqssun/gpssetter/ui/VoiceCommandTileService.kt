package io.github.jqssun.gpssetter.ui

import android.content.ComponentName
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.PrefManager

class VoiceCommandTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val recognitionIntent = createRecognitionIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val recognitionPendingIntent = PendingIntent.getActivity(
                this,
                0,
                recognitionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(recognitionPendingIntent)
        } else {
            startActivityAndCollapse(recognitionIntent)
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.state = if (PrefManager.isStarted || PrefManager.hasActiveAppProfile) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            tile.label = getString(R.string.voice_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(
                    if (PrefManager.isSeparateControlEnabled) {
                        R.string.voice_tile_subtitle_separate
                    } else {
                        R.string.voice_tile_subtitle
                    }
                )
            }
            tile.updateTile()
        }
    }

    companion object {
        fun createRecognitionIntent(context: android.content.Context): Intent {
            val resultIntent = Intent(context, VoiceCommandResultReceiver::class.java).apply {
                action = VoiceCommandResultReceiver.ACTION_PROCESS_RESULTS
                data = Uri.parse("gpssetter://voice-command/${System.currentTimeMillis()}")
            }
            val resultPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                val prompt = when {
                    !PrefManager.isSeparateControlEnabled -> context.getString(R.string.voice_prompt)
                    PrefManager.pendingVoiceTarget == null -> targetRecognitionPrompt(context)
                    else -> favoriteRecognitionPrompt(context)
                }
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, resultPendingIntent)
                putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, Bundle())
            }
        }

        private fun targetRecognitionPrompt(context: android.content.Context): String {
            val appCommands = PrefManager.appLocationProfiles
                .filter { it.enabled }
                .map { it.voiceCommand.trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString()
                .ifEmpty { context.getString(R.string.voice_no_app_commands) }
            val favorites = favoriteNames(context)
                .joinToString()
                .ifEmpty { context.getString(R.string.voice_no_saved_favorites) }
            return context.getString(R.string.voice_target_prompt, appCommands, favorites)
        }

        private fun favoriteRecognitionPrompt(context: android.content.Context): String {
            val favorites = favoriteNames(context)
                .joinToString()
                .ifEmpty { context.getString(R.string.voice_no_saved_favorites) }
            val target = PrefManager.pendingVoiceTarget
            val targetName = if (target == SYSTEM_TARGET) {
                context.getString(R.string.voice_system_target)
            } else {
                PrefManager.appLocationProfiles
                    .firstOrNull { it.packageName == target }
                    ?.appName
                    ?: context.getString(R.string.voice_system_target)
            }
            return context.getString(R.string.voice_location_prompt, targetName, favorites)
        }

        private fun favoriteNames(context: android.content.Context): List<String> =
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                VoiceCommandResultReceiver.VoiceCommandEntryPoint::class.java
            ).favoriteRepository()
                .getFavoritesForVoicePrompt()
                .mapNotNull { it.address?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()

        private fun voiceLanguageTag(): String =
            androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
                .ifEmpty { java.util.Locale.getDefault().toLanguageTag() }

        fun requestStateUpdate(context: android.content.Context) {
            requestListeningState(
                context,
                ComponentName(context, VoiceCommandTileService::class.java)
            )
        }

        private const val SYSTEM_TARGET = "@system"
    }
}
