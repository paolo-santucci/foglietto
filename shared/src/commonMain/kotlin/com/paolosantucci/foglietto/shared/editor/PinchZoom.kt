package com.paolosantucci.foglietto.shared.editor

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Pure pinch-to-zoom arithmetic shared between platforms.
 *
 * This is the ONLY M3 addition to the shared module (NFR-05 carve-out, OQ-02 resolution).
 * It contains zero Apple-framework or platform-specific dependencies — it is platform-neutral
 * commonMain code, fully jvmTest-coverable on Linux.
 *
 * Formula (spec §5.1.b, FR-17):
 *   delta = round(ln(scale) / ln(1.15))
 *   targetIndex = coerceIn(startIndex + delta, 0, 20)
 *
 * Tie-break oracle (OQ-03): kotlin.math.round is round-half-away-from-zero (JVM Math.round
 * semantics: ties go toward +∞). This is the pinned oracle so Swift callers can never
 * re-derive the tie-break — they MUST call this object via the XCFramework (gate-enforced).
 *
 * Short-circuit: scale <= 0 || scale == 1.0 → delta 0 (identity / invalid-scale guard).
 *
 * Spec refs: FR-17, NFR-05; spec §5.1.b; plan TASK-01 (sp-20260621-kmp-m3-swiftui-editor-core)
 */
object PinchZoom {

    private val LOG_BASE: Double = ln(1.15)

    /**
     * Maps a pinch gesture [scale] factor to a font-size slot delta.
     *
     * @param scale   The cumulative gesture scale factor (1.0 = no zoom).
     *                Values ≤ 0 are invalid and return 0 (guard, not an error).
     * @param startIndex  The slot index at gesture begin (unused in the formula — kept
     *                    in the signature so Swift callers have a single consistent call
     *                    surface matching [clampedTargetIndex]).
     * @return        Signed slot delta to apply to startIndex.
     */
    fun scaleToSlotDelta(scale: Double, @Suppress("UNUSED_PARAMETER") startIndex: Int): Int {
        if (scale <= 0.0 || scale == 1.0) return 0
        return (ln(scale) / LOG_BASE).roundToInt()
    }

    /**
     * Returns the clamped target font-size slot index after applying the pinch [scale].
     *
     * Equivalent to `coerceIn(startIndex + scaleToSlotDelta(scale, startIndex), 0, 20)`.
     *
     * @param scale       Cumulative pinch gesture scale factor.
     * @param startIndex  Slot index captured at gesture begin; must be in [0, 20].
     * @return            New slot index in [0, 20].
     */
    fun clampedTargetIndex(scale: Double, startIndex: Int): Int {
        val delta = scaleToSlotDelta(scale, startIndex)
        return (startIndex + delta).coerceIn(0, 20)
    }
}
