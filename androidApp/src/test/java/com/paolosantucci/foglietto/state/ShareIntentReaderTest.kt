package com.paolosantucci.foglietto.state

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A unit suite for [ShareIntentReader] — plain JUnit, no Robolectric, no real `Intent`.
 *
 * Pins plan TASK-02 / spec §5.1.7, FR-22, NFR-03:
 *  - [sendReadsExactlyTheTextExtraOnce] and [processTextReadsExactlyTheProcessTextExtraOnce] — the
 *    action selects exactly one extra key, and the supplied lambda is asked for that key once;
 *  - [aNullActionReturnsNullWithoutTouchingTheExtras],
 *    [anUnrelatedActionReturnsNullWithoutTouchingTheExtras] and
 *    [sendDoesNotProbeTheProcessTextExtra] — every non-matching action returns `null` without
 *    probing the other key (the Activity's `getCharSequenceExtra` must not be hit three times);
 *  - [aNonStringCharSequenceIsConvertedWithToString] and [anAbsentExtraReturnsNull] — conversion
 *    and absence;
 *  - [aBlankExtraIsPassedThroughUntouched] — blank is NOT judged here; the shared rule owns
 *    acceptance (single source of truth, FR-03);
 *  - [theConstantsAreThePlatformWireNames] and [theReaderOwnsNoPlatformLoggingOrAcceptanceLogic] —
 *    the four constants are spelled exactly as the platform spells them, and the implementation
 *    carries no platform import, no logging, no normalisation and no acceptance logic.
 */
class ShareIntentReaderTest {

    @Test
    fun `the constants are the platform wire names`() {
        assertEquals("android.intent.action.SEND", ShareIntentReader.ACTION_SEND)
        assertEquals("android.intent.action.PROCESS_TEXT", ShareIntentReader.ACTION_PROCESS_TEXT)
        assertEquals("android.intent.extra.TEXT", ShareIntentReader.EXTRA_TEXT)
        assertEquals("android.intent.extra.PROCESS_TEXT", ShareIntentReader.EXTRA_PROCESS_TEXT)
    }

    @Test
    fun `sendReadsExactlyTheTextExtraOnce`() {
        val recorded = mutableListOf<String>()

        val result = ShareIntentReader.read(ShareIntentReader.ACTION_SEND) { name ->
            recorded += name
            "hello"
        }

        assertEquals("hello", result)
        assertEquals(listOf("android.intent.extra.TEXT"), recorded)
    }

    @Test
    fun `processTextReadsExactlyTheProcessTextExtraOnce`() {
        val recorded = mutableListOf<String>()

        val result = ShareIntentReader.read(ShareIntentReader.ACTION_PROCESS_TEXT) { name ->
            recorded += name
            "sel"
        }

        assertEquals("sel", result)
        assertEquals(listOf("android.intent.extra.PROCESS_TEXT"), recorded)
    }

    @Test
    fun `aNullActionReturnsNullWithoutTouchingTheExtras`() {
        // `Assert.fail` returns Unit, so it cannot type-check as `CharSequence?` — throw instead.
        val result = ShareIntentReader.read(null) { name ->
            throw AssertionError("the extra lambda must not run for a null action, asked for '$name'")
        }

        assertNull(result)
    }

    @Test
    fun `anUnrelatedActionReturnsNullWithoutTouchingTheExtras`() {
        var invocations = 0

        val result = ShareIntentReader.read("android.intent.action.VIEW") { name ->
            invocations++
            throw AssertionError("the extra lambda must not run for VIEW, asked for '$name'")
        }

        assertNull(result)
        assertEquals(0, invocations)
    }

    @Test
    fun `sendDoesNotProbeTheProcessTextExtra`() {
        var invocations = 0

        val result = ShareIntentReader.read(ShareIntentReader.ACTION_SEND) { name ->
            invocations++
            if (name == ShareIntentReader.EXTRA_PROCESS_TEXT) "x" else null
        }

        assertNull(result)
        assertEquals("exactly one probe of the TEXT key", 1, invocations)
    }

    @Test
    fun `aNonStringCharSequenceIsConvertedWithToString`() {
        val payload = StringBuilder("abc")

        val result = ShareIntentReader.read(ShareIntentReader.ACTION_SEND) { payload }

        assertEquals("abc", result)
    }

    @Test
    fun `anAbsentExtraReturnsNull`() {
        assertNull(ShareIntentReader.read(ShareIntentReader.ACTION_SEND) { null })
        assertNull(ShareIntentReader.read(ShareIntentReader.ACTION_PROCESS_TEXT) { null })
    }

    @Test
    fun `aBlankExtraIsPassedThroughUntouched`() {
        // No acceptance/normalisation here: the shared rule is the single source of truth (D5).
        assertEquals("   ", ShareIntentReader.read(ShareIntentReader.ACTION_SEND) { "   " })
        assertEquals("", ShareIntentReader.read(ShareIntentReader.ACTION_SEND) { "" })
    }

    @Test
    fun `theReaderOwnsNoPlatformLoggingOrAcceptanceLogic`() {
        val code = readerSource().code()

        listOf(
            "import android",
            "androidx",
            "Log.",
            "println",
            "Intent(",
            "trim(",
            "isBlank",
            "isEmpty",
            "Regex(",
            "replace(",
            "take(",
            ".length",
        ).forEach { forbidden ->
            assertFalse(
                "ShareIntentReader.kt must not contain '$forbidden' — it is a total, " +
                    "platform-free function with no logging, no normalisation and no acceptance " +
                    "logic (spec §5.1.7, NFR-03).",
                code.contains(forbidden),
            )
        }

        assertEquals(
            "the reader declares exactly the four platform constants",
            4,
            Regex("""\bconst val\b""").findAll(code).count(),
        )
        assertTrue(
            "the reader is the object the spec names",
            code.contains("object ShareIntentReader"),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** The reader's source file, resolved from either the module dir or the repo root. */
    private fun readerSource(): File {
        val root = listOf(File("src/main/java"), File("androidApp/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("No androidApp main source dir from ${File(".").absolutePath}")
        return root.resolve("com/paolosantucci/foglietto/state/ShareIntentReader.kt")
            .also { file -> assertTrue("${file.path} must exist", file.isFile) }
    }

    /** Source with block and line comments removed, so the scan pins code, not prose. */
    private fun File.code(): String =
        readText()
            .replace(BLOCK_COMMENT, " ")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private companion object {
        val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    }
}
