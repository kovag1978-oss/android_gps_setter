package io.github.jqssun.gpssetter.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTargetsTest {
    private val first = AppLocationProfile("a", "A", enabled = true, started = true, latitude = 1.0, longitude = 2.0)
    private val second = AppLocationProfile("b", "B", enabled = true)
    private val disabled = AppLocationProfile("c", "C", enabled = false)

    @Test
    fun systemOnlyLeavesProfilesUnchanged() {
        assertEquals(listOf(first, second), updatedLocationProfiles(listOf(first, second), emptySet(), true, 5.0, 6.0))
        assertTrue(updatedLocationProfiles(emptyList(), emptySet(), true, 5.0, 6.0).isEmpty())
    }

    @Test
    fun selectedProfilesChangeWithoutTouchingOthers() {
        val result = updatedLocationProfiles(listOf(first, second, disabled), setOf("b"), false, 5.0, 6.0)
        assertEquals(first, result[0])
        assertEquals(second.copy(started = true, latitude = 5.0, longitude = 6.0), result[1])
        assertEquals(disabled, result[2])
    }

    @Test
    fun allUpdatesEveryEnabledTargetAndPreservesDisabledProfiles() {
        val result = updatedLocationProfiles(listOf(first, second, disabled), setOf("a", "b"), true, 5.0, 6.0)
        assertEquals(first.copy(latitude = 5.0, longitude = 6.0), result[0])
        assertEquals(second.copy(started = true, latitude = 5.0, longitude = 6.0), result[1])
        assertEquals(disabled, result[2])
    }

    @Test(expected = IllegalStateException::class)
    fun disabledTargetIsRejected() {
        updatedLocationProfiles(listOf(disabled), setOf("c"), true, 5.0, 6.0)
    }

    @Test
    fun systemPhraseMatchesWholeWordsAndWinsWithinLongerPhrase() {
        val command = VoiceCommandText.normalize("Система")
        assertTrue(VoiceCommandText.containsSystemCommand(listOf(VoiceCommandText.normalize("Система Москва")), command))
        assertFalse(VoiceCommandText.containsSystemCommand(listOf(VoiceCommandText.normalize("экосистема")), command))
    }
}
