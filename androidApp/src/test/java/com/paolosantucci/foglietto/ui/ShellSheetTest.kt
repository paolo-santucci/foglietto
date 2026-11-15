package com.paolosantucci.foglietto.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the shell's overlay routing and the ⋮ menu's shape (FR-21, FR-26, FR-34, EC-03).
 *
 * ## What changed
 *
 * This file pinned `OverflowItems` (the two-entry `DropdownMenu`), then a `ModalBottomSheet` menu.
 * Both are gone: the ⋮ is a Material 3 popover anchored to the **top bar's** overflow tile, so the
 * notes are still visible on open (no second tap) but the overlay is a card, not a sheet. What is
 * left to pin is:
 *
 *  - the shell mounts exactly one modal overlay — the About sheet — and nothing else;
 *  - the popover body carries the header, the inline notes, two hairlines and one About row, and no
 *    `ModalBottomSheet` of its own;
 *  - the notes list is height-capped and scrolls, so ten notes cannot make an unbounded card;
 *  - the popover re-fetches once per open (also pinned at its pure seam by `RecoverySheetModelTest`);
 *  - the About row hands over to the About sheet through the shell's routing, not its own;
 *  - the anchor lives in the top bar, and the bottom pill carries no menu plumbing at all;
 *  - no hand-drawn glyph code survives anywhere in the app.
 *
 * The structural half is a source scan because Compose UI interaction tests are Tier C and
 * structurally excluded from this module (OQ-20) — the same idiom the m8 gate and
 * `ProcessLifecycleWiringTest` use for invariants no plain JVM test can observe.
 */
class ShellSheetTest {

    @Test
    fun `the shell routes to exactly one overlay - the About sheet`() {
        assertEquals(
            "The ⋮ menu is a popover now, so the About sheet is the only mounted overlay.",
            listOf(ShellSheet.About),
            ShellSheet.entries.toList(),
        )
    }

    @Test
    fun `the About sheet is reached from inside the popover, not from a second menu level`() {
        val shell = appSources().single { it.contains("fun FogliettoApp(") }
        val popover = appSources().single { it.contains("fun MenuPopoverContent(") }

        assertTrue(
            "The popover body renders the About row.",
            popover.contains("R.string.about"),
        )
        assertTrue(
            "The shell opens the About sheet when the popover's About row is tapped.",
            shell.contains("openSheet = ShellSheet.About"),
        )
        assertTrue(
            "The shell closes the popover first, so the sheet is not stacked over a menu.",
            shell.contains("menuOpen = false"),
        )
    }

    @Test
    fun `the popover body shows the recent notes inline and one About row`() {
        val popover = appSources().single { it.contains("fun MenuPopoverContent(") }

        assertFalse(
            "The menu is a popover card, never a sheet again.",
            popover.contains("ModalBottomSheet("),
        )
        assertTrue(
            "the header is the localized `recent_notes` copy.",
            popover.contains("R.string.recent_notes"),
        )
        assertTrue(
            "the notes are rendered by the shared recovery-list rows — inline, no second tap.",
            popover.contains("RecentNoteRows("),
        )
        assertEquals(
            "two hairlines: one under the header, one above the About row (Keep's card anatomy).",
            2,
            Regex("""HorizontalDivider\(""").findAll(popover).count(),
        )
        assertTrue(
            "the About row uses the outlined Info icon.",
            popover.contains("Icons.Outlined.Info"),
        )
        assertTrue(
            "the About row is a ≥64dp full-width target.",
            popover.contains("heightIn(min = 64.dp)"),
        )
        assertFalse(
            "no lazy list inside the popover: a DropdownMenu content column is measured for " +
                "intrinsics, and a SubcomposeLayout child crashes the menu at measure time.",
            popover.contains("LazyColumn(") || popover.contains("recentNoteItems("),
        )
        assertTrue(
            "the note rows sit in a bounded, non-lazy scroll window so the card fits above the ⋮.",
            popover.contains("heightIn(max = NotesMaxHeight)") &&
                popover.contains("verticalScroll(") &&
                popover.contains("NotesMaxHeight = 360.dp"),
        )
    }

    @Test
    fun `the popover re-fetches the notes once per open`() {
        val popover = appSources().single { it.contains("fun MenuPopoverContent(") }

        assertTrue(
            "A constant-key LaunchedEffect inside the body is the per-open signal (FR-26): the host " +
                "composes the body only while the menu is open.",
            popover.contains("LaunchedEffect(Unit)"),
        )
        assertTrue("the effect drives the model's open().", popover.contains("model.open()"))
    }

    @Test
    fun `the ⋮ is the top bar's anchor, the pill carries no menu, and the shell owns the open state`() {
        val shell = appSources().single { it.contains("fun FogliettoShell(") }
        val bar = appSources().single { it.contains("fun TopActionBar(") }
        val row = appSources().single { it.contains("fun EditorActionRow(") }

        assertTrue(
            "The top bar's ⋮ owns the anchor Box the popover measures against.",
            bar.contains("DropdownMenu("),
        )
        assertTrue(
            "The shell owns the open flag and hands both callbacks to the top bar.",
            shell.contains("menuOpen = menuOpen") &&
                shell.contains("onOpenMenu = {") &&
                shell.contains("menuOpen = true") &&
                shell.contains("onMenuDismiss = { menuOpen = false }"),
        )
        assertTrue(
            "Opening the menu dismisses the IME: with the keyboard up the popup frame shrinks and " +
                "the card flips over its own anchor.",
            shell.contains("keyboardController?.hide()"),
        )
        assertTrue(
            "The shell hands the body to the top bar as a slot.",
            shell.contains("menuContent = {") && shell.contains("MenuPopoverContent("),
        )
        listOf("menuOpen", "onOpenMenu", "onMenuDismiss", "menuContent", "DropdownMenu(").forEach {
            assertFalse(
                "`$it` must not survive in the bottom pill: the ⋮ moved to the top bar.",
                row.contains(it),
            )
        }
    }

    @Test
    fun `the About sheet is the app's only modal sheet`() {
        val sources = appSources()

        assertEquals(
            "Exactly one ModalBottomSheet survives — the About sheet.",
            1,
            sources.sumOf { code -> Regex("""ModalBottomSheet\(""").findAll(code).count() },
        )
    }

    @Test
    fun `the removed menu sheet leaves no trace`() {
        val sources = appSources()

        sources.forEach { code ->
            assertFalse(
                "ShellSheet.Menu is gone: the ⋮ is no longer a sheet destination.",
                code.contains("ShellSheet.Menu"),
            )
            assertFalse("No drawn glyph geometry may survive.", code.contains("Canvas("))
            assertFalse("The old shell glyph enum must be gone.", code.contains("ShellGlyph"))
            assertFalse("The old row glyph enum must be gone.", code.contains("ActionGlyph"))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Production Kotlin sources, resolved from either the module dir or the repo root. */
    private fun appSources(): List<String> = mainSourceFiles().map { it.code() }

    private fun mainSourceFiles(): List<File> {
        val root = listOf(File("src/main/java"), File("androidApp/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("No androidApp main source dir from ${File(".").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
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
