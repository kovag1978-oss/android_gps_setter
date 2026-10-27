package io.github.jqssun.gpssetter.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivitySettingsBinding
import io.github.jqssun.gpssetter.utils.AppLocationProfile
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.VoiceCommandText
import java.util.Locale

class AppProfilesActivity : AppCompatActivity() {
    private val binding by lazy { ActivitySettingsBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContentView(binding.root)
        theme.applyStyle(
            com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight_NoActionBar,
            true
        )
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.app_profiles)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, ProfilesPreferenceFragment())
                .commit()
        }
    }

    class ProfilesPreferenceFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            val storedProfiles = PrefManager.appLocationProfiles.associateBy { it.packageName }
            val installedApps = launchableApps().sortedWith(
                compareByDescending<Pair<String, String>> {
                    storedProfiles[it.first]?.enabled == true
                }.thenBy { it.second.lowercase(Locale.getDefault()) }
            )

            installedApps.forEach { (packageName, appName) ->
                val oldProfile = storedProfiles[packageName]
                val profile = oldProfile ?: AppLocationProfile(packageName, appName)
                val category = PreferenceCategory(requireContext()).apply {
                    title = appName
                }
                screen.addPreference(category)

                val commandPreference = EditTextPreference(requireContext()).apply {
                    key = "app_voice_command_$packageName"
                    isPersistent = false
                    title = getString(R.string.app_voice_command)
                    dialogTitle = getString(R.string.app_voice_command)
                    dialogMessage = getString(R.string.app_voice_command_summary)
                    setText(profile.voiceCommand)
                    summary = profile.voiceCommand.ifBlank {
                        getString(R.string.app_voice_command_summary)
                    }
                    setOnPreferenceChangeListener { preference, newValue ->
                        val command = newValue.toString().trim()
                        if (command.isEmpty()) {
                            showInvalidCommand()
                            return@setOnPreferenceChangeListener false
                        }
                        val normalized = VoiceCommandText.normalize(command)
                        if (normalized.isEmpty()) {
                            showInvalidCommand()
                            return@setOnPreferenceChangeListener false
                        }
                        val duplicate = PrefManager.appLocationProfiles.any {
                            it.packageName != packageName &&
                                it.enabled &&
                                VoiceCommandText.normalize(it.voiceCommand) == normalized
                        }
                        if (normalized in VoiceCommandText.reservedCommands || duplicate) {
                            Toast.makeText(
                                requireContext(),
                                R.string.app_voice_command_duplicate,
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnPreferenceChangeListener false
                        }
                        PrefManager.saveAppLocationProfile(
                            profileFor(packageName, appName).copy(voiceCommand = command)
                        )
                        preference.summary = command
                        true
                    }
                }
                category.addPreference(commandPreference)

                val enabledPreference = SwitchPreferenceCompat(requireContext()).apply {
                    key = "app_profile_enabled_$packageName"
                    isPersistent = false
                    title = getString(R.string.app_profile_enabled)
                    isChecked = profile.enabled
                    setOnPreferenceChangeListener { _, newValue ->
                        val enabled = newValue as Boolean
                        val latest = profileFor(packageName, appName)
                        if (enabled && latest.voiceCommand.isBlank()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.app_voice_command_required,
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnPreferenceChangeListener false
                        }
                        if (enabled) {
                            val normalized = VoiceCommandText.normalize(latest.voiceCommand)
                            val duplicate = normalized in VoiceCommandText.reservedCommands ||
                                PrefManager.appLocationProfiles.any {
                                    it.packageName != packageName &&
                                        it.enabled &&
                                        VoiceCommandText.normalize(it.voiceCommand) == normalized
                                }
                            if (duplicate) {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.app_voice_command_duplicate,
                                    Toast.LENGTH_LONG
                                ).show()
                                return@setOnPreferenceChangeListener false
                            }
                        }
                        PrefManager.saveAppLocationProfile(
                            latest.copy(enabled = enabled, started = latest.started && enabled)
                        )
                        true
                    }
                }
                category.addPreference(enabledPreference)
            }

            if (installedApps.isEmpty()) {
                screen.addPreference(Preference(requireContext()).apply {
                    title = getString(R.string.no_launchable_apps)
                    isSelectable = false
                })
            }
            preferenceScreen = screen
        }

        private fun profileFor(packageName: String, appName: String): AppLocationProfile =
            PrefManager.appLocationProfiles.firstOrNull { it.packageName == packageName }
                ?: AppLocationProfile(packageName, appName)

        private fun launchableApps(): List<Pair<String, String>> {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val packageManager = requireContext().packageManager
            val launchableApps = packageManager
                .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .asSequence()
                .map { resolveInfo ->
                    resolveInfo.activityInfo.packageName to resolveInfo.loadLabel(packageManager).toString()
                }
                .filter { it.first != BuildConfig.APPLICATION_ID }
                .distinctBy { it.first }
                .toMutableList()
            GOOGLE_LOCATION_PACKAGES.forEach { packageName ->
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    launchableApps += packageName to packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    // This Google service is not installed on this device.
                }
            }
            return launchableApps
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase(Locale.getDefault()) }
        }

        private fun showInvalidCommand() {
            Toast.makeText(
                requireContext(),
                R.string.app_voice_command_required,
                Toast.LENGTH_LONG
            ).show()
        }

        companion object {
            private val GOOGLE_LOCATION_PACKAGES = listOf(
                "com.google.android.gms",
                "com.google.android.gsf"
            )
        }
    }
}
