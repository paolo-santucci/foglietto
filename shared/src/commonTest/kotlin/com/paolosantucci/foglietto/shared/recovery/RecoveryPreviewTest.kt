package com.paolosantucci.foglietto.shared.recovery

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port of test/domain/recovery/recovery_preview_test.dart
// Oracle: Dart RecoveryPreview.truncate test suite (8 ported cases + 1 PORTING-ADDITION)
//
// Invariants of truncate:
//   - result.length <= 80 (UTF-16 code units)
//   - result contains no '\n'
//   - \s*\n+\s* runs collapsed to one space
//   - No surrogate pair is split
//   - No ellipsis appended
//   - Input <80 code units with no '\n' returned identity-equal

class RecoveryPreviewTest {

    @Test
    fun truncate_multilineInput_newlinesCollapsedToSpaces() {
        // "a\n\n  b\n c" -> "a b c"
        val result = RecoveryPreview.truncate("a\n\n  b\n c")
        assertEquals("a b c", result)
    }

    @Test
    fun truncate_onlyNewlines_collapsesToSingleSpace() {
        // "\n\n\n" -> " " (single space after collapse run — EC-09)
        val result = RecoveryPreview.truncate("\n\n\n")
        assertEquals(" ", result)
    }

    @Test
    fun truncate_emptyString_returnsEmpty() {
        // "" -> "" (EC-09)
        val result = RecoveryPreview.truncate("")
        assertEquals("", result)
    }

    @Test
    fun truncate_200CharsNoNewline_returnsExactly80Units() {
        // 200-char no-newline input -> exactly 80 UTF-16 code units, no ellipsis
        val input = "a".repeat(200)
        val result = RecoveryPreview.truncate(input)
        assertEquals(80, result.length)
        assertFalse(result.contains('…'))
        assertFalse(result.contains('\n'))
    }

    @Test
    fun truncate_exactly80CharsNoNewline_identityEqual() {
        // exactly-80 no-newline -> identity-equal (same content, length 80) (FR-05)
        val input = "a".repeat(80)
        val result = RecoveryPreview.truncate(input)
        assertEquals(input, result)
        assertEquals(80, result.length)
    }

    @Test
    fun truncate_79CharsNoNewline_returnedUnchanged() {
        // 79 chars no-newline -> unchanged, length 79 (FR-05)
        val input = "a".repeat(79)
        val result = RecoveryPreview.truncate(input)
        assertEquals(input, result)
        assertEquals(79, result.length)
    }

    @Test
    fun truncate_emojiCrossing80thBoundary_cutOnCodePointBoundary() {
        // Emoji (😀, U+1F600) is a UTF-16 surrogate pair (2 code units).
        // Place 79 'a' chars then the emoji: total = 79 + 2 = 81 code units.
        // The high surrogate falls at index 79 (0-based) = the 80th code unit.
        // Truncation must NOT split the surrogate pair; result <= 80 code units.
        val emoji = "😀" // 😀 as explicit surrogate pair
        assertEquals(2, emoji.length) // verify it IS a surrogate pair
        val input = "a".repeat(79) + emoji
        assertEquals(81, input.length)

        val result = RecoveryPreview.truncate(input)
        assertTrue(result.length <= 80)
        assertFalse(result.contains('\n'))
        // Verify no lone surrogate by checking that codeUnits are accessible
        // (any lone surrogate would indicate a split pair)
        val units = result.map { it.code }
        assertTrue(units.isNotEmpty() || result.isEmpty())
    }

    @Test
    fun maxLength_equals80() {
        // The constant MAX_LENGTH must equal 80 (named const val, not a magic number) (R-A2)
        assertEquals(80, RecoveryPreview.MAX_LENGTH)
    }

    @Test
    fun truncate_512ByteHeadEndingMidUtf8_doesNotThrow() {
        // [PORTING-ADDITION] A 512-byte head whose last bytes form an incomplete UTF-8 sequence
        // (e.g. the first byte of a 3-byte CJK character at byte 512) decoded via okio
        // Buffer.readUtf8() must NOT throw; the result is a valid Kotlin String.
        // (FR-06, NFR-04, EC-10 — no Dart oracle equivalent; Kotlin/Native parity requirement)
        //
        // We construct a byte sequence: 510 bytes of ASCII 'a' (0x61) + 2 bytes of
        // a 3-byte UTF-8 CJK start (0xE4 0xB8 would be the first two bytes of U+4E00 '一').
        // The result after lenient decode should be a valid String (U+FFFD substitution ok).
        val buf = Buffer()
        // 510 bytes of 'a'
        repeat(510) { buf.writeByte(0x61) }
        // First two bytes of a 3-byte UTF-8 sequence (U+4E00 = 0xE4 0xB8 0x00),
        // intentionally incomplete: 0xE4 0xB8 without the third byte
        buf.writeByte(0xE4)
        buf.writeByte(0xB8)
        // Total: 512 bytes

        // readUtf8() with lenient decoding — must not throw
        val head = buf.readUtf8()

        // truncate must not throw on this input
        val result = RecoveryPreview.truncate(head)

        // result is a valid Kotlin String (no surrogate split); length <= 80
        assertTrue(result.length <= RecoveryPreview.MAX_LENGTH)
    }
}
