package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaceTest {
    @Test
    fun coords_fromGeoAndMapsLinks() {
        assertEquals(13.7563 to 100.5018, SharedPlace.coords("geo:13.7563,100.5018?z=15"))
        assertEquals(13.7563 to 100.5018, SharedPlace.coords("geo:0,0?q=13.7563,100.5018(Grand Palace)"))
        // The pin (!3d!4d) wins over the map centre (@).
        assertEquals(
            13.7500 to 100.4913,
            SharedPlace.coords("https://www.google.com/maps/place/Grand+Palace/@13.7563,100.5018,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d13.7500!4d100.4913"),
        )
        assertEquals(13.7 to 100.5, SharedPlace.coords("https://maps.google.com/?q=13.7,100.5"))
        assertNull(SharedPlace.coords("https://maps.app.goo.gl/AbCdEf123"))
    }

    @Test
    fun name_prefersTheTextBeforeTheLink() {
        assertEquals("Grand Palace", SharedPlace.name("Grand Palace\nhttps://maps.app.goo.gl/AbCdEf123", null))
        assertEquals("Grand Palace", SharedPlace.name("https://x", "https://www.google.com/maps/place/Grand+Palace/@13.7,100.5,17z"))
        assertNull(SharedPlace.name("https://x", "https://maps.google.com/?q=13.7,100.5"))
    }

    @Test
    fun overLimit_needsALimitAndAMargin() {
        assertTrue(RideScreen.overLimit(75, 60, 10))
        assertFalse(RideScreen.overLimit(70, 60, 10))
        assertFalse(RideScreen.overLimit(120, null, 10))
        assertFalse(RideScreen.overLimit(120, 60, 0))
    }

    @Test
    fun climbStep_ignoresAltitudeNoise() {
        assertEquals(Triple(100.0, 0.0, 0.0), RideData.climbStep(null, 100.0))
        assertEquals(Triple(100.0, 0.0, 0.0), RideData.climbStep(100.0, 103.0))
        assertEquals(Triple(108.0, 8.0, 0.0), RideData.climbStep(100.0, 108.0))
        assertEquals(Triple(90.0, 0.0, 10.0), RideData.climbStep(100.0, 90.0))
    }

    @Test
    fun isNight_followsSunriseAndSunset() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
        fun at(h: Int) = java.util.Calendar.getInstance().apply { set(2026, 10, 16, h, 0) }
        assertFalse(RideScreen.isNight(13.75, 100.5, at(12)))
        assertTrue(RideScreen.isNight(13.75, 100.5, at(20)))
        assertTrue(RideScreen.isNight(null, null, at(3)))
    }
}
