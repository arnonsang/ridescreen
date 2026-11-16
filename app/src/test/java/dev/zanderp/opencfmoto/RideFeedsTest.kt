package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideFeedsTest {
    @Test
    fun rainNote_readsTheFirstWetSlot() {
        assertNull(RideFeeds.rainNote(null))
        assertEquals("No rain for 3 h", RideFeeds.rainNote(DoubleArray(12)))
        assertEquals("Raining now", RideFeeds.rainNote(doubleArrayOf(0.4, 0.0)))
        assertEquals("Rain in about 45 min", RideFeeds.rainNote(doubleArrayOf(0.0, 0.0, 0.0, 0.3)))
        assertEquals("Rain in about 2 h", RideFeeds.rainNote(DoubleArray(12).also { it[8] = 1.0 }))
    }

    @Test
    fun rangeKm_isLastFillOverConsumptionMinusDistanceRidden() {
        assertEquals(300, RideFeeds.rangeKm(lastLiters = 9.0, per100 = 3.0, kmSince = 0.0))
        assertEquals(180, RideFeeds.rangeKm(lastLiters = 9.0, per100 = 3.0, kmSince = 120.0))
        assertEquals(0, RideFeeds.rangeKm(lastLiters = 9.0, per100 = 3.0, kmSince = 900.0))
        assertNull(RideFeeds.rangeKm(lastLiters = 9.0, per100 = 0.0, kmSince = 0.0))
    }

    @Test
    fun sunTimes_bangkokInNovember() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Bangkok")).apply { set(2026, 10, 16, 12, 0) }
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
        val (rise, set) = RideData.sunTimes(13.75, 100.5, cal)!!
        // About 06:16 and 17:46 local time.
        assertEquals((6 * 60 + 16).toDouble(), rise.toDouble(), 6.0)
        assertEquals((17 * 60 + 46).toDouble(), set.toDouble(), 6.0)
    }
}
