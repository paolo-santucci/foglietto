package com.paolosantucci.foglietto.shared.recovery

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files as NioFiles

/**
 * jvmTest oracle for [FileRecoveryRepository].
 *
 * Port of the Dart infra suites:
 *   - test/infrastructure/recovery/file_recovery_repository_test.dart
 *   - test/infrastructure/recovery/file_recovery_repository_sync_test.dart
 *
 * All tests use the NO-new-dependency strategy (no okio-fakefilesystem):
 *   - Normal cases: [FileSystem.SYSTEM] + [java.nio.file.Files.createTempDirectory]
 *   - I/O-error case: [ThrowingOnSinkFileSystem] (a tiny [ForwardingFileSystem] subclass)
 *
 * Plan refs: TASK-04, spec §7.2, assessment R-A3/R-A4.
 */
class FileRecoveryRepositoryTest {

    // ── Temp-dir lifecycle ─────────────────────────────────────────────────

    private lateinit var tempNioDir: java.nio.file.Path
    private lateinit var recoveryDir: Path

    @BeforeTest
    fun setUp() {
        tempNioDir = NioFiles.createTempDirectory("file_recovery_repo_test_")
        // The recovery subdirectory must NOT exist at test start — only save() creates it.
        recoveryDir = tempNioDir.toAbsolutePath().toString().toPath() / "recovery"
    }

    @AfterTest
    fun tearDown() {
        tempNioDir.toFile().deleteRecursively()
    }

    // ── Helper to build a repo with a controllable clock ──────────────────

    private fun makeRepo(now: () -> RecoveryInstant = ::realNow): FileRecoveryRepository =
        FileRecoveryRepository(
            fileSystem = FileSystem.SYSTEM,
            recoveryDir = recoveryDir,
            now = now,
        )

    // ── Helpers for deterministic instants ─────────────────────────────────

    private fun instant(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int, millis: Int,
    ): RecoveryInstant = RecoveryInstant(year, month, day, hour, minute, second, millis)

    private fun realNow(): RecoveryInstant {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return RecoveryInstant(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND),
        )
    }

    // ── Build the expected stem from a RecoveryInstant ─────────────────────

    private fun buildStem(ri: RecoveryInstant): String {
        fun pad(v: Int, w: Int) = v.toString().padStart(w, '0')
        return "${pad(ri.year, 4)}-${pad(ri.month, 2)}-${pad(ri.day, 2)}" +
                "T${pad(ri.hour, 2)}-${pad(ri.minute, 2)}-${pad(ri.second, 2)}" +
                "-${pad(ri.millis, 3)}Z"
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 1 — Write path: directory creation + content round-trip
    // Oracle: FR-11, R-A3
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun save_givenAbsentRecoveryDir_createsDirectoryRecursivelyAndWritesFile() {
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir), "recovery dir must not exist before save")

        val repo = makeRepo()
        val writtenPath = repo.save("hello")

        assertTrue(FileSystem.SYSTEM.exists(recoveryDir), "save() must create the recovery dir recursively")
        assertTrue(FileSystem.SYSTEM.exists(writtenPath.toPath()), "written file must exist")
        assertTrue(writtenPath.startsWith(recoveryDir.toString()), "written file must be under recoveryDir")
    }

    @Test
    fun save_givenHelloText_readBackBytesAreIdentical() {
        val repo = makeRepo()
        val writtenPath = repo.save("hello")
        val content = FileSystem.SYSTEM.source(writtenPath.toPath()).use { source ->
            val buf = okio.Buffer()
            source.read(buf, Long.MAX_VALUE)
            buf.readUtf8()
        }
        assertEquals("hello", content)
    }

    @Test
    fun save_givenMultibyteText_roundTripsByteForByte() {
        // FR-11: UTF-8 encoding integrity incl. 4-byte emoji
        val input = "日本語🗒️"
        val repo = makeRepo()
        val writtenPath = repo.save(input)
        val content = FileSystem.SYSTEM.source(writtenPath.toPath()).use { source ->
            val buf = okio.Buffer()
            source.read(buf, Long.MAX_VALUE)
            buf.readUtf8()
        }
        assertEquals(input, content)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 2 — Filename format
    // Oracle: FR-11, NFR-05, R-A3
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun save_filenameMatchesPattern_noColons_endsTxt() {
        val repo = makeRepo()
        val writtenPath = repo.save("text for filename test")
        val name = writtenPath.toPath().name

        val pattern = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}Z\.txt$""")
        assertTrue(pattern.matches(name), "filename '$name' does not match YYYY-MM-DDTHH-MM-SS-mmmZ.txt pattern")
        assertFalse(name.contains(':'), "colons must be replaced by dashes")
        assertTrue(name.endsWith(".txt"))
    }

    @Test
    fun save_millisecondComponentZeroPaddedTo3Digits() {
        // e.g. millis=9 must produce "009" not "9"
        val fixedNow = instant(2026, 6, 20, 13, 4, 9, 9)
        val repo = makeRepo(now = { fixedNow })
        val writtenPath = repo.save("pad test")
        val name = writtenPath.toPath().name
        // Stem: 2026-06-20T13-04-09-009Z.txt
        assertTrue(name.startsWith("2026-06-20T13-04-09-009Z"), "expected millis zero-padded to 3; got '$name'")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 3 — Lexicographic == chronological ordering
    // Oracle: FR-12, R-05, R-A3
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun save_threeAdvancingInstants_filenamesSortLexicographicallyInChronologicalOrder() {
        val t1 = instant(2026, 1, 1, 0, 0, 0, 1)
        val t2 = instant(2026, 1, 1, 0, 0, 0, 2)
        val t3 = instant(2026, 1, 1, 0, 0, 0, 3)
        val instants = mutableListOf(t1, t2, t3)
        var idx = 0
        val repo = makeRepo(now = { instants[idx++] })

        val p1 = repo.save("first")
        val p2 = repo.save("second")
        val p3 = repo.save("third")

        val n1 = p1.toPath().name
        val n2 = p2.toPath().name
        val n3 = p3.toPath().name
        val sorted = listOf(n1, n2, n3).sorted()
        assertEquals(listOf(n1, n2, n3), sorted, "lexicographic sort must match write order (lex==chron). names: $n1, $n2, $n3")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 4 — Collision suffix (EC-07, FR-13)
    // Ported from Dart Group-7 async-race as a sequential collision test (EC-08/OQ-A).
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun save_collision_twoSameMoment_writesBaseAndDash1() {
        val fixedNow = instant(2026, 6, 14, 12, 0, 0, 0)
        val repo = makeRepo(now = { fixedNow })

        val p1 = repo.save("original content")
        val p2 = repo.save("collision content")

        val stem = buildStem(fixedNow)
        assertTrue(p1.endsWith("$stem.txt"), "first save must be stem.txt; got $p1")
        assertTrue(p2.endsWith("$stem-1.txt"), "collision must produce stem-1.txt; got $p2")
        // original not overwritten
        val c1 = FileSystem.SYSTEM.source(p1.toPath()).use { s -> val b = okio.Buffer(); s.read(b, Long.MAX_VALUE); b.readUtf8() }
        assertEquals("original content", c1, "original file must not be overwritten")
        val c2 = FileSystem.SYSTEM.source(p2.toPath()).use { s -> val b = okio.Buffer(); s.read(b, Long.MAX_VALUE); b.readUtf8() }
        assertEquals("collision content", c2)
    }

    @Test
    fun save_collision_thirdSameMoment_writesDash2() {
        val fixedNow = instant(2026, 6, 14, 12, 0, 0, 0)
        val repo = makeRepo(now = { fixedNow })

        val p1 = repo.save("base")
        val p2 = repo.save("dash1")
        val p3 = repo.save("dash2 content")

        val stem = buildStem(fixedNow)
        assertTrue(p1.endsWith("$stem.txt"), "p1 must be $stem.txt; got $p1")
        assertTrue(p2.endsWith("$stem-1.txt"), "p2 must be $stem-1.txt; got $p2")
        assertTrue(p3.endsWith("$stem-2.txt"), "p3 must be $stem-2.txt; got $p3")
        // All three files exist and none overwritten
        val c3 = FileSystem.SYSTEM.source(p3.toPath()).use { s -> val b = okio.Buffer(); s.read(b, Long.MAX_VALUE); b.readUtf8() }
        assertEquals("dash2 content", c3)
        val c1 = FileSystem.SYSTEM.source(p1.toPath()).use { s -> val b = okio.Buffer(); s.read(b, Long.MAX_VALUE); b.readUtf8() }
        assertEquals("base", c1)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 5 — I/O error propagation unchanged (FR-15, EC-05)
    // Uses ForwardingFileSystem (in base okio artifact) to inject failures.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun save_ioErrorPropagatesUnchangedFromForwardingFileSystem() {
        val throwingFs = ThrowingOnSinkFileSystem(FileSystem.SYSTEM)
        // Pre-create the recovery dir so the failure is on sink(), not createDirectories()
        FileSystem.SYSTEM.createDirectories(recoveryDir)
        val repo = FileRecoveryRepository(
            fileSystem = throwingFs,
            recoveryDir = recoveryDir,
            now = ::realNow,
        )
        assertFailsWith<IOException> { repo.save("will fail") }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 6 — Dir NOT created on read-ops (FR-16, EC-02, R-A4)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun list_onAbsentDir_returnsEmptyAndDoesNotCreateDir() {
        val repo = makeRepo()
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir))
        val result = repo.list()
        assertEquals(emptyList(), result)
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir), "list must not create the dir")
    }

    @Test
    fun read_onAbsentDir_returnsNullAndDoesNotCreateDir() {
        val repo = makeRepo()
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir))
        val result = repo.read("/no/such/path.txt")
        assertNull(result)
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir), "read must not create the dir")
    }

    @Test
    fun delete_onAbsentDir_noopAndDoesNotCreateDir() {
        val repo = makeRepo()
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir))
        repo.delete("/no/such/path.txt") // must not throw
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir), "delete must not create the dir")
    }

    @Test
    fun deleteAll_onAbsentDir_noopAndDoesNotCreateDir() {
        val repo = makeRepo()
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir))
        repo.deleteAll() // must not throw
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir), "deleteAll must not create the dir")
    }

    @Test
    fun trim_onAbsentDir_noopAndDoesNotCreateDir() {
        val repo = makeRepo()
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir))
        repo.trim(10) // must not throw
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir), "trim must not create the dir")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 7 — trim: keep newest by lexicographic filename (FR-14, R-A3, R-05)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun trim_after13Saves_keepsNewest10ByLexicographicName() {
        // Plant 13 files with strictly advancing instants
        val base = instant(2026, 1, 1, 0, 0, 0, 0)
        val instants = (0 until 13).map { i ->
            RecoveryInstant(base.year, base.month, base.day, base.hour, base.minute, base.second, i)
        }.toMutableList()
        var idx = 0
        val repo = makeRepo(now = { instants[idx++] })
        val allPaths = (0 until 13).map { repo.save("content-$it") }

        repo.trim(10)

        val remaining = FileSystem.SYSTEM.list(recoveryDir).map { it.name }.sorted()
        assertEquals(10, remaining.size, "exactly 10 files must remain")
        // The 3 oldest (millis 0, 1, 2) must be deleted; newest 10 (millis 3..12) kept
        val deleted = allPaths.take(3)
        val kept = allPaths.drop(3)
        for (p in deleted) {
            assertFalse(FileSystem.SYSTEM.exists(p.toPath()), "oldest file ${p.toPath().name} must be deleted")
        }
        for (p in kept) {
            assertTrue(FileSystem.SYSTEM.exists(p.toPath()), "newer file ${p.toPath().name} must be kept")
        }
    }

    @Test
    fun trim_after7Saves_noFilesDeleted() {
        val base = instant(2026, 1, 1, 0, 0, 0, 0)
        val instants = (0 until 7).map { i ->
            RecoveryInstant(base.year, base.month, base.day, base.hour, base.minute, base.second, i)
        }.toMutableList()
        var idx = 0
        val repo = makeRepo(now = { instants[idx++] })
        val allPaths = (0 until 7).map { repo.save("content-$it") }

        repo.trim(10)

        for (p in allPaths) {
            assertTrue(FileSystem.SYSTEM.exists(p.toPath()), "all 7 files must survive trim(10) when count ≤ keep")
        }
    }

    @Test
    fun trim_tieBreakByFullFilenameString_lexicographicSuffixOrdering() {
        // R-05 regression: stem-10.txt < stem-2.txt lexicographically (because "1" < "2")
        // The trim must keep the lexicographically LARGEST keep filenames.
        // Arrange: plant exactly 3 files with the same instant (forcing -1, -2 suffixes)
        // so we have: stem.txt, stem-1.txt, stem-2.txt
        // Then trim(2). Lexicographic order: stem-1.txt < stem-2.txt < stem.txt
        // So stem-1.txt is the "oldest" by lex and must be deleted.
        val fixedNow = instant(2026, 3, 1, 0, 0, 0, 0)
        val repo = makeRepo(now = { fixedNow })
        val p0 = repo.save("base")      // stem.txt
        val p1 = repo.save("dash1")     // stem-1.txt
        val p2 = repo.save("dash2")     // stem-2.txt

        repo.trim(2)

        // Lex order: stem-1.txt < stem-2.txt < stem.txt → keep stem-2.txt and stem.txt
        assertFalse(FileSystem.SYSTEM.exists(p1.toPath()), "stem-1.txt (lex smallest) must be deleted")
        assertTrue(FileSystem.SYSTEM.exists(p0.toPath()), "stem.txt (lex largest) must be kept")
        assertTrue(FileSystem.SYSTEM.exists(p2.toPath()), "stem-2.txt (middle lex) must be kept")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 8 — list: read path, ordering, malformed skip (FR-17, R-A4)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun list_withValidAndMalformedFiles_returnsValidNewestFirstMalformedSkipped() {
        // Plant 3 valid-stem files and 1 malformed filename manually
        FileSystem.SYSTEM.createDirectories(recoveryDir)
        val t1 = instant(2026, 1, 1, 0, 0, 0, 1)
        val t2 = instant(2026, 1, 1, 0, 0, 0, 2)
        val t3 = instant(2026, 1, 1, 0, 0, 0, 3)
        fun writeStem(ri: RecoveryInstant, content: String) {
            val path = recoveryDir / "${buildStem(ri)}.txt"
            FileSystem.SYSTEM.write(path) { writeUtf8(content) }
        }
        writeStem(t1, "oldest")
        writeStem(t2, "middle")
        writeStem(t3, "newest")
        FileSystem.SYSTEM.write(recoveryDir / "garbage-filename.txt") { writeUtf8("skip me") }

        val repo = makeRepo()
        val notes = repo.list()

        assertEquals(3, notes.size, "malformed filename must be skipped")
        // Newest-first: t3 > t2 > t1
        assertEquals(t3, notes[0].savedAt)
        assertEquals(t2, notes[1].savedAt)
        assertEquals(t1, notes[2].savedAt)
    }

    @Test
    fun list_previewContentIsTruncatedFirst512Bytes_noNewlines() {
        FileSystem.SYSTEM.createDirectories(recoveryDir)
        val t1 = instant(2026, 1, 1, 0, 0, 0, 1)
        val content = "line one\nline two\nline three"
        FileSystem.SYSTEM.write(recoveryDir / "${buildStem(t1)}.txt") { writeUtf8(content) }

        val repo = makeRepo()
        val notes = repo.list()

        assertEquals(1, notes.size)
        assertFalse(notes[0].preview.contains('\n'), "preview must have no newlines")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 9 — read, delete, deleteAll happy paths
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun read_existingPath_returnsFullContent() {
        val repo = makeRepo()
        val content = "full content for read test"
        val path = repo.save(content)
        val result = repo.read(path)
        assertEquals(content, result)
    }

    @Test
    fun read_vanishedPath_returnsNullNoThrow() {
        val repo = makeRepo()
        val path = repo.save("temporary")
        // Delete the file externally (simulates Files-app mutation)
        FileSystem.SYSTEM.delete(path.toPath())
        val result = repo.read(path)
        assertNull(result, "read on vanished file must return null, not throw")
    }

    @Test
    fun delete_vanishedPath_noThrow() {
        val repo = makeRepo()
        val path = repo.save("will be deleted")
        FileSystem.SYSTEM.delete(path.toPath())
        repo.delete(path) // must not throw (mustExist=false)
    }

    @Test
    fun deleteAll_absentDir_noopNoThrow() {
        val repo = makeRepo()
        assertFalse(FileSystem.SYSTEM.exists(recoveryDir))
        repo.deleteAll() // must not throw
    }

    @Test
    fun deleteAll_populatedDir_removesAllFiles_subsequentListEmpty() {
        val repo = makeRepo()
        val instants = mutableListOf(
            instant(2026, 1, 1, 0, 0, 0, 1),
            instant(2026, 1, 1, 0, 0, 0, 2),
            instant(2026, 1, 1, 0, 0, 0, 3),
        )
        var idx = 0
        val savingRepo = FileRecoveryRepository(FileSystem.SYSTEM, recoveryDir) { instants[idx++] }
        savingRepo.save("a")
        savingRepo.save("b")
        savingRepo.save("c")

        repo.deleteAll()
        assertEquals(emptyList(), repo.list())
    }

    // ══════════════════════════════════════════════════════════════════════
    // Group 10 — [PORTING-ADDITION] 512-byte mid-UTF-8 sequence (FR-06, NFR-04)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun list_512ByteMidUtf8Boundary_returnsNoteNoThrow() {
        // Build a file where the 512-byte boundary falls mid-UTF-8 sequence.
        // Each CJK character (e.g. U+4E2D 中) encodes to 3 bytes in UTF-8.
        // 170 chars = 510 bytes; the 171st char starts at byte 511, so bytes 511-512
        // are the first two bytes of a 3-byte sequence — the 3rd byte goes beyond 512.
        // This exercises okio Buffer.readUtf8() lenient decode (U+FFFD acceptable).
        FileSystem.SYSTEM.createDirectories(recoveryDir)
        val cjkChar = '中'
        // Construct 171 CJK characters so the 512-byte window is at a mid-sequence boundary
        val longContent = cjkChar.toString().repeat(200) // 200 * 3 = 600 bytes — well over 512
        val t1 = instant(2026, 5, 1, 0, 0, 0, 0)
        FileSystem.SYSTEM.write(recoveryDir / "${buildStem(t1)}.txt") {
            writeUtf8(longContent)
        }

        val repo = makeRepo()
        // Must not throw; note must be returned
        val notes = repo.list()
        assertEquals(1, notes.size, "note must be returned despite mid-UTF-8 boundary at byte 512")
        assertNotNull(notes[0].preview)
        // Preview must be a valid String (no exception reached here)
        assertTrue(notes[0].preview.isNotEmpty())
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

// Note: String.toPath() extension is provided by okio.Path.Companion.toPath — already imported above.

/**
 * A [ForwardingFileSystem] that throws [IOException] on any [sink] or [appendingSink] call,
 * used to verify that [FileRecoveryRepository.save] propagates I/O errors unchanged.
 *
 * [ForwardingFileSystem] is in the base `okio` artifact (verified in okio-jvm-3.9.1.jar),
 * so no additional dependency is required.
 */
private class ThrowingOnSinkFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        throw IOException("Simulated write failure on $file")
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        throw IOException("Simulated appendingSink failure on $file")
    }
}
