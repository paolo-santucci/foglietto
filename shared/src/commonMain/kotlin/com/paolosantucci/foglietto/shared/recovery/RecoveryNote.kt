package com.paolosantucci.foglietto.shared.recovery

/**
 * Immutable value entity representing a single saved recovery note.
 *
 * Port of Dart `RecoveryNote` (@immutable class with value equality).
 * Kotlin `data class` provides structural equality and `hashCode` automatically.
 *
 * ## Documented invariants (producer-guaranteed, NOT constructor-enforced)
 *
 *   - `preview.length <= 80` — [RecoveryPreview.truncate] guarantees this.
 *   - `preview` contains no `'\n'` — collapsed by [RecoveryPreview.truncate].
 *   - `savedAt` is derived solely from the filename, NEVER from filesystem mtime.
 *
 * ## Design note on `path`
 *
 * The file path is carried as a plain [String] (the absolute path string) rather
 * than an `okio.Path`. This keeps [RecoveryNote] in the pure domain layer with
 * no okio import — okio's `Path` type appears only on the `recoveryBaseDir()`
 * expect/actual seam and inside `FileRecoveryRepository` (TASK-04).
 *
 * Spec refs: §5.1.1, FR-17; assessment R-A1, NFR-05.
 *
 * @property path      Absolute file path — stable identity for delete/read operations.
 * @property savedAt   UTC timestamp parsed from the filename (7-field [RecoveryInstant]).
 *                     Never derived from filesystem mtime.
 * @property preview   Single-line preview: at most 80 UTF-16 code units, no newlines.
 */
data class RecoveryNote(
    val path: String,
    val savedAt: RecoveryInstant,
    val preview: String,
)
