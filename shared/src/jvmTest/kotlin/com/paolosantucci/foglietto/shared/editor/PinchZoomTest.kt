package com.paolosantucci.foglietto.shared.editor

import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD test suite for [PinchZoom] — pure pinch arithmetic.
 *
 * Written FIRST, before PinchZoom.kt exists (RED phase).
 * The implementation in PinchZoom.kt makes these GREEN.
 *
 * Spec refs: FR-17, NFR-05; spec §5.1.b
 * Plan: TASK-01, sp-20260621-kmp-m3-swiftui-editor-core-plan.md
 *
 * False-green guard: [testCountGuard] asserts the suite has ≥ 9 test methods
 * so an empty-suite misconfiguration is caught immediately.
 *
 * Oracle: kotlin.math.round is round-half-away-from-zero (JVM Math.round
 * semantics), pinned here as the authoritative tie-break for OQ-03.
 */
class PinchZoomTest {

    // -------------------------------------------------------------------------
    // False-green guard (NFR-05, plan §4 TASK-01 qa-pattern)
    // -------------------------------------------------------------------------

    /**
     * Asserts that this class declares at least 9 @Test methods so a test-count
     * regression (e.g. accidentally deleting cases) is caught immediately.
     *
     * Uses reflection to count methods annotated with @Test in this class.
     */
    @Test
    fun testCountGuard() {
        val testMethodCount = this::class.java.methods
            .count { method ->
                method.isAnnotationPresent(Test::class.java)
            }
        assertTrue(
            testMethodCount >= 9,
            "PinchZoomTest must declare ≥ 9 @Test methods (false-green guard); " +
                "found $testMethodCount. Add the missing cases."
        )
    }

    // -------------------------------------------------------------------------
    // scaleToSlotDelta — worked examples from the plan (§4 TASK-01)
    // -------------------------------------------------------------------------

    /**
     * scale == 1.15 → delta 1.
     * ln(1.15)/ln(1.15) == 1.0 exactly; round(1.0) == 1.
     */
    @Test
    fun scaleToSlotDelta_exactOneSlot_returnsOne() {
        assertEquals(1, PinchZoom.scaleToSlotDelta(1.15, 8))
    }

    /**
     * scale ≈ 1.521 (1.15^3) → delta 3.
     * ln(1.15^3)/ln(1.15) == 3.0 exactly; round(3.0) == 3.
     */
    @Test
    fun scaleToSlotDelta_threeSlots_returnsThree() {
        val scale = 1.15.pow(3)
        assertEquals(3, PinchZoom.scaleToSlotDelta(scale, 5))
    }

    /**
     * scale == 1.0 → short-circuit δ 0 (no zoom, identity).
     */
    @Test
    fun scaleToSlotDelta_scaleOne_returnsZero() {
        assertEquals(0, PinchZoom.scaleToSlotDelta(1.0, 8))
    }

    /**
     * scale == 0.0 → invalid scale short-circuit → δ 0.
     */
    @Test
    fun scaleToSlotDelta_scaleZero_returnsZero() {
        assertEquals(0, PinchZoom.scaleToSlotDelta(0.0, 8))
    }

    /**
     * scale == -1.0 → invalid scale (≤ 0) short-circuit → δ 0.
     */
    @Test
    fun scaleToSlotDelta_scaleNegative_returnsZero() {
        assertEquals(0, PinchZoom.scaleToSlotDelta(-1.0, 8))
    }

    /**
     * Tie-break: scale such that ln(scale)/ln(1.15) == +0.5 → round-half-away-from-zero → δ 1.
     *
     * scale = 1.15^0.5 = sqrt(1.15) ≈ 1.07238.
     * kotlin.math.round(0.5) == 1 (round-half-away-from-zero, OQ-03 oracle).
     */
    @Test
    fun scaleToSlotDelta_positiveHalfTieBreak_roundsUp() {
        val scale = 1.15.pow(0.5)
        assertEquals(1, PinchZoom.scaleToSlotDelta(scale, 8))
    }

    /**
     * Tie-break: scale such that ln(scale)/ln(1.15) == -0.5 → round-half-away-from-zero → δ -1.
     *
     * scale = 1.15^(-0.5) = 1/sqrt(1.15) ≈ 0.93260.
     * kotlin.math.round(-0.5) == -1 (round-half-away-from-zero, OQ-03 oracle).
     *
     * Note: scale > 0 so the short-circuit does not fire; scale != 1.0 so no identity.
     */
    @Test
    fun scaleToSlotDelta_negativeHalfTieBreak_roundsDown() {
        val scale = 1.15.pow(-0.5)
        assertEquals(-1, PinchZoom.scaleToSlotDelta(scale, 8))
    }

    // -------------------------------------------------------------------------
    // clampedTargetIndex — plan test cases (§4 TASK-01)
    // -------------------------------------------------------------------------

    /**
     * scale == 1.15, startIndex 8 → delta 1 → target 9 (within [0,20]).
     */
    @Test
    fun clampedTargetIndex_normalZoomIn_returnsNineFromEight() {
        assertEquals(9, PinchZoom.clampedTargetIndex(1.15, 8))
    }

    /**
     * Lower clamp: startIndex 0 + negative delta → clamped to 0.
     * Use a scale that produces a large negative delta (e.g. 1.15^(-5) ≈ 0.497).
     * delta = round(-5.0) = -5; target = 0 + (-5) = -5 → coerceIn(0,20) = 0.
     */
    @Test
    fun clampedTargetIndex_lowerClamp_returnsZero() {
        val scale = 1.15.pow(-5.0)
        assertEquals(0, PinchZoom.clampedTargetIndex(scale, 0))
    }

    /**
     * Upper clamp: startIndex 20 + positive delta → clamped to 20.
     * Use scale = 1.15^3 ≈ 1.521 → delta 3; target = 20 + 3 = 23 → coerceIn(0,20) = 20.
     */
    @Test
    fun clampedTargetIndex_upperClamp_returnsTwenty() {
        val scale = 1.15.pow(3.0)
        assertEquals(20, PinchZoom.clampedTargetIndex(scale, 20))
    }

    /**
     * Identity: scale == 1.0 → delta 0 → target == startIndex (short-circuit path).
     * Tested at several startIndex values.
     */
    @Test
    fun clampedTargetIndex_identityAtScaleOne_returnsStart() {
        listOf(0, 5, 8, 15, 20).forEach { start ->
            assertEquals(
                start,
                PinchZoom.clampedTargetIndex(1.0, start),
                "clampedTargetIndex(1.0, $start) should equal $start"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Additional invariants
    // -------------------------------------------------------------------------

    /**
     * Output is always within [0, 20] for any startIndex in [0,20] and any scale.
     */
    @Test
    fun clampedTargetIndex_alwaysInRange() {
        val scales = listOf(0.001, 0.5, 0.866, 1.0, 1.15, 1.5, 2.0, 10.0)
        val indices = listOf(0, 1, 8, 19, 20)
        for (scale in scales) {
            for (start in indices) {
                val result = PinchZoom.clampedTargetIndex(scale, start)
                assertTrue(
                    result in 0..20,
                    "clampedTargetIndex($scale, $start) = $result is outside [0,20]"
                )
            }
        }
    }

    /**
     * scaleToSlotDelta: negative scales beyond -1.0 also short-circuit to 0.
     */
    @Test
    fun scaleToSlotDelta_veryNegativeScale_returnsZero() {
        assertEquals(0, PinchZoom.scaleToSlotDelta(-100.0, 10))
    }

    /**
     * Verify the ln-ratio formula for a zoom-OUT case: scale < 1, scale > 0.
     * scale = 1.15^(-2) ≈ 0.756 → ln(scale)/ln(1.15) == -2.0 → round(-2.0) == -2.
     */
    @Test
    fun scaleToSlotDelta_twoSlotsDown_returnsNegativeTwo() {
        val scale = 1.15.pow(-2.0)
        assertEquals(-2, PinchZoom.scaleToSlotDelta(scale, 10))
    }
}
