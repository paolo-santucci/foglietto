package com.paolosantucci.foglietto.shared.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the promoted `RecoveryInstant.formatSubtitle()` (FR-07, spec §5.1.a).
 *
 * TDD: written FIRST — before the implementation exists, both the symbol and the
 * formatter are unresolved, so this class cannot compile (RED).
 *
 * Every vector is the iOS reference behaviour moved to `commonTest`:
 * the Swift formatter it replaces (`iosApp/iosApp/Chrome/RecoveryListViewModel.swift`,
 * `subtitle(from:)`) formats exactly these five calendar fields with
 * `String(format: "%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute)` —
 * zero-padded, seconds and millis excluded, **no** epoch arithmetic, **no** `Date`.
 *
 * The last two cases are transplanted from the shape assertions of
 * `iosApp/iosAppTests/RecoveryListViewModelTests.swift`
 * (`test_refresh_rowSubtitle_nonEmptyAndMatchesDateShape`): the subtitle is ≥ 16
 * characters and never carries an epoch-derived year.
 */
class RecoveryInstantSubtitleTest {

    // ── 1. single-digit fields are zero-padded; seconds/millis never appear ────
    //
    // Swift: String(format:"%04d-%02d-%02d %02d:%02d", 2026, 5, 4, 9, 7)
    //        → "2026-05-04 09:07"   (second 12 and millis 345 are not formatted)

    @Test
    fun givenSingleDigitFieldsAndNonZeroSeconds_whenFormatSubtitle_thenZeroPaddedAndSecondsMillisDropped() {
        assertEquals(
            "2026-05-04 09:07",
            RecoveryInstant(year = 2026, month = 5, day = 4, hour = 9, minute = 7, second = 12, millis = 345)
                .formatSubtitle(),
        )
    }

    // ── 2. year shorter than four digits is left-padded, not truncated ────────
    //
    // Swift: String(format:"%04d-%02d-%02d %02d:%02d", 999, 1, 1, 0, 0)
    //        → "0999-01-01 00:00"

    @Test
    fun givenThreeDigitYearAtMidnight_whenFormatSubtitle_thenYearIsPaddedToFourDigits() {
        assertEquals(
            "0999-01-01 00:00",
            RecoveryInstant(year = 999, month = 1, day = 1, hour = 0, minute = 0, second = 0, millis = 0)
                .formatSubtitle(),
        )
    }

    // ── 3. last minute of the year — truncation, never rounding/rollover ──────
    //
    // Swift: String(format:"%04d-%02d-%02d %02d:%02d", 2026, 12, 31, 23, 59)
    //        → "2026-12-31 23:59"   (the 59s / 999ms do NOT roll into 00:00 / 2027)

    @Test
    fun givenLastSecondOfYear_whenFormatSubtitle_thenTruncatesToMinuteWithoutRollover() {
        assertEquals(
            "2026-12-31 23:59",
            RecoveryInstant(year = 2026, month = 12, day = 31, hour = 23, minute = 59, second = 59, millis = 999)
                .formatSubtitle(),
        )
    }

    // ── 4. the iOS view-model row vector (RecoveryListViewModelTests) ─────────

    @Test
    fun givenIosRowVector_whenFormatSubtitle_thenMatchesSwiftFormatterOutput() {
        assertEquals(
            "2025-06-15 10:30",
            RecoveryInstant(year = 2025, month = 6, day = 15, hour = 10, minute = 30, second = 0, millis = 0)
                .formatSubtitle(),
        )
    }

    // ── 5. transplanted iOS shape assertions: 16 chars, no epoch year ─────────

    @Test
    fun givenAnyInstant_whenFormatSubtitle_thenShapeIsSixteenCharsAndCarriesNoEpochYear() {
        val subtitle = RecoveryInstant(
            year = 2025, month = 6, day = 15, hour = 10, minute = 30, second = 0, millis = 0,
        ).formatSubtitle()

        assertEquals(16, subtitle.length) // "YYYY-MM-DD HH:MM"
        // NFR-06: epoch arithmetic would surface 1970 here.
        assertFalse(subtitle.contains("1970"))
    }
}
