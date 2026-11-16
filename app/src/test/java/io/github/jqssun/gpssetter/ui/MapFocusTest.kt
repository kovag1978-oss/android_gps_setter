package io.github.jqssun.gpssetter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapFocusTest {
    @Test
    fun applicationFocusesOnAppliedPointEvenWhenOtherLocationsAreActive() {
        assertEquals(
            MapFocus.Applied(MapCoordinates(12.0, 34.0)),
            mapFocusFor(
                MapCommandResult.Applied(12.0, 34.0),
                listOf(MapCoordinates(56.0, 78.0))
            )
        )
    }

    @Test
    fun partialStopShowsAllDistinctRemainingLocations() {
        val first = MapCoordinates(12.0, 34.0)
        val second = MapCoordinates(-12.0, -34.0)
        assertEquals(
            MapFocus.Remaining(listOf(first, second)),
            mapFocusFor(MapCommandResult.Stopped(), listOf(first, first, second))
        )
    }

    @Test
    fun stoppingLastLocationRequestsRealLocation() {
        assertEquals(
            MapFocus.RealLocation,
            mapFocusFor(MapCommandResult.Stopped(), emptyList())
        )
    }
}
