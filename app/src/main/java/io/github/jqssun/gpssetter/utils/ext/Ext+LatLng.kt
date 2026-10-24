package io.github.jqssun.gpssetter.utils.ext

import android.content.Context
import android.location.Geocoder
import io.github.jqssun.gpssetter.ui.CustomLatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

suspend fun CustomLatLng.getAddress(context: Context) = flow {
    val address = withContext(Dispatchers.IO) {
        try {
            val addresses =
                Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            val addressLine = addresses?.firstOrNull()?.getAddressLine(0).orEmpty()
            val parts = addressLine.split(",", limit = 2)
            if (parts.size > 1) {
                buildString {
                    append(parts[0])
                    val remainder = parts[1].trim()
                    if (remainder.isNotEmpty()) {
                        append("\n").append(remainder)
                    }
                }
            } else {
                addressLine
            }
        } catch (_: IOException) {
            ""
        }
    }
    emit(address)
}