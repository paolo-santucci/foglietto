package com.paolosantucci.foglietto.ui.recovery

import com.paolosantucci.foglietto.state.RecoveryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — plain JVM, no Robolectric (FR-26, FR-27, FR-28, FR-30).
 *
 * Compose UI interaction tests are Tier C and out of scope this wave (OQ-20), so the sheet's
 * contract is pinned here, at the seam [MenuSheet] actually drives:
 *
 *  - constructing the seam performs **zero** fetches — the list is never loaded at launch;
 *  - [RecoverySheetModel.open] invokes the refresh exactly once per open, every open, so a note
 *    saved while the app is running appears the next time the ⋮ sheet is opened (FR-26);
 *  - [RecoverySheetModel.close] is inert (no fetch, no state change);
 *  - an empty row list is the empty state (FR-28) — the sheet renders the localized
 *    `no_recent_notes` copy off this flag and never crashes on `[]`;
 *  - rows are forwarded verbatim and in the holder's newest-first order: no local truncation,
 *    no local date formatting (FR-27);
 *  - the accessibility label carries the preview and the saved-at subtitle through the
 *    `restore_note_label` template's two positional placeholders (FR-27, NFR-04).
 *
 * The refresh counter here is the executable form of FR-26: a caching implementation would
 * report `1` on the second open and fail.
 */
class RecoverySheetModelTest {

    private class CountingRefresh {
        var calls: Int = 0
        fun refresh() {
            calls++
        }
    }

    /**
     * The `restore_note_label` template, spelled the way the resource spells it. Kotlin string
     * templates need the `$` escaped; `StringInventoryTest` separately pins that both locales
     * carry the exact placeholders, so this literal only exercises the fill order.
     */
    private val labelTemplate = "Restore note: %1\$s, saved %2\$s"

    private val newest = RecoveryRow(
        path = "2026-05-04T09-07-00-000Z.txt",
        preview = "third draft",
        subtitle = "2026-05-04 09:07",
    )
    private val older = RecoveryRow(
        path = "2026-05-04T08-55-00-000Z.txt",
        preview = "second draft",
        subtitle = "2026-05-04 08:55",
    )

    @Test
    fun `constructing the seam never fetches - no launch-time load`() {
        val refresh = CountingRefresh()

        RecoverySheetModel(rowSource = { listOf(newest) }, onRefresh = refresh::refresh)

        assertEquals(0, refresh.calls)
    }

    @Test
    fun `every open re-fetches, never a launch-time cache`() {
        val refresh = CountingRefresh()
        val model = RecoverySheetModel(rowSource = { listOf(newest) }, onRefresh = refresh::refresh)

        model.open()
        assertEquals(1, refresh.calls)

        model.close()
        assertEquals("close must be inert", 1, refresh.calls)

        model.open()
        assertEquals(2, refresh.calls)
    }

    @Test
    fun `an empty row list yields the empty state`() {
        assertTrue(RecoverySheetModel.of(rows = emptyList()).isEmpty)
        assertFalse(RecoverySheetModel.of(rows = listOf(newest)).isEmpty)
    }

    @Test
    fun `a note saved while the app runs is visible on the next read`() {
        val savedNotes = mutableListOf<RecoveryRow>()
        val model = RecoverySheetModel(rowSource = { savedNotes.toList() }, onRefresh = {})

        assertTrue(model.isEmpty)

        savedNotes += newest

        assertEquals(listOf(newest), model.rows)
        assertFalse(model.isEmpty)
    }

    @Test
    fun `rows are forwarded verbatim, newest first`() {
        val holderRows = listOf(newest, older)
        val model = RecoverySheetModel(rowSource = { holderRows }, onRefresh = {})

        assertEquals(holderRows, model.rows)
        assertEquals("third draft", model.rows.first().preview)
        assertEquals("2026-05-04 09:07", model.rows.first().subtitle)
    }

    @Test
    fun `the row label carries the preview and the subtitle`() {
        assertEquals(
            "Restore note: hello, saved 2026-05-04 09:07",
            rowLabel(labelTemplate, "hello", "2026-05-04 09:07"),
        )
    }

    @Test
    fun `the row label fills the two placeholders positionally`() {
        assertEquals(
            "Restore note: preview-arg, saved subtitle-arg",
            rowLabel(labelTemplate, "preview-arg", "subtitle-arg"),
        )
    }

    @Test
    fun `a preview containing a percent sign is not re-interpreted`() {
        assertEquals(
            "Restore note: 100% done, saved 2026-05-04 09:07",
            rowLabel(labelTemplate, "100% done", "2026-05-04 09:07"),
        )
    }
}
