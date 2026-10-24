package io.github.jqssun.gpssetter.utils

import io.github.jqssun.gpssetter.room.Favorite

object FavoriteTextFormat {
    private const val HEADER = "id\taddress\tlat\tlng"

    fun encode(favorites: List<Favorite>): String = buildString {
        append(HEADER).append('\n')
        favorites.forEach { favorite ->
            val id = favorite.id ?: error("Favorite is missing its ID")
            val latitude = favorite.lat ?: error("Favorite is missing its latitude")
            val longitude = favorite.lng ?: error("Favorite is missing its longitude")
            append(id)
                .append('\t').append(escape(favorite.address.orEmpty()))
                .append('\t').append(latitude)
                .append('\t').append(longitude)
                .append('\n')
        }
    }

    fun decode(text: String): List<Favorite> {
        val lines = text.lineSequence().toList()
        val header = lines.firstOrNull()?.removePrefix("\uFEFF")
        require(header == HEADER) { "Expected favorites text file header" }

        return lines.drop(1)
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line ->
                val fields = line.split('\t')
                require(fields.size == 4) { "Invalid favorites row ${index + 2}" }

                val id = fields[0].toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid favorite ID on row ${index + 2}")
                require(id >= 0) { "Favorite ID must not be negative on row ${index + 2}" }

                val latitude = fields[2].toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid latitude on row ${index + 2}")
                val longitude = fields[3].toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid longitude on row ${index + 2}")
                require(latitude.isFinite() && latitude in -90.0..90.0) {
                    "Invalid latitude on row ${index + 2}"
                }
                require(longitude.isFinite() && longitude in -180.0..180.0) {
                    "Invalid longitude on row ${index + 2}"
                }

                Favorite(
                    id = id,
                    address = unescape(fields[1]),
                    lat = latitude,
                    lng = longitude
                )
            }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            append(
                when (char) {
                    '\\' -> "\\\\"
                    '\t' -> "\\t"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    else -> char.toString()
                }
            )
        }
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\') {
                append(char)
                index++
                continue
            }

            require(index + 1 < value.length) { "Invalid escape in favorite address" }
            append(
                when (val escaped = value[index + 1]) {
                    '\\' -> '\\'
                    't' -> '\t'
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> throw IllegalArgumentException("Invalid escape sequence: \\$escaped")
                }
            )
            index += 2
        }
    }
}
