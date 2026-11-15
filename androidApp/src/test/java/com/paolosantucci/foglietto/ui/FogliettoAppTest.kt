package com.paolosantucci.foglietto.ui

import androidx.compose.ui.platform.UriHandler
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.about.AboutSheetModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the shell's contract (FR-20, FR-32, EC-03, EC-30).
 *
 * [FogliettoApp] itself is presentation (manual inspection, OQ-20); the decisions it makes —
 * what the Clear notification says, how a dead link degrades, and which actions each bar owns —
 * resolve through these seams, so they are pinned here without a Compose UI test.
 *
 * The last test is a source scan: which actions each band owns is not observable from a plain JVM
 * and Compose UI tests are Tier C, which this module excludes (OQ-20) — so the shell's *delegation*
 * is pinned against its own source, the same idiom the m8 gate uses. The bands' internal order and
 * shapes are pinned by `TopActionBarChromeTest` and `ActionRowChromeTest`.
 */
class FogliettoAppTest {

    @Test
    fun `the clear notification carries the localized message and the Undo action`() {
        assertEquals(R.string.editor_cleared, clearSnackbar.messageRes)
        assertEquals(
            "the action is the same 'Undo' the action row renders (FR-32).",
            R.string.undo,
            clearSnackbar.actionLabelRes,
        )
    }

    @Test
    fun `a link with no handler degrades to a no-op instead of crashing the editor`() {
        val noHandler = object : UriHandler {
            override fun openUri(uri: String) {
                throw IllegalArgumentException("no activity handles $uri")
            }
        }

        // S7: `UriHandler.openUri` throws when no activity claims the scheme; an About-sheet row
        // on a device with no browser must leave the editor standing.
        openLink(noHandler, AboutSheetModel.WEBSITE_URL)
    }

    @Test
    fun `a link is forwarded to the platform handler verbatim`() {
        val opened = mutableListOf<String>()
        val recording = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }

        openLink(recording, AboutSheetModel.ISSUES_URL)

        assertEquals(listOf(AboutSheetModel.ISSUES_URL), opened)
    }

    @Test
    fun `the shell delegates both bands and composes no control of its own`() {
        val shell = appSources().single { it.contains("fun FogliettoApp(") }

        assertTrue(
            "Undo, Redo, Share and the ⋮ are the top bar's band (FR-20).",
            shell.contains("TopActionBar("),
        )
        assertTrue(
            "The shell delegates the whole bottom band to the floating pill.",
            shell.contains("EditorActionRow("),
        )
        assertFalse(
            "The shell must not compose a button directly: the bands own their controls.",
            shell.contains("TonalActionButton(") || shell.contains("GlyphActionButton("),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

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
