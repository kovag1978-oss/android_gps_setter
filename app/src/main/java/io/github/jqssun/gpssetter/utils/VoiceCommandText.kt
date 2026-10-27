package io.github.jqssun.gpssetter.utils

import android.content.Context
import io.github.jqssun.gpssetter.R
import java.text.Normalizer
import java.util.Locale

object VoiceCommandText {
    fun stopCommands(context: Context): Set<String> =
        setOf(normalize(context.getString(R.string.voice_stop_command)))

    fun isReservedCommand(value: String, context: Context): Boolean =
        normalize(value) in stopCommands(context)

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
            .trim()

    fun String.containsPhrase(phrase: String): Boolean =
        this == phrase || " $this ".contains(" $phrase ")
}
