package com.paolosantucci.foglietto.shared.recovery

import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * okio-backed [RecoveryRepository] implementation.
 *
 * Port of Dart `FileRecoveryRepository` — collapsed to ONE synchronous `save` because okio
 * I/O is blocking (the Dart `save`/`saveSync`/`_writeChain`/`callSync`/`_trimSync` machinery
 * is dropped per the assessment drop-list and EC-08/OQ-A).
 *
 * ## Ctor injection (NFR-06)
 *
 * All platform and I/O dependencies are injected:
 *   - [fileSystem]   — the okio `FileSystem` (never hardcoded as `FileSystem.SYSTEM`).
 *   - [recoveryDir]  — the base directory for recovery `.txt` files.
 *   - [now]          — returns the current [RecoveryInstant] (UTC calendar tuple);
 *                      injectable for deterministic tests.
 *
 * ## Filename stem (FR-11, NFR-05)
 *
 * `YYYY-MM-DDTHH-MM-SS-mmmZ` — pure string assembly from the 7 calendar fields; no
 * epoch-millis date math, no kotlinx-datetime. The millisecond component is zero-padded
 * to exactly 3 digits so lexicographic order equals chronological order (FR-12, R-05).
 *
 * ## Collision suffix (FR-13, EC-07)
 *
 * If the stem `.txt` already exists, appends `-1`, `-2`, … before `.txt`. Never overwrites.
 *
 * ## Directory creation (FR-16)
 *
 * `createDirectories(recoveryDir)` is called ONLY in [save]. All read/delete operations
 * no-op (empty/null result, no throw) when the directory is absent — they NEVER create it.
 *
 * ## Read tolerance (FR-18, R-06)
 *
 * `list` is guarded by `fileSystem.exists(recoveryDir)` because okio's `FileSystem.list`
 * throws on an absent directory. Per-file reads tolerate vanished/changed files. Deletes
 * use `mustExist = false`.
 *
 * ## 512-byte lenient head decode (FR-06, NFR-04)
 *
 * `list` reads at most [RecoveryPreview.PREVIEW_READ_LIMIT] bytes from the head of each
 * file via an okio `Buffer` + `readUtf8()`. This matches Dart's `allowMalformed: true`
 * and does not throw when the 512-byte window ends mid-UTF-8 sequence (U+FFFD acceptable).
 *
 * ## Trim ranking (FR-14, R-05, R-A3)
 *
 * [trim] ranks by **lexicographic filename string** (including any `-N` collision suffix),
 * never by `savedAt` / mtime. Because stems are fixed-width and lex-ordered chronologically,
 * this correctly identifies the oldest files. The `keep` parameter has NO default — the
 * literal `10` lives at the [SaveNoteToRecovery] use-case boundary.
 *
 * Spec refs: §5.1.1, §4.2, FR-11..FR-18, NFR-03, NFR-04, NFR-06; assessment R-A3, R-A4.
 */
class FileRecoveryRepository(
    private val fileSystem: FileSystem,
    private val recoveryDir: Path,
    private val now: () -> RecoveryInstant,
) : RecoveryRepository {

    // ══════════════════════════════════════════════════════════════════════
    // RecoveryRepository — write path
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists [text] to the recovery store and returns the written file path as a [String].
     *
     * Creates [recoveryDir] recursively (only this method creates it). Builds the
     * colon-free ms stem from the injected [now] lambda. Appends `-1`/`-2`/… on
     * filename collision. I/O errors propagate UNCHANGED (FR-15, EC-05).
     */
    override fun save(text: String): String {
        fileSystem.createDirectories(recoveryDir)
        val instant = now()
        val stem = buildStem(instant)
        val targetPath = resolveFile(stem)
        fileSystem.write(targetPath) {
            writeUtf8(text)
        }
        return targetPath.toString()
    }

    // ══════════════════════════════════════════════════════════════════════
    // RecoveryRepository — trim
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Retains the newest [keep] files ranked by **lexicographic filename** (NEVER mtime).
     *
     * No-op when the directory is absent or when the file count is at or below [keep].
     * [keep] has NO default — the literal `10` lives at [SaveNoteToRecovery] (FR-14, R-05).
     */
    override fun trim(keep: Int) {
        if (!fileSystem.exists(recoveryDir)) return
        val files = listTxtFiles()
        if (files.size <= keep) return

        // Sort by filename string (incl. -N suffix) ascending — lexicographically oldest first.
        val sorted = files.sortedBy { it.name }
        val toDelete = sorted.take(files.size - keep)
        for (path in toDelete) {
            fileSystem.delete(path, mustExist = false)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // RecoveryRepository — read path
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns notes newest-first by parsed [RecoveryInstant].
     *
     * Guards with `exists` before calling `list` (okio `list` throws on an absent dir).
     * Skips filenames that [RecoveryFilename.parse] cannot parse. Decodes at most
     * [RecoveryPreview.PREVIEW_READ_LIMIT] bytes leniently via `Buffer.readUtf8()`.
     * Returns empty list when the dir is absent — never creates it (FR-16, FR-17).
     */
    override fun list(): List<RecoveryNote> {
        if (!fileSystem.exists(recoveryDir)) return emptyList()

        val notes = mutableListOf<RecoveryNote>()
        for (path in listTxtFiles()) {
            val savedAt = RecoveryFilename.parse(path.name) ?: continue // skip malformed
            val head = readHead(path)
            val preview = RecoveryPreview.truncate(head)
            notes += RecoveryNote(path = path.toString(), savedAt = savedAt, preview = preview)
        }

        // Newest-first: descending by savedAt (compare field-by-field via the natural data class order)
        notes.sortWith(compareByDescending<RecoveryNote> { it.savedAt.year }
            .thenByDescending { it.savedAt.month }
            .thenByDescending { it.savedAt.day }
            .thenByDescending { it.savedAt.hour }
            .thenByDescending { it.savedAt.minute }
            .thenByDescending { it.savedAt.second }
            .thenByDescending { it.savedAt.millis })
        return notes
    }

    /**
     * Returns the full UTF-8 text of the file at [path], or `null` if the file has vanished.
     * Never throws on an absent file (FR-18, R-A4).
     */
    override fun read(path: String): String? {
        val okioPath = path.toPath()
        if (!fileSystem.exists(okioPath)) return null
        return try {
            // okio's FileSystem.read {} helper opens, buffers, and closes the source.
            // Used in place of `source().use {}` because okio.Source extends okio.Closeable,
            // which aliases java.io.Closeable (AutoCloseable) on JVM but NOT kotlin.AutoCloseable
            // on Kotlin/Native — so `.use {}` fails to resolve on the iOS targets.
            fileSystem.read(okioPath) {
                readUtf8()
            }
        } catch (e: IOException) {
            // File may have vanished between the exists check and the read (tolerate)
            null
        }
    }

    /**
     * Deletes the file at [path]. No-op when the file or directory is absent.
     * Uses `mustExist = false` (FR-18, EC-03).
     */
    override fun delete(path: String) {
        if (!fileSystem.exists(recoveryDir)) return
        fileSystem.delete(path.toPath(), mustExist = false)
    }

    /**
     * Deletes every `.txt` file in [recoveryDir]. No-op when the dir is absent (FR-16).
     */
    override fun deleteAll() {
        if (!fileSystem.exists(recoveryDir)) return
        for (path in listTxtFiles()) {
            fileSystem.delete(path, mustExist = false)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Lists all `.txt` files under [recoveryDir] WITHOUT sorting (callers sort).
     * Caller must guard with `fileSystem.exists(recoveryDir)` before calling.
     */
    private fun listTxtFiles(): List<Path> =
        fileSystem.list(recoveryDir).filter { it.name.endsWith(".txt") }

    /**
     * Reads at most [RecoveryPreview.PREVIEW_READ_LIMIT] bytes from [path]'s head.
     *
     * Uses `okio.Buffer.readUtf8()` for lenient UTF-8 decoding — equivalent to Dart's
     * `utf8.decode(bytes, allowMalformed: true)`. Does not throw when the window ends
     * mid-UTF-8 sequence; U+FFFD substitution is acceptable (FR-06, NFR-04).
     *
     * Returns an empty string if the file has vanished since listing (tolerate R-06).
     */
    private fun readHead(path: Path): String {
        return try {
            // okio's FileSystem.read {} helper (Native-safe; see note in read()).
            fileSystem.read(path) {
                val buffer = Buffer()
                read(buffer, RecoveryPreview.PREVIEW_READ_LIMIT.toLong())
                buffer.readUtf8()
            }
        } catch (e: IOException) {
            // File vanished between list() and this read — tolerate (R-06, FR-18)
            ""
        }
    }

    /**
     * Resolves the write target for [stem], appending `-1`, `-2`, … on collision.
     * Never overwrites an existing file (FR-13, EC-07).
     */
    private fun resolveFile(stem: String): Path {
        var candidate = recoveryDir / "$stem.txt"
        var suffix = 0
        while (fileSystem.exists(candidate)) {
            suffix += 1
            candidate = recoveryDir / "$stem-$suffix.txt"
        }
        return candidate
    }

    /**
     * Builds the colon-free ms filename stem from the 7 calendar fields of [instant].
     *
     * Format: `YYYY-MM-DDTHH-MM-SS-mmmZ`
     * Pure string assembly — no epoch-millis date math, no kotlinx-datetime (NFR-05).
     * The millisecond component is zero-padded to 3 digits (e.g. `009` not `9`).
     */
    private fun buildStem(instant: RecoveryInstant): String {
        fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')
        return "${pad(instant.year, 4)}-${pad(instant.month, 2)}-${pad(instant.day, 2)}" +
                "T${pad(instant.hour, 2)}-${pad(instant.minute, 2)}-${pad(instant.second, 2)}" +
                "-${pad(instant.millis, 3)}Z"
    }
}
