package io.github.jqssun.gpssetter.ui

import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceCommandActivity : AppCompatActivity() {

    private val recognitionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                PrefManager.pendingVoiceTarget = null
                finish()
                return@registerForActivityResult
            }

            val phrases = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            lifecycleScope.launch {
                var continuedRecognition = false
                withContext(Dispatchers.IO) {
                    VoiceCommandResultReceiver.processResults(
                        this@VoiceCommandActivity,
                        phrases
                    ) {
                        withContext(Dispatchers.Main) {
                            continuedRecognition = true
                            launchRecognition()
                        }
                    }
                }
                if (!continuedRecognition) finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            launchRecognition()
        }
    }

    private fun launchRecognition() {
        recognitionLauncher.launch(VoiceCommandTileService.createRecognitionIntent(this))
    }
}
