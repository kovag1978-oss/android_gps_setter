package io.github.jqssun.gpssetter.utils

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPreferencesCommitTest {
    private class FakePreferences(vararg results: Boolean) {
        val values = mutableMapOf<String, Any>()
        private val outcomes = results.toMutableList()
        var commits = 0
        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args[0])
                "getString", "getBoolean", "getFloat" -> values[args[0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putFloat" -> {
                        changes[args[0] as String] = args[1]
                        proxy
                    }
                    "remove" -> {
                        changes[args[0] as String] = null
                        proxy
                    }
                    "commit" -> {
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        commits++
                        outcomes.removeAt(0)
                    }
                    else -> error("Unexpected editor method ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    private fun commit(fake: FakePreferences) = commitLocationPreferences(
        fake.prefs, "profiles", "new", "start", "lat", "lon", true, 10f, 20f
    )

    @Test
    fun writesSystemAndProfilesInOneCommit() {
        val fake = FakePreferences(true)
        assertTrue(commit(fake))
        assertEquals(1, fake.commits)
        assertEquals(mapOf("profiles" to "new", "start" to true, "lat" to 10f, "lon" to 20f), fake.values)
    }

    @Test
    fun systemOnlyPreservesProfiles() {
        val fake = FakePreferences(true)
        fake.values["profiles"] = "old"
        assertTrue(commitLocationPreferences(fake.prefs, "profiles", null, "start", "lat", "lon", true, 10f, 20f))
        assertEquals("old", fake.values["profiles"])
    }

    @Test
    fun restoresPreviouslyPresentAndAbsentValuesAfterFailure() {
        val fake = FakePreferences(false, true)
        fake.values["profiles"] = "old"
        fake.values["lat"] = 1f
        assertFalse(commit(fake))
        assertEquals(mapOf("profiles" to "old", "lat" to 1f), fake.values)
        assertEquals(2, fake.commits)
    }

    @Test(expected = IllegalStateException::class)
    fun surfacesFailureToPersistRollback() {
        commit(FakePreferences(false, false))
    }
}
