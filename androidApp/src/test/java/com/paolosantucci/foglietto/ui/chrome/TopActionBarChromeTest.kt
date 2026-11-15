package com.paolosantucci.foglietto.ui.chrome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the top bar's contract (FR-20, FR-33, EC-03, NFR-04).
 *
 * ## What this bar is
 *
 * The product owner's directive put **Undo, Redo, Share and the ⋮** in the upper-right corner, each
 * in its own rounded-square tonal tile — Google Keep's note-editor toolbar. The editing actions
 * (Copy, Paste, Outdent, Indent, Clear) are the bottom pill's ([EditorActionRow]), so neither band
 * duplicates the other and this bar has an order to pin: Undo · Redo · Share · ⋮.
 *
 * What else can silently regress here, and is therefore pinned:
 *
 *  - each tile follows its own flag (Undo → `canUndo`, Redo → `canRedo`, Share → `canShare`) and
 *    the ⋮ is always enabled (EC-03);
 *  - the tile's container is *not* restyled at this call site: shape, colours, glyph size and the
 *    48dp target belong to [TonalActionButton];
 *  - the ⋮ is the popover anchor, and the card keeps its styling (FR-21);
 *  - the shell still re-reads the shared predicate at click time (EC-30), which is a shell
 *    obligation, not this row's — `FogliettoAppTest` pins that half.
 *
 * The structural half is a source scan because a Compose UI test is Tier C and structurally
 * excluded from this module (OQ-20), the same idiom the m8 gate uses.
 */
class TopActionBarChromeTest {

    @Test
    fun `the bar is one right-aligned row of four tiles in keep order`() {
        val code = barSource()

        listOf(
            "R.string.undo",
            "R.string.redo",
            "R.string.share",
            "R.string.open_menu",
            "Icons.AutoMirrored.Filled.Undo",
            "Icons.AutoMirrored.Filled.Redo",
            "Icons.Filled.Share",
            "Icons.Filled.MoreVert",
        ).forEach { token -> assertTrue("the bar renders `$token`.", code.contains(token)) }

        val positions = listOf(
            "R.string.undo",
            "R.string.redo",
            "R.string.share",
            "R.string.open_menu",
        ).map { code.indexOf(it) }

        assertEquals(
            "The cluster reads left to right as Undo, Redo, Share, ⋮ (the product owner's order).",
            positions.sorted(),
            positions,
        )

        assertEquals(
            "Four tiles, no more: one TonalActionButton per action.",
            4,
            Regex("""TonalActionButton\(""").findAll(code).count(),
        )
        assertEquals("One row, no second cluster.", 1, Regex("""Row\(""").findAll(code).count())

        // Geometry: four 48dp targets + three 8dp gaps + 8dp of end clearance = 224dp.
        assertTrue(
            "The cluster keeps Keep's 8dp gap between tiles.",
            code.contains("horizontalArrangement = Arrangement.spacedBy(TileSpacing)") &&
                code.contains("TileSpacing = 8.dp"),
        )
        assertTrue(
            "The cluster keeps Keep's 8dp clearance from the window edge.",
            code.contains("padding(end = EndPadding)") && code.contains("EndPadding = 8.dp"),
        )
        assertTrue(
            "The tiles centre themselves vertically in the compact band.",
            code.contains("verticalAlignment = Alignment.CenterVertically"),
        )
    }

    @Test
    fun `each tile follows its own flag and the menu is always available`() {
        val code = barSource()

        // The classic regression: one flag drives two tiles, so Undo lights up Redo. Pin each tile's
        // own `enabled` expression inside its own slice of the source.
        val undoTile = code.substringAfter("labelRes = R.string.undo")
            .substringBefore("labelRes = R.string.redo")
        assertTrue("Undo is gated on `canUndo` alone.", undoTile.contains("enabled = canUndo"))

        val redoTile = code.substringAfter("labelRes = R.string.redo")
            .substringBefore("labelRes = R.string.share")
        assertTrue("Redo is gated on `canRedo` alone.", redoTile.contains("enabled = canRedo"))

        val shareTile = code.substringAfter("labelRes = R.string.share")
            .substringBefore("labelRes = R.string.open_menu")
        assertTrue("Share is gated on the shared predicate.", shareTile.contains("enabled = canShare"))

        val menuTile = code.substringAfter("labelRes = R.string.open_menu")
        assertTrue(
            "The ⋮ is chrome, not a buffer action: it never depends on the buffer (EC-03).",
            menuTile.contains("enabled = true"),
        )
    }

    @Test
    fun `the bar delegates the tile to the shared rounded-square component`() {
        val code = barSource()
        val tile = tileSource()

        assertEquals(
            "The bar declares exactly one shape — the popover card's. The tile's shape is the " +
                "shared component's, so it cannot drift per call site.",
            1,
            Regex("""RoundedCornerShape\(""").findAll(code).count(),
        )
        assertTrue(
            "…and that one shape is the card's 28dp corner.",
            code.contains("PopoverShape = RoundedCornerShape(28.dp)"),
        )
        assertTrue(
            "The tile is the Material 3 filled-tonal icon button, restyled — not a forked Box.",
            tile.contains("FilledTonalIconButton("),
        )
        assertTrue(
            "The restyle is the rounded square: 14dp corners on M3's 40dp box (Keep's proportion).",
            tile.contains("RoundedCornerShape(14.dp)") && tile.contains("shape = TileShape"),
        )
        assertTrue(
            "The tile's fill and ink are the tonal pair, resolved from the scheme.",
            tile.contains("scheme.secondaryContainer") && tile.contains("scheme.onSecondaryContainer"),
        )
        assertTrue(
            "The glyph is the 24dp Material icon scale.",
            tile.contains("TileGlyphSize = 24.dp"),
        )
        assertTrue(
            "The pill's glyph keeps the 48dp touch target (NFR-04).",
            tile.contains("TouchTargetSize = 48.dp"),
        )
        assertFalse(
            "The 40dp visual must never be inflated to 48dp by an outer size(): the target is the " +
                "component's minimumInteractiveComponentSize(), the tile stays 40dp.",
            tile.contains("Modifier.size(48.dp)"),
        )
        assertTrue(
            "The glyph is sized independently of the host's 40dp minimum constraint.",
            tile.contains("Modifier.requiredSize(size)"),
        )
    }

    @Test
    fun `the menu anchors to the overflow tile and opens downward`() {
        val code = barSource()

        assertTrue(
            "The ⋮ is the menu's anchor: Material 3's DropdownMenu handles outside-tap and back.",
            code.contains("DropdownMenu("),
        )
        assertTrue("the menu's open state is the host's.", code.contains("expanded = menuOpen"))
        assertTrue(
            "the menu's dismissal is the host's.",
            code.contains("onDismissRequest = onMenuDismiss"),
        )
        assertTrue(
            "the anchor is the ⋮ tile itself: the Box wraps it and the popup wraps the same Box.",
            code.contains("Box {") && code.contains("onClick = onOpenMenu"),
        )
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
            code.contains("width(PopoverWidth)") && code.contains("PopoverWidth = 280.dp"),
        )
        assertTrue(
            "8dp between the ⋮ tile and the card, on the default (downward) placement.",
            code.contains("DpOffset(x = 0.dp, y = PopoverGap)") && code.contains("PopoverGap = 8.dp"),
        )
        assertTrue(
            "the menu body is the host's slot, composed only while the menu is open.",
            code.contains("menuContent: @Composable ColumnScope.() -> Unit"),
        )
    }

    @Test
    fun `the editing actions never come back to the top bar`() {
        val code = barSource()

        listOf(
            "R.string.copy",
            "R.string.paste",
            "R.string.outdent",
            "R.string.indent",
            "R.string.clear_editor",
        ).forEach { label ->
            assertFalse(
                "`$label` belongs to the bottom pill now, never to the top bar.",
                code.contains(label),
            )
        }
        assertFalse("No Canvas glyph geometry may survive in the bar.", code.contains("Canvas("))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The bar's own source, comment-stripped: the composable declaration, cut before previews. */
    private fun barSource(): String =
        mainSources().single { it.contains("fun TopActionBar(") }.substringBefore("@Preview(")

    /** The shared tile's source: the component both bands' controls come from. */
    private fun tileSource(): String =
        mainSources().single { it.contains("fun TonalActionButton(") }.substringBefore("@Preview(")

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
