package com.paolosantucci.foglietto.shared.recovery

/**
 * Use case: save the current buffer text to the recovery store.
 *
 * ## Trim-guard (FR-07, EC-06)
 *
 * When [text] is empty or whitespace-only after trimming, returns `null`
 * immediately — **zero** repository calls are made (neither [RecoveryRepository.save]
 * nor [RecoveryRepository.trim] is invoked). This guard lives HERE at the
 * use-case boundary, not inside [RecoveryRepository].
 *
 * The RAW (un-trimmed) [text] is passed to [RecoveryRepository.save] — only
 * the empty/non-empty DECISION uses `trim()`.
 *
 * ## Call order (FR-08)
 *
 * When text is non-empty:
 *   1. `repository.save(rawText)` — the literal, un-trimmed text.
 *   2. `repository.trim(10)`       — the keep literal lives HERE, not on the repo.
 *
 * ## Error contract (FR-09, EC-05)
 *
 * If `save` throws, the exception propagates to the caller unchanged.
 * `trim` is **not** called when `save` throws.
 *
 * ## Recovery is always on (FR-10, R-A6)
 *
 * There is no `emergencyRecoveryEnabled` gate, toggle, or setting in this class.
 * Recovery is unconditionally active.
 *
 * ## Return value
 *
 * Returns `null` when nothing was saved (empty/whitespace-only input).
 * Returns the written file path [String] when the save succeeds.
 *
 * Spec refs: §5.1.1, §4.2, FR-07, FR-08, FR-09, FR-10, EC-05, EC-06.
 */
class SaveNoteToRecovery(private val repository: RecoveryRepository) {

    /**
     * Saves [text] to the recovery store, or returns `null` if [text] is
     * empty/whitespace-only.
     *
     * Delegates the raw, un-trimmed [text] to [RecoveryRepository.save], then
     * calls `repository.trim(10)` to cap the store at 10 files. The literal
     * `10` is the use-case boundary — it does not live on [RecoveryRepository].
     *
     * If `save` throws, the exception propagates unchanged and `trim` is never
     * called.
     */
    operator fun invoke(text: String): String? {
        if (text.trim().isEmpty()) return null
        val path = repository.save(text)
        repository.trim(10)
        return path
    }
}
