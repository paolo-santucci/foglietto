package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the top bar's contract (FR-20, FR-32, FR-33, EC-03, NFR-04).
 *
 * The bar is the one band the product owner pinned by reference ("from left to right: Undo, Redo,
 * Share and Menu, each in a rounded square"), so its *order* is part of the contract and is pinned
 * here — the same source-scan idiom the m8 gate and the row's tests use, because this module
 * excludes Tier C Compose UI tests (OQ-20).
 *
 * What a plain JVM test can still observe directly is the enablement rule: Share follows the shared
 * predicate, Undo/Redo follow their own stacks independently, and the ⋮ is always available.
 */
class TopActionBarChromeTest {

    @Test
    fun `share follows the shared predicate and history follows its own stacks`() {
        val empty = topBarEnabled(canUndo = false, canRedo = false, canShare = false)
        assertFalse("an empty history cannot be undone.", empty.undo)
        assertFalse("an empty history cannot be redone.", empty.redo)
        assertFalse("an empty buffer cannot be shared (FR-33).", empty.share)

        val populated = topBarEnabled(canUndo = true, canRedo = true, canShare = true)
        assertTrue(populated.undo)
        assertTrue(populated.redo)
        assertTrue(populated.share)
    }

    @Test
    fun `undo and redo follow their own stacks independently`() {
        // The classic regression: one flag drives both, so Undo lights up Redo.
        val undoOnly = topBarEnabled(canUndo = true, canRedo = false, canShare = true)
        assertTrue(undoOnly.undo)
        assertFalse(undoOnly.redo)

        val redoOnly = topBarEnabled(canUndo = false, canRedo = true, canShare = true)
        assertFalse(redoOnly.undo)
        assertTrue(redoOnly.redo)
    }

    @Test
    fun `the menu never depends on the buffer`() {
        // EC-03: the ⋮ is chrome, not a buffer action.
        assertTrue(topBarEnabled(canUndo = false, canRedo = false, canShare = false).menu)
        assertTrue(topBarEnabled(canUndo = true, canRedo = true, canShare = true).menu)
    }

    @Test
    fun `the bar orders undo, redo, share then the menu`() {
        val code = barSource()

        val positions = listOf(
            "R.string.undo",
            "R.string.redo",
            "R.string.share",
            "R.string.open_menu",
        ).map { label ->
            val index = code.indexOf(label)
            assertTrue("the bar renders `$label`.", index >= 0)
            index
        }

        assertEquals(
            "The bar must render its four actions left to right: Undo, Redo, Share, Menu.",
            positions.sorted(),
            positions,
        )
    }

    @Test
    fun `the bar draws the Material icons already in use`() {
        val code = barSource()

        listOf(
            "Icons.AutoMirrored.Filled.Undo",
            "Icons.AutoMirrored.Filled.Redo",
            "Icons.Filled.Share",
            "Icons.Filled.MoreVert",
        ).forEach { icon -> assertTrue("the bar renders `$icon`.", code.contains(icon)) }

        assertFalse("No Canvas glyph geometry may survive in the bar.", code.contains("Canvas("))
    }

    @Test
    fun `the bar is one row that ends at the window edge`() {
        val code = barSource()

        assertEquals(
            "One row of controls, no second cluster.",
            1,
            Regex("""Row\(""").findAll(code).count(),
        )
        assertTrue(
            "The cluster keeps Keep's 8dp clearance from the edge.",
            code.contains("padding(end = EndPadding)"),
        )
    }

    @Test
    fun `the bar delegates every control to the shared rounded-square button`() {
        val code = barSource()

        assertEquals(
            "All four actions are TonalActionButton — one shape, four instances.",
            4,
            Regex("""TonalActionButton\(""").findAll(code).count(),
        )
        assertFalse(
            "The bar must not hand-roll its own container: the size and shape live in one place.",
            code.contains("RoundedCornerShape("),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

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
