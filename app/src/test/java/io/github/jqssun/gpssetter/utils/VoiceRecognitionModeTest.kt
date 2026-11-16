package io.github.jqssun.gpssetter.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionModeTest {
    @Test
    fun automaticUsesOfflineWithoutValidatedInternet() {
        assertTrue(VoiceRecognitionMode.AUTOMATIC.useOffline(false))
        assertFalse(VoiceRecognitionMode.AUTOMATIC.useOffline(true))
    }

    @Test
    fun explicitModesIgnoreConnectivity() {
        assertTrue(VoiceRecognitionMode.OFFLINE.useOffline(true))
        assertTrue(VoiceRecognitionMode.OFFLINE.useOffline(false))
        assertFalse(VoiceRecognitionMode.REGULAR.useOffline(true))
        assertFalse(VoiceRecognitionMode.REGULAR.useOffline(false))
    }

    @Test
    fun preservesPreviousSetting() {
        assertEquals(VoiceRecognitionMode.OFFLINE, VoiceRecognitionMode.fromLegacyOffline(true))
        assertEquals(VoiceRecognitionMode.REGULAR, VoiceRecognitionMode.fromLegacyOffline(false))
    }

    @Test
    fun modeValuesRoundTrip() {
        VoiceRecognitionMode.entries.forEach {
            assertEquals(it, VoiceRecognitionMode.fromValue(it.value))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidModeIsRejected() {
        VoiceRecognitionMode.fromValue("invalid")
    }
}
