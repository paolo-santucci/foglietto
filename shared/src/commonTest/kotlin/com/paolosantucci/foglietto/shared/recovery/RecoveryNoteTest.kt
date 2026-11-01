package com.paolosantucci.foglietto.shared.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// Port of test/domain/recovery/recovery_note_test.dart
// Oracle: Dart RecoveryNote test suite (3 cases)

class RecoveryNoteTest {

    private val instant = RecoveryInstant(2026, 6, 14, 10, 30, 0, 123)

    @Test
    fun equalFields_instancesAreEqual() {
        // Two instances with the same path, savedAt, and preview are == (data class contract)
        val a = RecoveryNote(
            path = "/recovery/2026-06-14T10-30-00-123Z.txt",
            savedAt = instant,
            preview = "hello",
        )
        val b = RecoveryNote(
            path = "/recovery/2026-06-14T10-30-00-123Z.txt",
            savedAt = instant,
            preview = "hello",
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differingPath_instancesAreNotEqual() {
        // Any field differs -> not ==
        val a = RecoveryNote(
            path = "/recovery/2026-06-14T10-30-00-123Z.txt",
            savedAt = instant,
            preview = "hello",
        )
        val b = RecoveryNote(
            path = "/recovery/2026-06-14T10-30-00-456Z.txt",
            savedAt = instant,
            preview = "hello",
        )
        assertNotEquals(a, b)
    }

    @Test
    fun savedAtIs7FieldRecoveryInstant_previewLengthPreserved() {
        // savedAt is a RecoveryInstant (7-field calendar value, not epoch millis); preview length preserved
        val preview80 = "a".repeat(80)
        val note = RecoveryNote(
            path = "/recovery/2026-06-14T10-30-00-123Z.txt",
            savedAt = RecoveryInstant(2026, 6, 14, 10, 30, 0, 123),
            preview = preview80,
        )
        // savedAt carries 7 calendar fields (year/month/day/hour/minute/second/millis) — not epoch ms
        assertEquals(2026, note.savedAt.year)
        assertEquals(6, note.savedAt.month)
        assertEquals(14, note.savedAt.day)
        assertEquals(10, note.savedAt.hour)
        assertEquals(30, note.savedAt.minute)
        assertEquals(0, note.savedAt.second)
        assertEquals(123, note.savedAt.millis)
        // preview length preserved as supplied
        assertEquals(80, note.preview.length)
    }
}
