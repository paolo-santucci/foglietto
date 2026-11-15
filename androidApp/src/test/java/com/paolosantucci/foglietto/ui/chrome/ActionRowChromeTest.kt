package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the bottom pill's contract (FR-22, FR-32, NFR-04, NFR-05, EC-03, EC-27).
 *
 * ## What moved out of here
 *
 * Undo, Redo and the ⋮ used to be this row's right cluster. With the current Google Keep layout
 * they went back to the top bar ([TopActionBar], pinned by `TopActionBarChromeTest`), so the row is
 * now **one pill of the three buffer controls** — Outdent, Indent, Clear — and its enablement seam
 * ([actionRowEnabled]) takes only the buffer flag.
 *
 * ## History — why the chrome seam is still gone
 *
 * This file once pinned `actionRowChrome`, the fraction-driven `(alpha, translationY)` pair the row
 * applied on top of `imePadding()`. That seam *was* the second of two vertical movers: at
 * `imeFraction == 1` it lifted the row a further full row-height above the keyboard (leaving a dead
 * band above the IME), and its mid-flight dip faded a row that rests on the navigation bar while
 * the keyboard opens. Both behaviours were wrong, and re-pinning them to a smaller number would
 * have kept an invented animation alive: the IME inset is now the single owner of the band's travel
 * and the band's opacity is constant, so there is no chrome value left to assert.
 *
 * What remains is the part that can actually regress: which buttons accept input, how that
 * enablement animates under reduced motion, the EC-27 inset rule, and the pill's single-`Surface`
 * shape (a structure no unit test can observe without a Compose UI test, which this module excludes
 * — OQ-20 — so it is pinned against the source, the same way the m8 gate pins `imePadding()`).
 */
class ActionRowChromeTest {

    @Test
    fun `every pill control maps to its own holder flag`() {
        val empty = actionRowEnabled(canClear = false)
        assertTrue("indent is always available.", empty.indent)
        assertTrue("outdent is always available.", empty.outdent)
        assertFalse("an empty buffer cannot be cleared.", empty.clear)

        val populated = actionRowEnabled(canClear = true)
        assertTrue(populated.clear)
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
        // the pill one navigation-bar height too high — the same double-inset class as the
        // double-travel bug this row was fixed for.
        assertTrue(appliesNavigationBarInset(imeVisible = false))
        assertFalse(appliesNavigationBarInset(imeVisible = true))
    }

    @Test
    fun `the band keeps the IME inset and the rule it depends on`() {
        val code = rowSource()

        assertTrue(
            "imePadding() is the band's only vertical mover (EC-27).",
            code.contains("imePadding()"),
        )
        assertTrue(
            "The navigation-bar inset must stay conditional on the IME being hidden (EC-27).",
            code.contains("appliesNavigationBarInset(imeVisible)"),
        )
    }

    @Test
    fun `the band is one centred pill of the three buffer controls`() {
        val code = rowSource()

        listOf("onOutdent", "onIndent", "onClear").forEach { param ->
            assertTrue(
                "EditorActionRow must declare `$param` — the three buffer controls are the pill's " +
                    "whole contract.",
                code.contains("$param: () -> Unit"),
            )
        }

        assertEquals(
            "The pill is one cluster, so exactly one spaced arrangement may exist.",
            1,
            Regex("""horizontalArrangement\s*=\s*Arrangement\.spacedBy\(""").findAll(code).count(),
        )
        assertFalse(
            "The two-cluster layout is gone: no weight spacer may survive in the pill.",
            code.contains("Modifier.weight(1f)"),
        )
        assertTrue(
            "The pill is centred in the band, as Keep draws its floating toolbar.",
            code.contains("horizontalArrangement = Arrangement.Center"),
        )

        listOf("R.string.outdent", "R.string.indent", "R.string.clear_editor").forEach { label ->
            assertTrue("the pill renders `$label`.", code.contains(label))
        }
        listOf("R.string.undo", "R.string.redo", "R.string.open_menu", "R.string.share")
            .forEach { label ->
                assertFalse(
                    "`$label` belongs to the top bar now, never to the pill.",
                    code.contains(label),
                )
            }
    }

    @Test
    fun `the pill is a single elevated surface with fully rounded ends`() {
        val code = rowSource()

        assertTrue(
            "The band's only container is a Surface — the pill itself.",
            code.contains("Surface("),
        )
        assertTrue(
            "The pill must float: it needs its own shadow elevation over the canvas.",
            code.contains("shadowElevation = PillElevation"),
        )
        assertTrue(
            "The pill's ends are fully rounded (a stadium, not a rounded rectangle).",
            code.contains("shape = PillShape"),
        )
        assertTrue(
            "The pill's fill is the raised sheet tone, never the canvas tone.",
            code.contains("MaterialTheme.colorScheme.surfaceContainerLow"),
        )
    }

    @Test
    fun `the pill draws Material icons instead of hand-drawn glyphs`() {
        val code = rowSource()

        listOf(
            "Icons.AutoMirrored.Filled.FormatIndentDecrease",
            "Icons.AutoMirrored.Filled.FormatIndentIncrease",
            "Icons.Filled.Close",
        ).forEach { icon ->
            assertTrue("the pill renders `$icon`.", code.contains(icon))
        }
        assertFalse("No Canvas glyph geometry may survive in the pill.", code.contains("Canvas("))
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
