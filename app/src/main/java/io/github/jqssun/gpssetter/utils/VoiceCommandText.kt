package io.github.jqssun.gpssetter.utils

import java.text.Normalizer
import java.util.Locale

object VoiceCommandText {
    val stopCommands = setOf("выключить")
    val reservedCommands = stopCommands

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
            .trim()

    fun String.containsPhrase(phrase: String): Boolean =
        this == phrase || " $this ".contains(" $phrase ")
}
