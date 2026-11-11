package com.paolosantucci.foglietto.shared.recovery

/**
 * Immutable calendar-field value representing the UTC instant encoded in a recovery filename.
 *
 * ## Design rationale (R-05 / NFR-05)
 *
 * This is intentionally NOT an epoch-millis Long. The recovery filename grammar encodes
 * a calendar tuple (`YYYY-MM-DDTHH-MM-SS-mmmZ`) and that is what gets parsed — no
 * hand-rolled date math, no kotlinx-datetime on the critical path. Every field maps
 * 1:1 to a capture group in [RecoveryFilename]'s regex.
 *
 * Used as:
 *   - the return type of [RecoveryFilename.parse] (a parsed filename → UTC instant)
 *   - the injected-clock return type in `FileRecoveryRepository` (the `now` lambda)
 *   - [RecoveryNote.savedAt] (the timestamp displayed in the UI and used for ordering)
 *
 * Spec refs: §5.1.1, FR-01, NFR-05; assessment R-A1, S-C1.
 *
 * @property year   4-digit year (e.g. 2026).
 * @property month  1..12
 * @property day    1..31
 * @property hour   0..23
 * @property minute 0..59
 * @property second 0..59
 * @property millis 0..999 — millisecond precision; 6-digit (microsecond) fractional rejected by the parser.
 */
data class RecoveryInstant(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millis: Int,
) {

    /**
     * Renders the calendar fields as the recovery-row subtitle `"YYYY-MM-DD HH:MM"` — i.e.
     * `%04d-%02d-%02d %02d:%02d` over ([year], [month], [day], [hour], [minute]).
     *
     * Promoted from Swift so both front-ends share one formatter (FR-07): this replaces the
     * private `subtitle(from:)` in `iosApp/iosApp/Chrome/RecoveryListViewModel.swift`, whose
     * `String(format: "%04d-%02d-%02d %02d:%02d", …)` output is byte-identical, and it feeds
     * the Android `RecoveryRow` subtitle.
     *
     * * Zero-padded to the stated width; the year widens to 4 digits and is never truncated.
     * * [second] and [millis] are **excluded** — the subtitle is minute-precise, so
     *   `23:59:59.999` renders as `23:59` (truncation, never rounding or rollover).
     * * Pure common Kotlin (`padStart`) — no `Date`, no platform date API, no epoch
     *   arithmetic (NFR-06). Total function: no error path.
     *
     * @return a 16-character `"YYYY-MM-DD HH:MM"` string for every non-negative calendar field set.
     */
    fun formatSubtitle(): String {
        val yearText = year.zeroPadded(4)
        val monthText = month.zeroPadded(2)
        val dayText = day.zeroPadded(2)
        val hourText = hour.zeroPadded(2)
        val minuteText = minute.zeroPadded(2)
        return "$yearText-$monthText-$dayText $hourText:$minuteText"
    }
}

/** Left-pads this non-negative field with `0` up to [width] digits, mirroring `%0Nd`. */
private fun Int.zeroPadded(width: Int): String = toString().padStart(width, '0')
