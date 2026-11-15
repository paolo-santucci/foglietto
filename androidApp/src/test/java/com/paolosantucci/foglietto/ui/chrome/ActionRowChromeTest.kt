package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the bottom bar's contract (FR-22, FR-32, NFR-04, NFR-05, EC-03, EC-27).
 *
 * ## Shape
 *
 * The bar is **one pill of eight controls in two clusters**: Copy, Paste, Outdent, Indent, Clear on
 * the left; Undo, Redo and the ⋮ on the right — Undo/Redo/⋮ arrived here when the top bar was
 * reduced to Share ([TopActionBar], pinned by `TopActionBarChromeTest`). All eight are always
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
 * enablement animates under reduced motion, the EC-27 inset rule, the eight-control order, the ⋮
 * popover's anchoring and styling — and the pill's single-`Surface` shape. The structural half is a
 * source scan because a Compose UI test is Tier C and structurally excluded from this module
 * (OQ-20), the same idiom the m8 gate uses for `imePadding()`.
 */
class ActionRowChromeTest {

    @Test
    fun `every bar control maps to its own holder flag`() {
        val empty = actionRowEnabled(canClear = false, canUndo = false, canRedo = false)
        assertTrue("outdent is always available.", empty.outdent)
        assertTrue("indent is always available.", empty.indent)
        assertFalse("an empty buffer cannot be cleared.", empty.clear)
        assertFalse("an empty history cannot be undone.", empty.undo)
        assertFalse("an empty history cannot be redone.", empty.redo)

        val populated = actionRowEnabled(canClear = true, canUndo = true, canRedo = true)
        assertTrue(populated.clear)
        assertTrue(populated.undo)
        assertTrue(populated.redo)
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
    fun `copy and paste are never gated and the menu never depends on the buffer`() {
        // EC-03 / iOS parity: copy works on an empty buffer (the share surface is what is gated) and
        // paste is enabled unconditionally — an empty clipboard is a silent no-op at the holder.
        val empty = actionRowEnabled(canClear = false, canUndo = false, canRedo = false)
        assertTrue("Copy is never gated on the buffer.", empty.copy)
        assertTrue("Paste is never gated on the clipboard.", empty.paste)
        assertTrue("The ⋮ is chrome, not a buffer action.", empty.menu)
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
    fun `the bar orders copy, paste, outdent, indent, clear then undo, redo and the menu`() {
        val code = rowSource()

        val positions = listOf(
            "R.string.copy",
            "R.string.paste",
            "R.string.outdent",
            "R.string.indent",
            "R.string.clear_editor",
            "R.string.undo",
            "R.string.redo",
            "R.string.open_menu",
        ).map { label ->
            val index = code.indexOf(label)
            assertTrue("the bar renders `$label`.", index >= 0)
            index
        }

        assertEquals(
            "The bar must render its eight controls left to right: Copy, Paste, Outdent, Indent, " +
                "Clear, Undo, Redo, Menu (the iOS order, with Undo/Redo/⋮ moved here from the top bar).",
            positions.sorted(),
            positions,
        )

        assertFalse(
            "Share belongs to the top bar, never to this one.",
            code.contains("R.string.share"),
        )
    }

    @Test
    fun `the two clusters are separated by the slack, not by a second row`() {
        val code = rowSource()

        assertTrue(
            "One weight spacer takes the slack between the editing and history clusters.",
            code.contains("Modifier.weight(1f)"),
        )
        assertEquals(
            "The bar is one row inside the pill: exactly one spaced arrangement.",
            1,
            Regex("""horizontalArrangement\s*=\s*Arrangement\.spacedBy\(""").findAll(code).count(),
        )
        assertTrue(
            "The 48dp targets sit directly next to each other — ButtonSpacing is 0.",
            code.contains("ButtonSpacing = 0.dp"),
        )
        assertEquals(
            "Eight controls, eight glyph buttons, no hand-rolled variant.",
            8,
            Regex("""GlyphActionButton\(""").findAll(rowSource()).count(),
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
            "The pill spans the band, which is what lets the weight spacer distribute the slack.",
            code.contains("fillMaxWidth()"),
        )
    }

    @Test
    fun `the menu button anchors the Material 3 popover card`() {
        val code = rowSource()

        assertTrue(
            "The ⋮ is the menu's anchor: Material 3's DropdownMenu handles outside-tap and back.",
            code.contains("DropdownMenu("),
        )
        assertTrue("the menu's open state is the host's.", code.contains("expanded = menuOpen"))
        assertTrue("the menu's dismissal is the host's.", code.contains("onDismissRequest = onMenuDismiss"))
        assertTrue(
            "the card is Keep's rounded overflow card: 28dp corners.",
            code.contains("shape = PopoverShape") && code.contains("RoundedCornerShape(28.dp)"),
        )
        assertTrue(
            "the card is the raised sheet tone, flat, with one shadow step.",
            code.contains("containerColor = MaterialTheme.colorScheme.surfaceContainerLow") &&
                code.contains("tonalElevation = 0.dp") &&
                code.contains("shadowElevation = PopoverElevation"),
        )
        assertTrue(
            "the card is capped at 320dp, never wider than the window.",
            code.contains("widthIn(max = PopoverWidth)") && code.contains("PopoverWidth = 320.dp"),
        )
        assertTrue(
            "8dp between the ⋮ glyph and the card.",
            code.contains("DpOffset(x = 0.dp, y = PopoverGap)") && code.contains("PopoverGap = 8.dp"),
        )
        assertTrue(
            "the menu body is the host's slot, composed only while the menu is open.",
            code.contains("menuContent: @Composable ColumnScope.() -> Unit"),
        )
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
            "Icons.AutoMirrored.Filled.Undo",
            "Icons.AutoMirrored.Filled.Redo",
            "Icons.Filled.MoreVert",
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
