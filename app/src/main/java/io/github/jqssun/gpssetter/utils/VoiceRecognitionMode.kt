package io.github.jqssun.gpssetter.utils

internal enum class VoiceRecognitionMode(val value: String) {
    REGULAR("regular"),
    OFFLINE("offline"),
    AUTOMATIC("automatic");

    fun useOffline(hasValidatedInternet: Boolean): Boolean = when (this) {
        REGULAR -> false
        OFFLINE -> true
        AUTOMATIC -> !hasValidatedInternet
    }

    companion object {
        fun fromValue(value: String): VoiceRecognitionMode =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Invalid voice recognition mode: $value")

        fun fromLegacyOffline(enabled: Boolean): VoiceRecognitionMode =
            if (enabled) OFFLINE else REGULAR
    }
}
