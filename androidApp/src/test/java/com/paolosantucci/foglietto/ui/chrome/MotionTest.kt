package com.paolosantucci.foglietto.ui.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the pure motion table (FR-35 scale-0 half, NFR-05, EC-26).
 *
 * `resolveMotion` is a total function over the system animator scale: the only semantic
 * branch is "collapsed vs. animate", so a collapsed result must never carry a duration.
 */
class MotionTest {

    @Test
    fun `scale zero collapses every transition`() {
        val spec = resolveMotion(0f)

        assertTrue(spec.collapsed)
        assertEquals(0, spec.revealMillis)
        assertEquals(0, spec.hideMillis)
        assertEquals(0, spec.enabledSpringMillis)
    }

    @Test
    fun `a positive scale keeps the tuned durations`() {
        val spec = resolveMotion(1f)

        assertFalse(spec.collapsed)
        assertEquals(200, spec.revealMillis)
        assertEquals(150, spec.hideMillis)
        assertEquals(200, spec.enabledSpringMillis)
    }

    @Test
    fun `a half scale scales the tuned table and stays animated`() {
        val spec = resolveMotion(0.5f)

        assertFalse(spec.collapsed)
        assertEquals(100, spec.revealMillis)
        assertEquals(75, spec.hideMillis)
        assertEquals(100, spec.enabledSpringMillis)
    }

    @Test
    fun `a negative or non-finite scale collapses instead of throwing`() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalidScale ->
            val spec = resolveMotion(invalidScale)

            assertTrue("scale $invalidScale must collapse", spec.collapsed)
            assertEquals(0, spec.revealMillis)
        }
    }

    @Test
    fun `a positive scale never resolves to a zero-length animation`() {
        val spec = resolveMotion(0.001f)

        assertFalse(spec.collapsed)
        assertTrue(spec.revealMillis >= 1)
        assertTrue(spec.hideMillis >= 1)
        assertTrue(spec.enabledSpringMillis >= 1)
    }
}
