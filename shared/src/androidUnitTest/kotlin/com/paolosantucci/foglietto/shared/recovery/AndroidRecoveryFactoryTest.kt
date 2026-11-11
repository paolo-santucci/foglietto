package com.paolosantucci.foglietto.shared.recovery

import com.paolosantucci.foglietto.shared.AndroidContext
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier B (Robolectric) contract tests for [createAndroidRecoveryRepository].
 *
 * Covers the plan's TASK-07 vectors:
 *   - save/read/list round-trip inside `<filesDir>/recovery` (newest first),
 *   - trim-to-ten evicting the lexicographically oldest file,
 *   - UTC calendar-field decomposition of an injected fixed clock (no epoch arithmetic),
 *   - filename grammar parity with iOS-written files (NFR-10),
 *   - app-private internal storage only (NFR-09).
 *
 * The clock seam ([createAndroidRecoveryRepository] with an injected `() -> Date`) lets the
 * filename and ordering assertions be deterministic without any epoch math of their own.
 *
 * The matrix is pinned to sdk 26 + 36 (NFR-12); on this JDK 17 host the 36 leg cannot start
 * ("Android SDK 36 requires Java 21") and `shared/build.gradle.kts` announces the degradation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AndroidRecoveryFactoryTest {

    private val application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AndroidContext.init(application)
        // Robolectric data dirs can be reused across tests in one sdk sandbox — start empty.
        createAndroidRecoveryRepository().deleteAll()
    }

    @After
    fun tearDown() {
        createAndroidRecoveryRepository().deleteAll()
        AndroidContext.resetForTesting()
    }

    /** A clock frozen at the [isoInstant] argument. */
    private fun clockAt(isoInstant: String): () -> Date {
        val fixed = Date.from(Instant.parse(isoInstant))
        return { fixed }
    }

    /** A clock that advances one second per read, starting at [isoInstant]. */
    private fun advancingClock(isoInstant: String): () -> Date {
        val base = Instant.parse(isoInstant)
        var index = 0L
        return { Date.from(base.plusSeconds(index++)) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Round-trip / ordering
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `factory round-trips a note through filesDir recovery`() {
        val repository = createAndroidRecoveryRepository(clockAt("2026-05-04T09:07:12.345Z"))

        val path = repository.save("hello")

        assertTrue(
            path.startsWith(recoveryBaseDir().toString()),
            "save must land under <filesDir>/recovery: $path"
        )
        assertEquals("hello", repository.read(path))
        assertEquals(listOf(path), repository.list().map { it.path })
    }

    @Test
    fun `list returns notes newest first`() {
        val repository = createAndroidRecoveryRepository(advancingClock("2026-05-04T09:07:12.000Z"))

        val older = repository.save("older")
        val newer = repository.save("newer")

        assertEquals(listOf(newer, older), repository.list().map { it.path })
        assertEquals("newer", repository.list().first().preview)
    }

    @Test
    fun `trim to ten evicts the oldest`() {
        val repository = createAndroidRecoveryRepository(advancingClock("2026-05-04T09:07:12.000Z"))

        val paths = (0 until 11).map { repository.save("note-$it") }
        repository.trim(keep = 10)

        val listed = repository.list()
        assertEquals(10, listed.size)
        assertFalse(listed.map { it.path }.contains(paths.first()), "the oldest note must be evicted")
        assertEquals(paths.last(), listed.first().path, "newest note stays first")
        assertNull(repository.read(paths.first()), "the evicted file must be gone")
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTC calendar decomposition (NFR-06)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `now decomposes UTC calendar fields`() {
        val fixed = Date.from(Instant.parse("2026-05-04T09:07:12.345Z"))

        assertEquals(
            RecoveryInstant(year = 2026, month = 5, day = 4, hour = 9, minute = 7, second = 12, millis = 345),
            utcRecoveryInstant(fixed)
        )
    }

    @Test
    fun `the decomposition is UTC even when the host default zone is far from UTC`() {
        val fixed = Date.from(Instant.parse("2026-05-04T09:07:12.345Z"))
        val original = TimeZone.getDefault()
        try {
            // UTC+14: a local-time decomposition would roll over to 23:07 on May 4th.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals(
                RecoveryInstant(2026, 5, 4, 9, 7, 12, 345),
                utcRecoveryInstant(fixed)
            )
            val path = createAndroidRecoveryRepository { fixed }.save("x")
            assertEquals(
                "2026-05-04T09-07-12-345Z.txt",
                path.substringAfterLast('/'),
                "filenames must be timezone-independent"
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Filename grammar parity (NFR-10)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `an android-written filename parses identically to the iOS grammar`() {
        val repository = createAndroidRecoveryRepository(clockAt("2026-05-04T09:07:12.345Z"))

        val fileName = repository.save("parity").substringAfterLast('/')

        val fromAndroid = RecoveryFilename.parse(fileName)
        val fromIos = RecoveryFilename.parse("2026-05-04T09-07-12-345Z.txt")
        assertNotNull(fromAndroid, "the Android-written filename must parse under the shared grammar: $fileName")
        assertNotNull(fromIos)
        assertEquals(RecoveryInstant(2026, 5, 4, 9, 7, 12, 345), fromIos)
        assertEquals(fromIos, fromAndroid, "identical instants must parse to identical tuples")
        assertEquals(fromIos, repository.list().single().savedAt)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal storage only (NFR-09)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `recovery files live in app-private internal storage`() {
        val repository = createAndroidRecoveryRepository(clockAt("2026-05-04T09:07:12.345Z"))

        val path = repository.save("internal")

        assertTrue(
            path.startsWith(application.filesDir.absolutePath),
            "recovery must resolve under filesDir (internal storage): $path"
        )
        val externalRoots = listOfNotNull(
            application.getExternalFilesDir(null)?.absolutePath,
            System.getenv("EXTERNAL_STORAGE"),
        )
        for (root in externalRoots) {
            assertFalse(path.startsWith(root), "recovery must never land on external storage: $path")
        }
    }

    @Test
    fun `the factory source references no external-storage API`() {
        val source = factorySourceFile()
        val code = source.readLines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }

        for (forbidden in listOf("getExternalFilesDir", "getExternalStorage", "getExternalStorageState")) {
            assertFalse(
                code.any { it.contains(forbidden) },
                "NFR-09: $forbidden must not appear in ${source.path}"
            )
        }
    }

    /**
     * Resolves the factory source from either the module dir (Gradle's test working dir)
     * or the repo root, mirroring the existing SourceScanGateTest idiom.
     */
    private fun factorySourceFile(): File {
        val relative = "src/androidMain/kotlin/com/paolosantucci/foglietto/shared/recovery/AndroidRecoveryFactory.kt"
        val candidates = listOf(File(relative), File("shared/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("AndroidRecoveryFactory.kt not found from ${File(".").absolutePath}")
    }
}
