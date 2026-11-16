package io.github.jqssun.gpssetter.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRecognitionLocaleTest {
    @Test
    fun prefersExactInstalledLocale() {
        assertEquals(
            "en-GB",
            VoiceRecognitionLocale.installedLanguage("en-GB", listOf("en-US", "en-GB"))
        )
    }

    @Test
    fun selectsInstalledVariantOfRequestedLanguage() {
        assertEquals(
            "ru-RU",
            VoiceRecognitionLocale.installedLanguage("ru", listOf("en-US", "ru-RU"))
        )
        assertEquals(
            "en-GB",
            VoiceRecognitionLocale.installedLanguage("en-US", listOf("ru-RU", "en-GB"))
        )
    }

    @Test
    fun neverSelectsDifferentLanguage() {
        assertNull(VoiceRecognitionLocale.installedLanguage("ru-RU", listOf("en-US")))
        assertNull(VoiceRecognitionLocale.installedLanguage("ru-RU", emptyList()))
    }

    @Test
    fun matchesLanguageCaseInsensitively() {
        assertEquals("RU-ru", VoiceRecognitionLocale.installedLanguage("ru-RU", listOf("RU-ru")))
    }
}
