package com.paolosantucci.foglietto.shared.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Port of test/domain/recovery/recovery_filename_test.dart
// Oracle: Dart RecoveryFilename.parse test suite (9 cases)
//
// Accepted grammar:
//   YYYY-MM-DDTHH-MM-SS-mmmZ[.txt]
//   YYYY-MM-DDTHH-MM-SS-mmmZ-<N>[.txt]
//
// Rejection rules:
//   - 6-digit fractional (microsecond width) -> null
//   - malformed / empty -> null
//   - colons in timestamp -> null (filesystem stems never contain colons)
//   - partial/truncated stems -> null
//   - Never throws on any input.

class RecoveryFilenameTest {

    @Test
    fun parse_validMsStem_returnsCorrectInstant() {
        // "2026-06-20T13-04-09-512Z" -> RecoveryInstant(2026,6,20,13,4,9,512)
        val result = RecoveryFilename.parse("2026-06-20T13-04-09-512Z")
        assertEquals(RecoveryInstant(2026, 6, 20, 13, 4, 9, 512), result)
    }

    @Test
    fun parse_stemWithCollisionSuffix_returnsInstantSuffixDiscarded() {
        // "2026-06-20T13-04-09-512Z-1" -> same instant (suffix discarded)
        val result = RecoveryFilename.parse("2026-06-20T13-04-09-512Z-1")
        assertEquals(RecoveryInstant(2026, 6, 20, 13, 4, 9, 512), result)
    }

    @Test
    fun parse_stemWithTxtExtension_returnsCorrectInstant() {
        // "2026-06-20T13-04-09-512Z.txt" -> correct RecoveryInstant
        val result = RecoveryFilename.parse("2026-06-20T13-04-09-512Z.txt")
        assertEquals(RecoveryInstant(2026, 6, 20, 13, 4, 9, 512), result)
    }

    @Test
    fun parse_stemWithCollisionSuffixAndExtension_returnsCorrectInstant() {
        // "2026-06-20T13-04-09-512Z-2.txt" -> correct RecoveryInstant
        val result = RecoveryFilename.parse("2026-06-20T13-04-09-512Z-2.txt")
        assertEquals(RecoveryInstant(2026, 6, 20, 13, 4, 9, 512), result)
    }

    @Test
    fun parse_sixDigitMicrosecondFractional_returnsNull() {
        // 6-digit (microsecond) precision "2026-06-20T13-04-09-512000Z" -> null, never throws (FR-02, R-05)
        val result = RecoveryFilename.parse("2026-06-20T13-04-09-512000Z")
        assertNull(result)
    }

    @Test
    fun parse_emptyString_returnsNull() {
        // "" -> null, never throws (FR-03)
        val result = RecoveryFilename.parse("")
        assertNull(result)
    }

    @Test
    fun parse_garbageFilename_returnsNull() {
        // "garbage.txt" -> null, never throws (FR-03)
        val result = RecoveryFilename.parse("garbage.txt")
        assertNull(result)
    }

    @Test
    fun parse_colonSeparators_returnsNull() {
        // "2026-06-20T13:04:09-512Z" (colons instead of dashes) -> null (FR-03)
        // filename stems never contain colons
        val result = RecoveryFilename.parse("2026-06-20T13:04:09-512Z")
        assertNull(result)
    }

    @Test
    fun parse_truncatedStem_returnsNull() {
        // "2026-06-20T13-04" (partial/truncated) -> null (FR-03)
        val result = RecoveryFilename.parse("2026-06-20T13-04")
        assertNull(result)
    }
}
