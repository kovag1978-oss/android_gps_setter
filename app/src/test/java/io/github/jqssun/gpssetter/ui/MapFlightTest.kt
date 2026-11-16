package io.github.jqssun.gpssetter.ui

import io.github.jqssun.gpssetter.utils.AppLocationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFlightTest {
    @Test
    fun flightStartsAndEndsCloseAndMovesThroughOverview() {
        val flight = MapFlight(MapCoordinates(0.0, 0.0), MapCoordinates(40.0, 80.0), 1080, 1920)
        assertEquals(16.0, flight.frame(0f).zoom, 0.0001)
        assertEquals(0.0, flight.frame(0f).longitude, 0.0001)
        assertTrue(flight.frame(0.5f).zoom < 12.0)
        assertEquals(40.0, flight.frame(0.55f).longitude, 0.0001)
        assertEquals(16.0, flight.frame(1f).zoom, 0.0001)
        assertEquals(40.0, flight.frame(1f).latitude, 0.0001)
        assertEquals(80.0, flight.frame(1f).longitude, 0.0001)
    }

    @Test
    fun flightCrossesAntimeridianByShortRoute() {
        val flight = MapFlight(MapCoordinates(0.0, 179.0), MapCoordinates(0.0, -179.0), 1080, 1920)
        assertEquals(180.0, kotlin.math.abs(flight.frame(0.55f).longitude), 0.0001)
        assertEquals(-179.0, flight.frame(1f).longitude, 0.0001)
    }

    @Test
    fun sourceSelectionPrefersSystemAndOtherwiseSortsPackages() {
        val system = MapCoordinates(1.0, 2.0)
        val profiles = listOf(
            AppLocationProfile("z", "Z", enabled = true, started = true, latitude = 9.0, longitude = 9.0),
            AppLocationProfile("a", "A", enabled = true, started = true, latitude = 3.0, longitude = 4.0),
            AppLocationProfile("ignored", "I", enabled = false, started = true)
        )
        assertEquals(system, chooseActiveLocation(setOf(SYSTEM_TARGET, "a"), system, profiles))
        assertEquals(MapCoordinates(3.0, 4.0), chooseActiveLocation(setOf("z", "a"), system, profiles))
        assertEquals(null, chooseActiveLocation(setOf("ignored"), system, profiles))
    }
}
