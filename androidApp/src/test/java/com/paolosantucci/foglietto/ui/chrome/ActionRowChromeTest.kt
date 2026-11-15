package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the bottom bar's contract (FR-22, FR-32, NFR-04, NFR-05, EC-03, EC-27).
 *
 * ## Shape
 *
 * The bar is **one pill of five editing controls**: Copy, Paste, Outdent, Indent, Clear, centred.
 * Undo, Redo and the ⋮ are the top bar's rounded-square tiles ([TopActionBar], pinned by
 * `TopActionBarChromeTest`) — that split is the product owner's layout, so this file pins that the
 * history/menu controls are *absent* here and that nothing has crept back in. All five are always
 * visible: the product owner asked for no IME-conditional reveal, so the reveal rule is not part of
 * this contract and must not be re-introduced.
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
 * What remains is the part that can actually regress: which controls accept input, how that
 * enablement animates under reduced motion, the EC-27 inset rule, the five-control order and
 * centring, the absence of the menu/history controls — and the pill's single-`Surface` shape. The
 * structural half is a source scan because a Compose UI test is Tier C and structurally excluded
 * from this module (OQ-20), the same idiom the m8 gate uses for `imePadding()`.
 */
class ActionRowChromeTest {

    @Test
    fun `every bar control maps to a holder flag and nothing else`() {
        val empty = actionRowEnabled(canClear = false)
        assertTrue("outdent is always available.", empty.outdent)
        assertTrue("indent is always available.", empty.indent)
        assertFalse("an empty buffer cannot be cleared.", empty.clear)

        val populated = actionRowEnabled(canClear = true)
        assertTrue(populated.clear)

        // No orphan flag: the history and menu flags used to live here. Undo/Redo are the top bar's
        // and the ⋮ is chrome, so a reappearing field is dead state the bar would then have to feed.
        assertEquals(
            "The bar's enablement contract is exactly its five controls.",
            setOf("copy", "paste", "outdent", "indent", "clear"),
            ActionRowEnabled::class.java.declaredFields
                // The Compose compiler adds a static `$stable` field; only instance state counts.
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `copy and paste are never gated on the buffer or the clipboard`() {
        // EC-03 / iOS parity: copy works on an empty buffer (the share surface is what is gated) and
        // paste is enabled unconditionally — an empty clipboard is a silent no-op at the holder.
        val empty = actionRowEnabled(canClear = false)
        assertTrue("Copy is never gated on the buffer.", empty.copy)
        assertTrue("Paste is never gated on the clipboard.", empty.paste)
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
        // the bar one navigation-bar height too high — the same double-inset class as the
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
    fun `the bar orders copy, paste, outdent, indent, clear and nothing else`() {
        val code = rowSource()

        val positions = listOf(
            "R.string.copy",
            "R.string.paste",
            "R.string.outdent",
            "R.string.indent",
            "R.string.clear_editor",
        ).map { label ->
            val index = code.indexOf(label)
            assertTrue("the bar renders `$label`.", index >= 0)
            index
        }

        assertEquals(
            "The bar must render its five controls left to right: Copy, Paste, Outdent, Indent, " +
                "Clear.",
            positions.sorted(),
            positions,
        )

        listOf(
            "R.string.undo",
            "R.string.redo",
            "R.string.share",
            "R.string.open_menu",
        ).forEach { label ->
            assertFalse(
                "`$label` is the top bar's tile now, never this bar's (product-owner layout).",
                code.contains(label),
            )
        }
    }

    @Test
    fun `the five controls are centred in one row with no slack spacer`() {
        val code = rowSource()

        assertEquals(
            "Five controls, five glyph buttons, no hand-rolled variant.",
            5,
            Regex("""GlyphActionButton\(""").findAll(code).count(),
        )
        assertTrue(
            "The five targets are centred in the pill — there is no second cluster to distribute " +
                "slack between.",
            code.contains("horizontalArrangement = Arrangement.Center"),
        )
        assertEquals(
            "Exactly one arrangement: the bar is one Row inside the pill.",
            1,
            Regex("""horizontalArrangement\s*=""").findAll(code).count(),
        )
        assertFalse(
            "No weight spacer: nothing has to be pushed to an edge any more.",
            code.contains("Modifier.weight("),
        )
    }

    @Test
    fun `the pill is a single elevated surface with fully rounded ends`() {
        val code = rowSource()

        assertEquals(
            "The bar's only container is the pill Surface.",
            1,
            Regex("""Surface\(""").findAll(code).count(),
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
        assertTrue(
            "The pill spans the band and the five targets are centred inside it.",
            code.contains("fillMaxWidth()"),
        )
        assertTrue(
            "Keep's chrome sits 8dp clear of the window edge.",
            code.contains("BandHorizontalPadding = 8.dp"),
        )
    }

    @Test
    fun `the bar carries no menu and no history control`() {
        val code = rowSource()

        listOf(
            "DropdownMenu(",
            "menuOpen",
            "onOpenMenu",
            "onMenuDismiss",
            "menuContent",
            "Icons.Filled.MoreVert",
            "ColumnScope",
            "DpOffset",
        ).forEach { token ->
            assertFalse(
                "`$token` moved to the top bar with the ⋮: the pill owns no popover anchor.",
                code.contains(token),
            )
        }
    }

    @Test
    fun `the bar draws Material icons instead of hand-drawn glyphs`() {
        val code = rowSource()

        listOf(
            "Icons.Filled.ContentCopy",
            "Icons.Filled.ContentPaste",
            "Icons.AutoMirrored.Filled.FormatIndentDecrease",
            "Icons.AutoMirrored.Filled.FormatIndentIncrease",
            "Icons.Filled.Close",
        ).forEach { icon -> assertTrue("the bar renders `$icon`.", code.contains(icon)) }

        assertFalse("No Canvas glyph geometry may survive in the bar.", code.contains("Canvas("))
        assertFalse(
            "The ⋮ is not a sheet any more: no ModalBottomSheet may live in the bar.",
            code.contains("ModalBottomSheet("),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /**
     * The row's own source, comment-stripped and cut before the previews: the composable and its
     * metrics, so a preview's own `Surface` cannot be mistaken for the pill.
     */
    private fun rowSource(): String =
        mainSources().single { it.contains("fun EditorActionRow(") }.substringBefore("@Preview(")

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
