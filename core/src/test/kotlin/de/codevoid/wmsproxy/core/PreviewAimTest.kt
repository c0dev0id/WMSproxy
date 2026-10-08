package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAimTest {

    private val colorado = LonLatBox(-109.1, 36.9, -102.0, 41.0)
    private val world = LonLatBox(-180.0, -90.0, 180.0, 90.0)
    private val denver = LonLat(-104.99, 39.74)
    private val hamburg = LonLat(9.99, 53.55)

    @Test
    fun `a regional layer opens at the phone when the phone is inside it`() {
        assertEquals(denver, PreviewAim.startAt(colorado, denver))
    }

    @Test
    fun `a regional layer opens at its middle when the phone is elsewhere or unknown`() {
        assertEquals(colorado.centre, PreviewAim.startAt(colorado, hamburg))
        assertEquals(colorado.centre, PreviewAim.startAt(colorado, null))
    }

    @Test
    fun `a world layer, or none declared, opens at the phone or on the world`() {
        assertEquals(hamburg, PreviewAim.startAt(world, hamburg))
        assertEquals(hamburg, PreviewAim.startAt(null, hamburg))
        assertNull(PreviewAim.startAt(world, null))
        assertNull(PreviewAim.startAt(null, null))
    }

    @Test
    fun `a box is the world when it spans most of both axes`() {
        assertTrue(world.isWorld)
        // WebMercator's limits, the shape a global tile cache declares.
        assertTrue(LonLatBox(-180.0, -85.05, 180.0, 85.05).isWorld)
        assertFalse(colorado.isWorld)
        assertFalse(LonLatBox(-180.0, 0.0, 180.0, 90.0).isWorld)
        assertEquals(-105.55, colorado.centre.longitude, 1e-9)
        assertEquals(38.95, colorado.centre.latitude, 1e-9)
        assertTrue(colorado.contains(denver))
        assertFalse(colorado.contains(hamburg))
    }
}
