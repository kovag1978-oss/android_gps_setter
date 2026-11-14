package io.github.jqssun.gpssetter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.VoiceCommandOverlayService

class VoiceTilePreferencesActivity : AppCompatActivity() {

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                enableFloatingButton()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != ACTION_QS_TILE_PREFERENCES) {
            finish()
            return
        }

        if (Settings.canDrawOverlays(this)) {
            enableFloatingButton()
            finish()
        } else {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun enableFloatingButton() {
        PrefManager.isVoiceFloatingButtonEnabled = true
        VoiceCommandOverlayService.sync(this, enabled = true)
    }

    companion object {
        private const val ACTION_QS_TILE_PREFERENCES =
            "android.service.quicksettings.action.QS_TILE_PREFERENCES"
    }
}
