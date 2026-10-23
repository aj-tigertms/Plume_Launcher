package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SunTest {
    private val london = ZoneId.of("Europe/London")
    private val dubai = ZoneId.of("Asia/Dubai")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun near(expected: Long, actual: Long) =
        assertEquals("off by ${(actual - expected) / 60_000} min", expected.toDouble(), actual.toDouble(), 3 * 60_000.0)

    @Test fun londonMidsummer() { // published: 04:43 and 21:21 BST
        val day = Sun.day(51.5074, -0.1278, LocalDate.of(2026, 6, 21), london)!!
        near(at(london, 2026, 6, 21, 4, 43), day.rise)
        near(at(london, 2026, 6, 21, 21, 21), day.set)
    }

    @Test fun londonMidwinter() { // published: 08:04 and 15:54 GMT
        val day = Sun.day(51.5074, -0.1278, LocalDate.of(2026, 12, 21), london)!!
        near(at(london, 2026, 12, 21, 8, 4), day.rise)
        near(at(london, 2026, 12, 21, 15, 54), day.set)
    }

    @Test fun dubai() { // by hand: solar noon 12:03, declination -11.5°, half-day 5 h 42 min
        val day = Sun.day(25.2048, 55.2708, LocalDate.of(2026, 10, 23), dubai)!!
        near(at(dubai, 2026, 10, 23, 6, 21), day.rise)
        near(at(dubai, 2026, 10, 23, 17, 45), day.set)
    }

    @Test fun darkAfterDusk() {
        assertTrue(Sun.isDark(25.2048, 55.2708, at(dubai, 2026, 10, 23, 5, 0), dubai))
        assertFalse(Sun.isDark(25.2048, 55.2708, at(dubai, 2026, 10, 23, 12, 0), dubai))
        assertFalse(Sun.isDark(25.2048, 55.2708, at(dubai, 2026, 10, 23, 17, 55), dubai)) // inside the 20 min after sunset
        assertTrue(Sun.isDark(25.2048, 55.2708, at(dubai, 2026, 10, 23, 18, 30), dubai))
        assertTrue(Sun.isDark(25.2048, 55.2708, at(dubai, 2026, 10, 23, 23, 30), dubai))
    }

    @Test fun poles() {
        val tromso = ZoneId.of("Europe/Oslo")
        assertNull(Sun.day(69.65, 18.96, LocalDate.of(2026, 6, 21), tromso))
        assertFalse(Sun.isDark(69.65, 18.96, at(tromso, 2026, 6, 21, 0, 30), tromso)) // midnight sun
        assertTrue(Sun.isDark(69.65, 18.96, at(tromso, 2026, 12, 21, 12, 0), tromso)) // polar night
        assertNotNull(Sun.day(69.65, 18.96, LocalDate.of(2026, 3, 21), tromso))
    }
}
