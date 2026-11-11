package com.paolosantucci.foglietto.state

import com.paolosantucci.foglietto.shared.recovery.RecoveryInstant
import com.paolosantucci.foglietto.shared.recovery.RecoveryNote
import com.paolosantucci.foglietto.shared.recovery.RecoveryPreview
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — plain JVM, no Robolectric (FR-26..FR-30, EC-02, EC-04, EC-19, EC-29).
 *
 * Pins the Recent-notes state holder:
 *  - `refresh()` re-reads `recovery.list()` on EVERY invocation — never a launch-time cache
 *    (FR-26), and maps each note through the shared preview and the promoted shared
 *    `RecoveryInstant.formatSubtitle()` with no local truncation or date formatting (FR-27);
 *  - an empty repository yields empty rows without crashing (EC-02/FR-28);
 *  - `select(path)` hands a non-null `read` result to the editor's populate path exactly once,
 *    and a null read is a silent no-op that leaves the rows untouched (FR-29, EC-04);
 *  - a surrogate pair at the 80th/81st UTF-16 unit of a preview survives intact (EC-19);
 *  - the surface is restore-only: the fake repository fails loudly on any delete call (FR-30).
 *
 * The editor is injected as the populate callback rather than a concrete `EditorStateHolder`
 * (see the TASK-14 report): the holder only needs "populate with this text", so the contract
 * stays one function wide and the state holder carries no dependency on the editor's internals.
 * The caret-reset/scroll-to-top half of FR-29 lives in `EditorStateHolder.populate` (TASK-13).
 */
class RecoveryListStateHolderTest {

    private class FakeRecoveryRepository : RecoveryRepository {
        val notes: MutableList<RecoveryNote> = mutableListOf()
        val contents: MutableMap<String, String> = mutableMapOf()
        var listCalls: Int = 0
        var readCalls: Int = 0
        var readDelayMillis: Long = 0L

        override fun save(text: String): String = error("save is not part of the list holder")

        override fun list(): List<RecoveryNote> {
            listCalls++
            return notes.toList()
        }

        override fun read(path: String): String? {
            readCalls++
            if (readDelayMillis > 0) Thread.sleep(readDelayMillis)
            return contents[path]
        }

        override fun delete(path: String) {
            error("the recovery list is restore-only: no delete affordance (FR-30)")
        }

        override fun deleteAll() {
            error("the recovery list is restore-only: no delete affordance (FR-30)")
        }

        override fun trim(keep: Int) = Unit
    }

    private val repository = FakeRecoveryRepository()
    private val populated = mutableListOf<String>()

    private fun holderOf(repository: RecoveryRepository = this.repository) =
        RecoveryListStateHolder(repository) { text -> populated += text }

    private fun noteOf(
        path: String,
        preview: String,
        instant: RecoveryInstant,
    ) = RecoveryNote(path = path, preview = preview, savedAt = instant)

    private fun newest() = noteOf(
        path = "/recovery/2026-05-04T09-07-00-000Z.txt",
        preview = RecoveryPreview.truncate("hello world\nsecond line"),
        instant = RecoveryInstant(2026, 5, 4, 9, 7, 0, 0),
    )

    private fun older() = noteOf(
        path = "/recovery/2026-04-02T18-03-00-123Z.txt",
        preview = "older note",
        instant = RecoveryInstant(2026, 4, 2, 18, 3, 0, 123),
    )

    /** Mirrors the `SurrogatePairOffsetTests` oracle: no lone surrogate may survive. */
    private fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isHighSurrogate() ->
                    if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return true
                    else index += 2

                char.isLowSurrogate() -> return true
                else -> index += 1
            }
        }
        return false
    }

    @Test
    fun `refresh maps notes through the shared preview and formatter`() {
        val fresh = newest()
        val stale = older()
        repository.notes += listOf(fresh, stale)

        val holder = holderOf()
        holder.refresh()

        assertEquals(2, holder.rows.size)
        // Newest-first order is the repository's contract — the holder preserves it.
        assertEquals(fresh.path, holder.rows[0].path)
        assertEquals(fresh.preview, holder.rows[0].preview)
        assertEquals("2026-05-04 09:07", holder.rows[0].subtitle)
        assertEquals(fresh.savedAt.formatSubtitle(), holder.rows[0].subtitle)
        assertEquals(stale.path, holder.rows[1].path)
        // Millis and seconds are excluded from the subtitle (minute precision).
        assertEquals("2026-04-02 18:03", holder.rows[1].subtitle)
    }

    @Test
    fun `the row carries the preview verbatim without local truncation`() {
        // 90 UTF-16 units, single line: the shared truncation is the producer's job, so the
        // holder must copy the note's preview untouched rather than re-cutting it here.
        val longPreview = "b".repeat(90)
        repository.notes += noteOf("/recovery/long.txt", longPreview, RecoveryInstant(2026, 1, 2, 3, 4, 0, 0))

        val holder = holderOf()
        holder.refresh()

        assertEquals(longPreview, holder.rows.single().preview)
    }

    @Test
    fun `an empty repository yields an empty list without crashing`() {
        val holder = holderOf()

        holder.refresh()

        assertTrue(holder.rows.isEmpty())
        assertEquals(1, repository.listCalls)
    }

    @Test
    fun `every refresh re-reads the repository`() {
        val holder = holderOf()
        holder.refresh()
        holder.refresh()

        assertEquals(2, repository.listCalls)
    }

    @Test
    fun `a refresh after a new save shows the new note newest first`() {
        val holder = holderOf()
        repository.notes += older()
        holder.refresh()
        assertEquals(listOf(older().path), holder.rows.map { it.path })

        // The note saved after the first refresh must appear without rebuilding the holder.
        repository.notes.add(0, newest())
        holder.refresh()

        assertEquals(listOf(newest().path, older().path), holder.rows.map { it.path })
        assertEquals(2, repository.listCalls)
    }

    @Test
    fun `a non-null read populates the editor with the note text`() {
        val fresh = newest()
        repository.notes += fresh
        repository.contents[fresh.path] = "hello world\nsecond line"
        val holder = holderOf()
        holder.refresh()
        val rowsBefore = holder.rows

        holder.select(fresh.path)

        assertEquals(1, repository.readCalls)
        assertEquals(listOf("hello world\nsecond line"), populated)
        assertEquals(rowsBefore, holder.rows)
    }

    @Test
    fun `a null read is a silent no-op`() {
        val fresh = newest()
        repository.notes += fresh
        // No contents entry: the file vanished between list() and tap.
        val holder = holderOf()
        holder.refresh()
        val rowsBefore = holder.rows

        holder.select(fresh.path)

        assertTrue(populated.isEmpty())
        assertEquals(rowsBefore, holder.rows)
    }

    @Test
    fun `a slow blocking read still completes without crashing`() {
        val fresh = newest()
        repository.notes += fresh
        repository.contents[fresh.path] = "slow"
        repository.readDelayMillis = 25L
        val holder = holderOf()
        holder.refresh()

        holder.select(fresh.path)

        assertEquals(listOf("slow"), populated)
    }

    @Test
    fun `a surrogate pair at the 80-unit boundary survives`() {
        val emoji = "😀" // U+1F600 — a two-unit UTF-16 surrogate pair.
        assertEquals(2, emoji.length)
        val fitting = "a".repeat(78) + emoji // exactly 80 units: kept whole.
        val straddling = "a".repeat(79) + emoji // 81 units: the pair straddles the cut.
        assertEquals(80, fitting.length)
        assertEquals(81, straddling.length)

        repository.notes += listOf(
            noteOf("/recovery/fitting.txt", RecoveryPreview.truncate(fitting), RecoveryInstant(2026, 6, 1, 8, 0, 0, 0)),
            noteOf("/recovery/straddling.txt", RecoveryPreview.truncate(straddling), RecoveryInstant(2026, 6, 1, 7, 0, 0, 0)),
        )

        val holder = holderOf()
        holder.refresh()

        val fittingRow = holder.rows.first { it.path == "/recovery/fitting.txt" }
        assertEquals(RecoveryPreview.truncate(fitting), fittingRow.preview)
        assertTrue(fittingRow.preview.endsWith(emoji))
        assertTrue(fittingRow.preview.length <= RecoveryPreview.MAX_LENGTH)
        assertFalse(hasLoneSurrogate(fittingRow.preview))

        val straddlingRow = holder.rows.first { it.path == "/recovery/straddling.txt" }
        assertEquals(RecoveryPreview.truncate(straddling), straddlingRow.preview)
        assertTrue(straddlingRow.preview.length <= RecoveryPreview.MAX_LENGTH)
        assertFalse(hasLoneSurrogate(straddlingRow.preview))
    }
}
