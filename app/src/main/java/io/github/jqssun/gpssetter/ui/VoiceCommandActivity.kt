package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.DialogVoiceOfflineBinding
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.VoiceRecognitionLocale
import io.github.jqssun.gpssetter.utils.VoiceRecognitionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import timber.log.Timber

class VoiceCommandActivity : AppCompatActivity() {

    private var offlineMode = false
    private var permissionPending = false
    private var recognizer: SpeechRecognizer? = null
    private var offlineDialog: AlertDialog? = null
    private var offlineDialogBinding: DialogVoiceOfflineBinding? = null
    private var listening = false
    private var commandCompleted = false
    private val realLocationTracker by lazy { RealLocationTracker(this) }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionPending = false
            if (granted) {
                startOfflineRecognition()
            } else {
                showOfflineError(getString(R.string.voice_offline_microphone_required))
            }
        }

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
            processResults(phrases)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        realLocationTracker.start()
        offlineMode = savedInstanceState?.getBoolean(STATE_OFFLINE_MODE) ?: run {
            val mode = PrefManager.voiceRecognitionMode
            mode.useOffline(mode == VoiceRecognitionMode.AUTOMATIC && hasValidatedInternet())
        }
        permissionPending = savedInstanceState?.getBoolean(STATE_PERMISSION_PENDING) ?: false
        if (offlineMode) {
            showOfflineDialog()
            if (!permissionPending) launchRecognition()
        } else if (savedInstanceState == null) {
            launchRecognition()
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_OFFLINE_MODE, offlineMode)
        outState.putBoolean(STATE_PERMISSION_PENDING, permissionPending)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (offlineMode && recognizer != null && !isChangingConfigurations) {
            cancelOfflineRecognition()
        }
        super.onStop()
    }

    override fun onDestroy() {
        realLocationTracker.stop()
        releaseRecognizer()
        offlineDialog?.dismiss()
        offlineDialog = null
        offlineDialogBinding = null
        if (offlineMode && isFinishing && !commandCompleted) {
            PrefManager.pendingVoiceTarget = null
        }
        super.onDestroy()
    }

    private fun launchRecognition() {
        if (!offlineMode) {
            recognitionLauncher.launch(VoiceCommandTileService.createRecognitionIntent(this))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            showOfflineError(getString(R.string.voice_offline_unavailable))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionPending = true
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startOfflineRecognition()
    }

    private fun showOfflineDialog() {
        val binding = DialogVoiceOfflineBinding.inflate(layoutInflater)
        offlineDialogBinding = binding
        binding.voicePrompt.text = VoiceCommandTileService.createRecognitionIntent(this)
            .getStringExtra(RecognizerIntent.EXTRA_PROMPT)
        offlineDialog = AlertDialog.Builder(this)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelOfflineRecognition() }
            .setOnCancelListener { cancelOfflineRecognition() }
            .show()
    }

    private fun startOfflineRecognition() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            showOfflineError(getString(R.string.voice_offline_unavailable))
            return
        }
        releaseRecognizer()
        val intent = VoiceCommandTileService.createRecognitionIntent(this, offline = true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        setOfflineStatus(R.string.voice_offline_preparing)
        offlineDialogBinding?.voicePrompt?.text = intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)
        val speechRecognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } catch (exception: UnsupportedOperationException) {
            Timber.w(exception, "On-device speech recognition is unavailable")
            showOfflineError(getString(R.string.voice_offline_unavailable))
            return
        }
        recognizer = speechRecognizer
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer === speechRecognizer) {
                    setOfflineStatus(R.string.voice_offline_listening)
                    listening = true
                }
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                if (recognizer !== speechRecognizer || !listening || !rmsdB.isFinite()) return
                val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.8f
                offlineDialogBinding?.voiceLevel?.animate()
                    ?.scaleX(scale)?.scaleY(scale)?.setDuration(120)?.start()
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (recognizer === speechRecognizer) {
                    setOfflineStatus(R.string.voice_offline_processing)
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== speechRecognizer) return
                Timber.w("On-device speech recognition failed: %s", error)
                val message = when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        getString(R.string.voice_offline_language_unavailable)
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        getString(R.string.voice_offline_language_unsupported)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        getString(R.string.voice_offline_microphone_required)
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        getString(R.string.voice_offline_timeout)
                    SpeechRecognizer.ERROR_NO_MATCH -> getString(R.string.voice_no_matches)
                    else -> getString(R.string.voice_offline_error, error)
                }
                showOfflineError(message)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== speechRecognizer) return
                releaseRecognizer()
                setOfflineStatus(R.string.voice_offline_processing)
                processResults(
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                )
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !AppCompatDelegate.getApplicationLocales().isEmpty
            ) {
                val installed = withTimeoutOrNull(3_000) {
                    suspendCancellableCoroutine { continuation ->
                        val query = android.content.Intent(intent).apply {
                            removeExtra(RecognizerIntent.EXTRA_LANGUAGE)
                        }
                        speechRecognizer.checkRecognitionSupport(
                            query,
                            ContextCompat.getMainExecutor(this@VoiceCommandActivity),
                            object : RecognitionSupportCallback {
                                override fun onSupportResult(support: RecognitionSupport) {
                                    if (continuation.isActive) {
                                        continuation.resume(support.installedOnDeviceLanguages)
                                    }
                                }

                                override fun onError(error: Int) {
                                    Timber.w("Cannot check offline language support: %s", error)
                                    if (continuation.isActive) continuation.resume(emptyList())
                                }
                            }
                        )
                    }
                }
                if (installed == null) {
                    Timber.w("Timed out checking offline language support")
                } else {
                    val preferred = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
                    val selected = preferred?.let {
                        VoiceRecognitionLocale.installedLanguage(it, installed)
                    }
                    if (selected != null) {
                        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, selected)
                    }
                }
            }
            if (recognizer === speechRecognizer && !isFinishing && !isDestroyed) {
                speechRecognizer.startListening(intent)
            }
        }
    }

    private fun showOfflineError(message: String) {
        releaseRecognizer()
        PrefManager.pendingVoiceTarget = null
        setOfflineStatus(R.string.voice_offline_recognition)
        offlineDialogBinding?.voicePrompt?.text = message
    }

    private fun setOfflineStatus(status: Int) {
        listening = false
        offlineDialogBinding?.let { binding ->
            binding.voiceLevel.animate().cancel()
            binding.voiceLevel.scaleX = 1f
            binding.voiceLevel.scaleY = 1f
            binding.voiceStatus.setText(status)
        }
    }

    private fun cancelOfflineRecognition() {
        PrefManager.pendingVoiceTarget = null
        releaseRecognizer()
        finish()
    }

    private fun releaseRecognizer() {
        listening = false
        offlineDialogBinding?.voiceLevel?.animate()?.cancel()
        val currentRecognizer = recognizer
        recognizer = null
        currentRecognizer?.destroy()
    }

    private fun processResults(phrases: List<String>) {
        lifecycleScope.launch {
            var continuedRecognition = false
            val realLocation = realLocationTracker.current()
            val mapResult = withContext(Dispatchers.IO) {
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
            val result = if (mapResult is MapCommandResult.Applied && mapResult.previous == null) {
                mapResult.copy(previous = realLocation)
            } else mapResult
            if (!continuedRecognition) {
                commandCompleted = true
                if (result != null) {
                    val mapWasVisible = intent.getBooleanExtra(EXTRA_MAP_WAS_VISIBLE, false)
                    val mapIntent = Intent(this@VoiceCommandActivity, MapActivity::class.java)
                        .putExtra(BaseMapActivity.EXTRA_PREVIEW, !mapWasVisible)
                    result.previous?.let {
                        mapIntent.putExtra(BaseMapActivity.EXTRA_PREVIOUS_LATITUDE, it.latitude)
                        mapIntent.putExtra(BaseMapActivity.EXTRA_PREVIOUS_LONGITUDE, it.longitude)
                    }
                    when (result) {
                        is MapCommandResult.Applied -> mapIntent
                            .putExtra(BaseMapActivity.EXTRA_LATITUDE, result.latitude)
                            .putExtra(BaseMapActivity.EXTRA_LONGITUDE, result.longitude)
                        is MapCommandResult.Stopped -> mapIntent
                            .putExtra(BaseMapActivity.EXTRA_STOPPED, true)
                    }
                    if (mapWasVisible) {
                        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    } else {
                        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    try {
                        startActivity(mapIntent)
                    } catch (exception: ActivityNotFoundException) {
                        Timber.e(exception, "Cannot open the map result")
                        Toast.makeText(this@VoiceCommandActivity, R.string.map_preview_unavailable, Toast.LENGTH_LONG).show()
                    } catch (exception: SecurityException) {
                        Timber.e(exception, "Cannot open the map result")
                        Toast.makeText(this@VoiceCommandActivity, R.string.map_preview_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
        }
    }

    companion object {
        internal const val EXTRA_MAP_WAS_VISIBLE = "map_was_visible"
        private const val STATE_OFFLINE_MODE = "offline_mode"
        private const val STATE_PERMISSION_PENDING = "permission_pending"
    }
}
