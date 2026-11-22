package com.craft.capturespace.ai

import android.media.ExifInterface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a photo file says about itself (EXIF), read on the phone: when it was taken and, if the
 * camera saved it, where. Coordinates are shown as they are; they are never sent anywhere to be
 * turned into an address (the person can open them in their maps app).
 */
object PhotoInfo {
    data class Info(val taken: LocalDateTime?, val lat: Double?, val lon: Double?, val camera: String?) {
        val hasLocation get() = lat != null && lon != null
    }

    fun read(path: String): Info? = try {
        val e = ExifInterface(path)
        val taken = (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))?.let {
            runCatching { LocalDateTime.parse(it.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")) }.getOrNull()
        }
        val ll = FloatArray(2)
        @Suppress("DEPRECATION")
        val hasLl = e.getLatLong(ll) && !(ll[0] == 0f && ll[1] == 0f) && ll[0] in -90f..90f && ll[1] in -180f..180f
        val camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE)?.trim(), e.getAttribute(ExifInterface.TAG_MODEL)?.trim())
            .filter { it.isNotEmpty() }.distinct().joinToString(" ").takeIf { it.isNotEmpty() }?.take(60)
        Info(taken, if (hasLl) ll[0].toDouble() else null, if (hasLl) ll[1].toDouble() else null, camera)
            .takeIf { it.taken != null || it.hasLocation }
    } catch (_: Exception) { null }

    /** "9.9312° N, 76.2673° E" */
    fun coords(lat: Double, lon: Double): String =
        String.format(Locale.ENGLISH, "%.4f° %s, %.4f° %s", kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

    /** Back from "9.9312° N, 76.2673° E" to numbers, for opening the maps app. */
    fun parse(s: String): Pair<Double, Double>? {
        val m = Regex("""^([\d.]+)° ([NS]), ([\d.]+)° ([EW])$""").find(s.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[3].toDoubleOrNull() ?: return null
        return (if (m.groupValues[2] == "S") -lat else lat) to (if (m.groupValues[4] == "W") -lon else lon)
    }

    fun takenText(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH))
}
