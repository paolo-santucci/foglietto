package com.paolosantucci.foglietto.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the shell's sheet routing and the ⋮ menu's shape (FR-21, FR-26, FR-34, EC-03).
 *
 * ## What changed
 *
 * This file used to pin `OverflowItems`: the two `DropdownMenu` entries ("Recent notes", "About")
 * and the sheet each one opened. That two-level shape is gone — it hid the recoverable notes behind
 * a submenu, which is the defect the Keep-style review reported. The ⋮ now opens **one**
 * `ModalBottomSheet` whose first section *is* the recent-notes list, with a single About row under
 * a hairline divider. So what is left to pin is:
 *
 *  - the shell routes to exactly two sheets, and the ⋮'s destination is [ShellSheet.Menu];
 *  - the menu sheet hosts the notes inline (no second tap) plus exactly one About row;
 *  - the sheet re-fetches once per open (the per-open refresh is also pinned at its pure seam by
 *    `RecoverySheetModelTest`);
 *  - no `DropdownMenu` and no hand-drawn glyph code survives anywhere in the app.
 *
 * The structural half is a source scan because Compose UI interaction tests are Tier C and
 * structurally excluded from this module (OQ-20) — the same idiom the m8 gate and
 * `ProcessLifecycleWiringTest` use for invariants no plain JVM test can observe.
 */
class ShellSheetTest {

    @Test
    fun `the shell routes to exactly the menu and the About sheet`() {
        assertEquals(
            listOf(ShellSheet.Menu, ShellSheet.About),
            ShellSheet.entries.toList(),
        )
    }

    @Test
    fun `the menu is the overflow's only destination`() {
        val shell = appSources().single { it.contains("fun FogliettoApp(") }

        assertTrue(
            "The ⋮ must open the menu sheet directly.",
            shell.contains("onOpenMenu = { openSheet = ShellSheet.Menu }"),
        )
        assertFalse(
            "The ⋮ must never jump straight to About — About lives inside the menu.",
            shell.contains("onOpenMenu = { openSheet = ShellSheet.About }"),
        )
    }

    @Test
    fun `the About sheet is reached from inside the menu, not from a second menu level`() {
        val shell = appSources().single { it.contains("fun FogliettoApp(") }
        val menu = appSources().single { it.contains("fun MenuSheet(") }

        assertTrue(
            "The ⋮ menu hands over to the About sheet.",
            shell.contains("onOpenAbout = { onOpenSheet(ShellSheet.About) }"),
        )
        assertTrue(
            "The menu sheet must route the About row through its host.",
            menu.contains("onOpenAbout"),
        )
    }

    @Test
    fun `the menu sheet shows the recent notes inline and one About row`() {
        val menu = appSources().single { it.contains("fun MenuSheet(") }

        assertTrue("the sheet is a ModalBottomSheet.", menu.contains("ModalBottomSheet("))
        assertTrue(
            "the header is the localized `recent_notes` copy.",
            menu.contains("R.string.recent_notes"),
        )
        assertTrue(
            "the notes are rendered by the shared recovery-list items — inline, no second tap.",
            menu.contains("recentNoteItems("),
        )
        assertEquals(
            "exactly one divider separates the notes from the About row.",
            1,
            Regex("""HorizontalDivider\(""").findAll(menu).count(),
        )
        assertTrue(
            "the About row is labelled by the localized `about` copy.",
            menu.contains("R.string.about"),
        )
        assertTrue(
            "the About row uses the outlined Info icon.",
            menu.contains("Icons.Outlined.Info"),
        )
        assertTrue(
            "the sheet's container is the palette's `surfaceContainerLow`.",
            menu.contains("MaterialTheme.colorScheme.surfaceContainerLow"),
        )
    }

    @Test
    fun `the menu re-fetches the notes once per open`() {
        val menu = appSources().single { it.contains("fun MenuSheet(") }

        assertTrue(
            "A constant-key LaunchedEffect inside the sheet is the per-open signal (FR-26): the " +
                "host composes the sheet only while it is open.",
            menu.contains("LaunchedEffect(Unit)"),
        )
        assertTrue("the effect drives the model's open().", menu.contains("model.open()"))
    }

    @Test
    fun `no dropdown menu and no hand-drawn glyph survives`() {
        val sources = appSources()

        sources.forEach { code ->
            assertFalse(
                "The ⋮ is a bottom sheet; a DropdownMenu is the two-level menu the review rejected.",
                code.contains("DropdownMenu"),
            )
            assertFalse("No drawn glyph geometry may survive.", code.contains("Canvas("))
            assertFalse("The old shell glyph enum must be gone.", code.contains("ShellGlyph"))
            assertFalse("The old row glyph enum must be gone.", code.contains("ActionGlyph"))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

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
