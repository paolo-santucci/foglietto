package com.paolosantucci.foglietto.shared.recovery

/**
 * Domain port (repository interface) for the recovery persistence boundary.
 *
 * Port of the Dart `RecoveryRepository` abstract interface class.
 *
 * ## API surface (§5.1.1)
 *
 * Six non-suspend members (okio I/O is blocking — the Dart async split collapses
 * to one synchronous save):
 *   - [save]      — writes the buffer text; returns the written file path as a String.
 *   - [list]      — newest-first notes; empty list when the dir is absent.
 *   - [read]      — returns file text or null if the file has vanished.
 *   - [delete]    — no-op if the file or directory is absent.
 *   - [deleteAll] — no-op if the directory is absent.
 *   - [trim]      — keep newest `keep` files by lexicographic filename (NEVER mtime).
 *
 * ## Dropped from the Dart oracle
 *
 *   - `saveSync` — dropped by construction (okio is synchronous; no async split needed).
 *   - `keep` default on `trim` — the literal `10` lives at the use-case boundary
 *     ([SaveNoteToRecovery]), NOT on this interface.
 *
 * ## Error contract
 *
 * okio `IOException` from [save] MUST propagate unchanged (no swallow, no wrap).
 * [list], [read], [delete], [deleteAll], [trim] tolerate an absent directory and
 * vanished/changed files without throwing (FR-16, FR-18, R-A4).
 *
 * ## Domain purity
 *
 * This file imports only types within the `recovery` package. It must never import
 * okio, Flutter, or any infrastructure package.
 *
 * Spec refs: §5.1.1, FR-11..FR-18; assessment R-A3, R-A4, R-A5 (drop-list).
 */
interface RecoveryRepository {

    /**
     * Persists [text] to the recovery store and returns the written file path as a [String].
     *
     * Precondition: `text.trim().isNotEmpty` — enforced by [SaveNoteToRecovery], not here.
     * On okio `IOException`: propagates to the caller unchanged (not swallowed).
     */
    fun save(text: String): String

    /**
     * Returns saved notes NEWEST-FIRST by parsed [RecoveryInstant].
     *
     * When the recovery directory is absent returns an empty list. Never creates the directory.
     * Malformed filenames (not matching the ms grammar) are silently skipped.
     * Tolerates files that have vanished or changed since the directory was listed.
     */
    fun list(): List<RecoveryNote>

    /**
     * Returns the full UTF-8 text of the recovery file at [path].
     *
     * Returns `null` if the file has vanished since listing (Files-app mutability).
     * Never throws on an absent file.
     */
    fun read(path: String): String?

    /**
     * Deletes the recovery file at [path].
     *
     * No-op when the directory or the file is absent. Never creates the directory.
     * Never throws on an absent target.
     */
    fun delete(path: String)

    /**
     * Deletes every recovery `.txt` file.
     *
     * No-op when the directory is absent. Never creates the directory.
     */
    fun deleteAll()

    /**
     * Retains the newest [keep] files ranked by LEXICOGRAPHIC FILENAME, deletes the rest.
     *
     * No-op when the directory is absent or when the file count is at or below [keep].
     * Tie-breaking is by filename, NEVER mtime (FR-14, R-A3).
     *
     * Note: [keep] has NO default value — the literal `10` lives at the use-case
     * boundary ([SaveNoteToRecovery.invoke]), not on this interface.
     */
    fun trim(keep: Int)
}
