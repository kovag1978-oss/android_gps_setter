package io.github.jqssun.gpssetter.utils

import java.text.Normalizer
import java.util.Locale

object VoiceCommandText {
    val stopCommands = setOf(
        "стоп",
        "stop",
        "выключи геолокацию",
        "выключить геолокацию",
        "отключи геолокацию",
        "отключить геолокацию",
        "останови подмену",
        "остановить подмену",
        "turn off location",
        "disable location",
        "stop location"
    )
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
