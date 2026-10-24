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
        val resultIntent = Intent(this, VoiceCommandResultReceiver::class.java).apply {
            action = VoiceCommandResultReceiver.ACTION_PROCESS_RESULTS
            data = Uri.parse("gpssetter://voice-command/${System.currentTimeMillis()}")
        }
        val resultPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, resultPendingIntent)
            putExtra(
                RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE,
                Bundle()
            )
        }
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

    private fun voiceLanguageTag(): String =
        androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            .ifEmpty { java.util.Locale.getDefault().toLanguageTag() }

    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.state = if (PrefManager.isStarted) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.voice_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.voice_tile_subtitle)
            }
            tile.updateTile()
        }
    }

    companion object {
        fun requestStateUpdate(context: android.content.Context) {
            requestListeningState(
                context,
                ComponentName(context, VoiceCommandTileService::class.java)
            )
        }
    }
}
