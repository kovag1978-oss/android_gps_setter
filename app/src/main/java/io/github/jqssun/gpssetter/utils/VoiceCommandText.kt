package io.github.jqssun.gpssetter.utils

import android.content.Context
import io.github.jqssun.gpssetter.R
import java.text.Normalizer
import java.util.Locale

object VoiceCommandText {
    fun stopCommands(context: Context): Set<String> =
        setOf(normalize(context.getString(R.string.voice_stop_command)))

    fun systemCommand(context: Context): String =
        normalize(context.getString(R.string.voice_system_target))

    fun isReservedCommand(value: String, context: Context): Boolean =
        normalize(value) in stopCommands(context) || normalize(value) == systemCommand(context)

    fun containsSystemCommand(phrases: List<String>, systemCommand: String): Boolean =
        phrases.any { it.containsPhrase(systemCommand) }

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
            .trim()

    fun String.containsPhrase(phrase: String): Boolean =
        this == phrase || " $this ".contains(" $phrase ")
}
