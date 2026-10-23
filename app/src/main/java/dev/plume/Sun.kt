package dev.plume

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise and sunset, worked out on the device: Android has no API for them, and the sums need only the
 * date and a place (the weather's saved location), no network. The standard sunrise equation, good to a
 * minute or two away from the poles.
 */
internal object Sun {
    /** The Auto theme stays light this long after sunset: the sky is still bright just after. */
    const val DUSK_MS = 20 * 60_000L

    /** Sunrise and sunset, as epoch millis. */
    class Day(val rise: Long, val set: Long)

    /** Sunrise and sunset on [date] at the place; null where the sun doesn't rise or set that day. */
    fun day(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Day? = times(lat, lon, date, zone).first

    /** Dark between sunset (plus [DUSK_MS]) and sunrise, at [now], at the place. */
    fun isDark(lat: Double, lon: Double, now: Long, zone: ZoneId): Boolean {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (day, up) = times(lat, lon, date, zone)
        day ?: return !up
        return now < day.rise || now >= day.set + DUSK_MS
    }

    private fun times(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Pair<Day?, Boolean> {
        val noon = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val jdNoon = noon / 86_400_000.0 + 2440587.5
        // Days since 2000-01-01 12:00 UTC to the place's mean solar noon nearest local noon.
        val n = Math.round(jdNoon - 2451545.0 + lon / 360.0).toDouble()
        val jStar = n - lon / 360.0
        val m = norm(357.5291 + 0.98560028 * jStar)
        val c = 1.9148 * sinD(m) + 0.02 * sinD(2 * m) + 0.0003 * sinD(3 * m)
        val lambda = norm(m + c + 180.0 + 102.9372)
        val transit = 2451545.0 + jStar + 0.0053 * sinD(m) - 0.0069 * sinD(2 * lambda)
        val decl = Math.toDegrees(asin(sinD(lambda) * sinD(23.4397)))
        // -0.833°: the sun's disc touches the horizon, with the air's refraction.
        val cosW = (sinD(-0.833) - sinD(lat) * sinD(decl)) / (cosD(lat) * cosD(decl))
        if (cosW < -1) return null to true // midnight sun
        if (cosW > 1) return null to false // polar night
        val w = Math.toDegrees(acos(cosW))
        fun millis(jd: Double) = ((jd - 2440587.5) * 86_400_000.0).roundToLong()
        return Day(millis(transit - w / 360.0), millis(transit + w / 360.0)) to true
    }

    private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))
}
