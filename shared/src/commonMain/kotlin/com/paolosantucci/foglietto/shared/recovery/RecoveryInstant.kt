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
)
