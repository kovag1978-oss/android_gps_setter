package io.github.jqssun.gpssetter.ui


import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivitySettingsBinding
import io.github.jqssun.gpssetter.gsApp
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.VoiceCommandOverlayService
import io.github.jqssun.gpssetter.update.UpdateChecker
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ActivitySettings : AppCompatActivity() {

    private val binding by lazy {
        ActivitySettingsBinding.inflate(layoutInflater)
    }

    class SettingPreferenceDataStore() : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "system_hooked" -> PrefManager.isSystemHooked
                "random_position" -> PrefManager.isRandomPosition
                "separate_control" -> PrefManager.isSeparateControlEnabled
                "voice_quick_tile" -> PrefManager.isVoiceTileEnabled
                "voice_floating_button" -> PrefManager.isVoiceFloatingButtonEnabled
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            return when (key) {
                "system_hooked" -> PrefManager.isSystemHooked = value
                "random_position" -> PrefManager.isRandomPosition = value
                "separate_control" -> PrefManager.isSeparateControlEnabled = value
                "voice_quick_tile" -> {
                    VoiceCommandTileService.setEnabled(gsApp, value)
                    PrefManager.isVoiceTileEnabled = value
                }
                "voice_floating_button" -> {
                    VoiceCommandOverlayService.sync(gsApp, value)
                    PrefManager.isVoiceFloatingButtonEnabled = value
                }
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "accuracy_level" -> PrefManager.accuracy
                "random_position_radius" -> PrefManager.randomPositionRadius
                "map_type" -> PrefManager.mapType.toString()
                "dark_theme" -> PrefManager.darkTheme.toString()
                "voice_recognition_mode" -> PrefManager.voiceRecognitionMode.value
                "app_language" -> AppCompatDelegate.getApplicationLocales().toLanguageTags()
                    .ifEmpty { "system" }
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            return when (key) {
                "accuracy_level" -> PrefManager.accuracy = value
                "random_position_radius" -> PrefManager.randomPositionRadius = value
                "map_type" -> PrefManager.mapType = value!!.toInt()
                "dark_theme" -> PrefManager.darkTheme = value!!.toInt()
                "voice_recognition_mode" -> PrefManager.voiceRecognitionMode =
                    io.github.jqssun.gpssetter.utils.VoiceRecognitionMode.fromValue(
                        requireNotNull(value)
                    )
                "app_language" -> {
                    val languageTags = when (value) {
                        "system" -> ""
                        "ru", "en" -> value
                        else -> throw IllegalArgumentException("Invalid app language $value")
                    }
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(languageTags)
                    )
                }
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        setContentView(binding.root)
        theme.applyStyle(com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight_NoActionBar, true)
        setSupportActionBar(binding.toolbar)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    @AndroidEntryPoint
    class SettingsPreferenceFragment : PreferenceFragmentCompat() {

        @Inject
        lateinit var updateChecker: UpdateChecker

        @Inject
        lateinit var downloadManager: DownloadManager

        private var pendingUpdate: UpdateChecker.Update? = null
        private var pendingInstallerUri: Uri? = null
        private var pendingDownloadId = INVALID_DOWNLOAD_ID

        private val installPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (!requireContext().packageManager.canRequestPackageInstalls()) {
                    pendingUpdate = null
                    pendingInstallerUri = null
                    requireContext().showToast(
                        getString(R.string.update_install_permission_required)
                    )
                    return@registerForActivityResult
                }

                val update = pendingUpdate
                val installerUri = pendingInstallerUri
                pendingUpdate = null
                pendingInstallerUri = null
                when {
                    update != null -> downloadUpdate(update)
                    installerUri != null -> openApkInstaller(installerUri)
                }
            }

        private val downloadCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val downloadId = intent.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    INVALID_DOWNLOAD_ID
                )
                if (downloadId != pendingDownloadId) return
                pendingDownloadId = INVALID_DOWNLOAD_ID
                handleDownloadComplete(downloadId)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            pendingDownloadId = savedInstanceState?.getLong(
                STATE_PENDING_DOWNLOAD_ID,
                INVALID_DOWNLOAD_ID
            ) ?: INVALID_DOWNLOAD_ID
            pendingUpdate = savedInstanceState?.getString(STATE_PENDING_UPDATE_URL)?.let { url ->
                UpdateChecker.Update(
                    name = savedInstanceState.getString(STATE_PENDING_UPDATE_NAME).orEmpty(),
                    changelog = savedInstanceState.getString(STATE_PENDING_UPDATE_CHANGELOG).orEmpty(),
                    assetUrl = url
                )
            }
            pendingInstallerUri =
                savedInstanceState?.getString(STATE_PENDING_INSTALLER_URI)?.let(Uri::parse)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            outState.putLong(STATE_PENDING_DOWNLOAD_ID, pendingDownloadId)
            pendingUpdate?.let { update ->
                outState.putString(STATE_PENDING_UPDATE_URL, update.assetUrl)
                outState.putString(STATE_PENDING_UPDATE_NAME, update.name)
                outState.putString(STATE_PENDING_UPDATE_CHANGELOG, update.changelog)
            }
            outState.putString(STATE_PENDING_INSTALLER_URI, pendingInstallerUri?.toString())
            super.onSaveInstanceState(outState)
        }

        override fun onStart() {
            super.onStart()
            ContextCompat.registerReceiver(
                requireContext(),
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            resumeCompletedDownload()
        }

        override fun onStop() {
            requireContext().unregisterReceiver(downloadCompleteReceiver)
            super.onStop()
        }

        private val overlayPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (Settings.canDrawOverlays(requireContext())) {
                    findPreference<SwitchPreferenceCompat>("voice_floating_button")
                        ?.isChecked = true
                } else {
                    requireContext().showToast(
                        getString(R.string.voice_overlay_permission_required)
                    )
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager?.preferenceDataStore = SettingPreferenceDataStore()
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<SwitchPreferenceCompat>("voice_floating_button")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true && !Settings.canDrawOverlays(requireContext())) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                        try {
                            overlayPermissionLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            requireContext().showToast(
                                getString(R.string.voice_overlay_permission_required)
                            )
                        }
                        false
                    } else {
                        true
                    }
                }

            findPreference<Preference>("check_update")?.setOnPreferenceClickListener { preference ->
                preference.isEnabled = false
                preference.setSummary(R.string.checking_for_updates)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = updateChecker.checkForUpdate()

                    preference.isEnabled = true
                    preference.setSummary(R.string.check_update_summary)
                    when (result) {
                        UpdateChecker.CheckResult.Current -> showUpdateCheckMessage(
                            R.string.latest_version_installed,
                            R.string.latest_version_installed_message
                        )
                        is UpdateChecker.CheckResult.Failed -> showUpdateCheckMessage(
                            R.string.update_check_failed,
                            R.string.update_check_failed_message,
                            result.reason
                        )
                        is UpdateChecker.CheckResult.Available -> showUpdateAvailable(result.update)
                    }
                }
                true
            }

            findPreference<Preference>("app_profiles")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AppProfilesActivity::class.java))
                true
            }

            findPreference<EditTextPreference>("accuracy_level")?.let {
                it.summary = "${PrefManager.accuracy} m"
                it.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER;
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,");
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText));
                }

                it.setOnPreferenceChangeListener { preference, newValue ->
                    try {
                        newValue as String?
                        preference.summary = "$newValue m"
                    } catch (n: NumberFormatException) {
                        n.printStackTrace()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.enter_valid_input),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            findPreference<EditTextPreference>("random_position_radius")?.let {
                it.summary = getString(
                    R.string.random_position_radius_summary,
                    PrefManager.randomPositionRadius.orEmpty()
                )
                it.setOnBindEditTextListener { editText ->
                    editText.inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,")
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText))
                }
                it.setOnPreferenceChangeListener { preference, newValue ->
                    val radius = (newValue as? String)
                        ?.replace(',', '.')
                        ?.toDoubleOrNull()
                    if (radius == null ||
                        radius !in MIN_RANDOM_POSITION_RADIUS..MAX_RANDOM_POSITION_RADIUS
                    ) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.random_position_radius_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    } else {
                        preference.summary = getString(
                            R.string.random_position_radius_summary,
                            radius.toString()
                        )
                        true
                    }
                }
            }

            findPreference<DropDownPreference>("dark_theme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    activity?.recreate()
                }
                true
            }
        }

        override fun onResume() {
            super.onResume()
            findPreference<SwitchPreferenceCompat>("voice_floating_button")
                ?.isChecked = PrefManager.isVoiceFloatingButtonEnabled &&
                Settings.canDrawOverlays(requireContext())
        }

        private fun showUpdateCheckMessage(title: Int, message: Int, reason: String? = null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(if (reason == null) getString(message) else getString(message, reason))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun showUpdateAvailable(update: UpdateChecker.Update) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.update_available)
                .setMessage(
                    getString(
                        R.string.update_available_message,
                        update.name,
                        update.changelog
                    )
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_button) { _, _ ->
                    prepareUpdateDownload(update)
                }
                .show()
        }

        private fun prepareUpdateDownload(update: UpdateChecker.Update) {
            if (requireContext().packageManager.canRequestPackageInstalls()) {
                downloadUpdate(update)
                return
            }

            pendingUpdate = update
            requireContext().showToast(
                getString(R.string.update_install_permission_required)
            )
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${requireContext().packageName}")
            )
            try {
                installPermissionLauncher.launch(intent)
            } catch (exception: ActivityNotFoundException) {
                pendingUpdate = null
                Timber.e(exception, "Could not open install-source settings")
                requireContext().showToast(getString(R.string.update_download_failed))
            }
        }

        private fun downloadUpdate(update: UpdateChecker.Update) {
            val downloadUri = Uri.parse(update.assetUrl)
            if (downloadUri.scheme != "https" || downloadUri.host != "github.com") {
                Timber.w("Rejected unexpected update URL: %s", update.assetUrl)
                requireContext().showToast(getString(R.string.update_download_failed))
                return
            }

            val request = DownloadManager.Request(downloadUri)
                .setTitle(getString(R.string.update_download_title))
                .setDescription(update.name)
                .setMimeType(APK_MIME_TYPE)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    requireContext(),
                    Environment.DIRECTORY_DOWNLOADS,
                    "gps-setter-update-${System.currentTimeMillis()}.apk"
                )

            try {
                pendingDownloadId = downloadManager.enqueue(request)
                requireContext().showToast(getString(R.string.update_download_started))
            } catch (exception: IllegalArgumentException) {
                Timber.e(exception, "Invalid APK update download request")
                requireContext().showToast(getString(R.string.update_download_failed))
            } catch (exception: SecurityException) {
                Timber.e(exception, "APK update download was denied")
                requireContext().showToast(getString(R.string.update_download_failed))
            }
        }

        private fun handleDownloadComplete(downloadId: Long) {
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            val successful = cursor.use {
                if (!it.moveToFirst()) {
                    Timber.w("APK update download %d disappeared from DownloadManager", downloadId)
                    false
                } else {
                    val status = it.getInt(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        val reason = it.getInt(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                        Timber.w("APK update download %d failed with reason %d", downloadId, reason)
                        false
                    } else {
                        true
                    }
                }
            }
            if (!successful) {
                requireContext().showToast(getString(R.string.update_download_failed))
                return
            }

            val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
            if (apkUri == null) {
                Timber.w("APK update download %d has no content URI", downloadId)
                requireContext().showToast(getString(R.string.update_download_failed))
                return
            }
            openApkInstaller(apkUri)
        }

        private fun resumeCompletedDownload() {
            val downloadId = pendingDownloadId
            if (downloadId == INVALID_DOWNLOAD_ID) return

            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            val completed = cursor.use {
                if (!it.moveToFirst()) {
                    false
                } else {
                    val status = it.getInt(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    status != DownloadManager.STATUS_PENDING &&
                        status != DownloadManager.STATUS_RUNNING &&
                        status != DownloadManager.STATUS_PAUSED
                }
            }
            if (completed) {
                pendingDownloadId = INVALID_DOWNLOAD_ID
                handleDownloadComplete(downloadId)
            }
        }

        private fun openApkInstaller(apkUri: Uri) {
            if (!requireContext().packageManager.canRequestPackageInstalls()) {
                pendingInstallerUri = apkUri
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${requireContext().packageName}")
                )
                try {
                    installPermissionLauncher.launch(intent)
                } catch (exception: ActivityNotFoundException) {
                    pendingInstallerUri = null
                    Timber.e(exception, "Could not open install-source settings")
                    requireContext().showToast(getString(R.string.update_download_failed))
                }
                return
            }

            val installIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(installIntent)
            } catch (exception: ActivityNotFoundException) {
                Timber.e(exception, "No APK installer is available")
                requireContext().showToast(getString(R.string.update_download_failed))
            } catch (exception: SecurityException) {
                Timber.e(exception, "APK installer could not read downloaded APK")
                requireContext().showToast(getString(R.string.update_download_failed))
            }
        }

        private companion object {
            const val APK_MIME_TYPE = "application/vnd.android.package-archive"
            const val INVALID_DOWNLOAD_ID = -1L
            const val STATE_PENDING_DOWNLOAD_ID = "pending_update_download_id"
            const val STATE_PENDING_INSTALLER_URI = "pending_update_installer_uri"
            const val STATE_PENDING_UPDATE_URL = "pending_update_url"
            const val STATE_PENDING_UPDATE_NAME = "pending_update_name"
            const val STATE_PENDING_UPDATE_CHANGELOG = "pending_update_changelog"
            const val MIN_RANDOM_POSITION_RADIUS = 0.5
            const val MAX_RANDOM_POSITION_RADIUS = 50.0
        }

        private fun getCommaReplacerTextWatcher(editText: EditText): TextWatcher {
            return object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun onTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun afterTextChanged(editable: Editable) {
                    val text = editable.toString()
                    if (text.contains(",")) {
                        editText.setText(text.replace(",", "."))
                        editText.setSelection(editText.text.length)
                    }
                }
            }
        }
    }
}