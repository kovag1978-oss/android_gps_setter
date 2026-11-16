package io.github.jqssun.gpssetter.utils

import java.util.Locale

internal object VoiceRecognitionLocale {
    fun installedLanguage(preferredTag: String, installedTags: List<String>): String? {
        val preferred = Locale.forLanguageTag(preferredTag)
        val matching = installedTags.filter {
            val candidate = Locale.forLanguageTag(it)
            candidate.language.isNotEmpty() &&
                candidate.language.equals(preferred.language, ignoreCase = true)
        }
        return matching.firstOrNull { it.equals(preferredTag, ignoreCase = true) }
            ?: matching.firstOrNull {
                val candidate = Locale.forLanguageTag(it)
                preferred.country.isNotEmpty() &&
                    candidate.country.equals(preferred.country, ignoreCase = true)
            }
            ?: matching.sorted().firstOrNull()
    }
}
