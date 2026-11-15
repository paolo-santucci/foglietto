package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the top bar's contract (FR-20, FR-33, EC-03, NFR-04).
 *
 * ## What this bar is now
 *
 * Undo, Redo and the ⋮ moved to the bottom bar ([EditorActionRow]) when the reference put the whole
 * editing surface there, so the title-less top bar keeps the one action that leaves the app: Share.
 * The bar therefore has no order to pin beyond "Share, right-aligned in its rounded square" — what
 * this file pins instead is the part that can silently regress:
 *
 *  - nothing else may creep back into the bar (Undo/Redo/⋮/Clear belong to the bottom bar);
 *  - Share keeps its string-resource label and its 48dp rounded-square container;
 *  - the shell still re-reads the shared predicate at click time (EC-30), which is a shell
 *    obligation, not this row's — `FogliettoAppTest` pins that half.
 *
 * The structural half is a source scan because a Compose UI test is Tier C and structurally
 * excluded from this module (OQ-20), the same idiom the m8 gate uses.
 */
class TopActionBarChromeTest {

    @Test
    fun `the bar is one right-aligned row of the share control`() {
        val code = barSource()

        listOf(
            "R.string.share",
            "Icons.Filled.Share",
        ).forEach { token -> assertTrue("the bar renders `$token`.", code.contains(token)) }

        assertEquals(
            "Share is the bar's whole content — one TonalActionButton, no cluster.",
            1,
            Regex("""TonalActionButton\(""").findAll(code).count(),
        )
        assertEquals("One row, no second cluster.", 1, Regex("""Row\(""").findAll(code).count())
        assertTrue(
            "The cluster keeps Keep's 8dp clearance from the edge.",
            code.contains("padding(end = EndPadding)"),
        )
        assertTrue("Share's enabled state is the shared predicate.", code.contains("enabled = canShare"))
    }

    @Test
    fun `the moved actions never come back to the top bar`() {
        val code = barSource()

        listOf(
            "R.string.undo",
            "R.string.redo",
            "R.string.open_menu",
            "R.string.clear_editor",
            "R.string.copy",
            "R.string.paste",
        ).forEach { label ->
            assertFalse(
                "`$label` belongs to the bottom bar now, never to the top bar.",
                code.contains(label),
            )
        }
        assertFalse("No Canvas glyph geometry may survive in the bar.", code.contains("Canvas("))
    }

    @Test
    fun `the bar delegates its container to the shared rounded-square button`() {
        val code = barSource()

        assertFalse(
            "The bar must not hand-roll its own container: the size and shape live in one place.",
            code.contains("RoundedCornerShape("),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The bar's own source, comment-stripped: the composable declaration, not a call site. */
    private fun barSource(): String =
        mainSources().single { it.contains("fun TopActionBar(") }

    /** Main-source files, resolved from either the module dir or the repo root, comments removed. */
    private fun mainSources(): List<String> {
        val root = listOf(File("src/main/java"), File("androidApp/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("No androidApp main source dir from ${File(".").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.code() }
            .toList()
            .also { files -> assertTrue("main sources must not be empty", files.isNotEmpty()) }
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
