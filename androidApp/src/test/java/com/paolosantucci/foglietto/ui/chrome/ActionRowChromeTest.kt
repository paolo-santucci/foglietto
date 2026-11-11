package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the action row's contract (FR-22, FR-32, NFR-04, NFR-05, EC-03, EC-26, EC-27).
 *
 * ## What moved here
 *
 * Undo and Redo used to be top-bar actions and the row held three buttons. Both changed with the
 * Keep-style layout: the row now owns **six** controls in two clusters and the top bar owns Share
 * alone, so the enablement seam takes the history flags too ([actionRowEnabled]).
 *
 * ## History — why the chrome seam is gone
 *
 * This file used to pin `actionRowChrome`, the fraction-driven `(alpha, translationY)` pair the row
 * applied on top of `imePadding()`. That seam *was* the second of two vertical movers: at
 * `imeFraction == 1` it lifted the row a further full row-height above the keyboard (leaving a dead
 * band above the IME), and its mid-flight dip faded a row that rests on the navigation bar while
 * the keyboard opens. Both behaviours were wrong, and re-pinning them to a smaller number would
 * have kept an invented animation alive: the IME inset is now the single owner of the row's travel
 * and the row's opacity is constant, so there is no chrome value left to assert.
 *
 * What remains is the part that can actually regress: which buttons accept input, how that
 * enablement animates under reduced motion, the EC-27 inset rule, and the row's two-cluster shape
 * (a structure no unit test can observe without a Compose UI test, which this module excludes —
 * OQ-20 — so it is pinned against the source, the same way the m8 gate pins `imePadding()`).
 */
class ActionRowChromeTest {

    @Test
    fun `every row control maps to its own holder flag`() {
        val empty = actionRowEnabled(canClear = false, canUndo = false, canRedo = false)
        assertTrue("indent is always available.", empty.indent)
        assertTrue("outdent is always available.", empty.outdent)
        assertFalse("an empty buffer cannot be cleared.", empty.clear)
        assertFalse("an empty history cannot be undone.", empty.undo)
        assertFalse("an empty history cannot be redone.", empty.redo)
        assertTrue("the ⋮ is chrome, not a buffer action (EC-03).", empty.menu)

        val populated = actionRowEnabled(canClear = true, canUndo = true, canRedo = true)
        assertTrue(populated.clear)
        assertTrue(populated.undo)
        assertTrue(populated.redo)
        assertTrue("the ⋮ never depends on the buffer (EC-03).", populated.menu)
    }

    @Test
    fun `undo and redo follow their own stacks independently`() {
        // The classic regression: one flag drives both, so Undo lights up Redo.
        val undoOnly = actionRowEnabled(canClear = true, canUndo = true, canRedo = false)
        assertTrue(undoOnly.undo)
        assertFalse(undoOnly.redo)

        val redoOnly = actionRowEnabled(canClear = true, canUndo = false, canRedo = true)
        assertFalse(redoOnly.undo)
        assertTrue(redoOnly.redo)
    }

    @Test
    fun `the enablement spring is taken from the motion spec`() {
        // The enabled-state transition duration is part of the same resolved spec, so a
        // collapsed system animator scale snaps enablement instead of springing it (EC-26).
        assertTrue(resolveMotion(0f).collapsed)
        assertTrue(resolveMotion(1f).enabledSpringMillis > 0)
    }

    @Test
    fun `the navigation-bar inset applies only while the ime is hidden`() {
        // EC-27: the IME inset already covers the navigation-bar strip. Adding both would float
        // the row one navigation-bar height too high — the same double-inset class as the
        // double-travel bug this row was fixed for.
        assertTrue(appliesNavigationBarInset(imeVisible = false))
        assertFalse(appliesNavigationBarInset(imeVisible = true))
    }

    @Test
    fun `the row keeps the IME inset and the bar it depends on`() {
        val code = rowSource()

        assertTrue(
            "imePadding() is the row's only vertical mover (EC-27).",
            code.contains("imePadding()"),
        )
        assertTrue(
            "The navigation-bar inset must stay conditional on the IME being hidden (EC-27).",
            code.contains("appliesNavigationBarInset(imeVisible)"),
        )
    }

    @Test
    fun `the row is two clusters with history and the menu on the right`() {
        val code = rowSource()

        listOf("onOutdent", "onIndent", "onClear", "onUndo", "onRedo", "onOpenMenu").forEach { param ->
            assertTrue(
                "EditorActionRow must declare `$param` — the six Keep-style controls are the row's " +
                    "whole contract.",
                code.contains("$param: () -> Unit"),
            )
        }

        assertEquals(
            "The row must build exactly two clusters, each with its own spaced arrangement.",
            2,
            Regex("""horizontalArrangement\s*=\s*Arrangement\.spacedBy\(""").findAll(code).count(),
        )
        assertTrue(
            "The two clusters are separated by a weight spacer, never by fixed padding.",
            code.contains("Modifier.weight(1f)"),
        )

        listOf(
            "R.string.outdent",
            "R.string.indent",
            "R.string.clear_editor",
            "R.string.undo",
            "R.string.redo",
            "R.string.open_menu",
        ).forEach { label ->
            assertTrue("the row renders `$label`.", code.contains(label))
        }
        assertFalse(
            "Share is the top bar's action, never the row's.",
            code.contains("R.string.share"),
        )
    }

    @Test
    fun `the row draws Material icons instead of hand-drawn glyphs`() {
        val code = rowSource()

        listOf(
            "Icons.AutoMirrored.Filled.FormatIndentDecrease",
            "Icons.AutoMirrored.Filled.FormatIndentIncrease",
            "Icons.Filled.Close",
            "Icons.AutoMirrored.Filled.Undo",
            "Icons.AutoMirrored.Filled.Redo",
            "Icons.Filled.MoreVert",
        ).forEach { icon ->
            assertTrue("the row renders `$icon`.", code.contains(icon))
        }
        assertFalse("No Canvas glyph geometry may survive in the row.", code.contains("Canvas("))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** The row's own source, comment-stripped: the composable declaration, not a call site. */
    private fun rowSource(): String =
        mainSources().single { it.contains("fun EditorActionRow(") }

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
