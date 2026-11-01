package com.paolosantucci.foglietto.shared.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Hand-written fake — records the exact call sequence for assertion.
//
// `internal` visibility: required so the test class (public by default in
// kotlin.test) can access the fake without a private-in-file restriction on
// the JVM test runner.
// ---------------------------------------------------------------------------

/**
 * Hand-written fake [RecoveryRepository] that records each call as a
 * structured string entry in [callLog].
 *
 * Entry format:
 *   `"save:<text>"`  — produced by [save]
 *   `"trim:<keep>"`  — produced by [trim]
 *
 * When [saveError] is non-null, [save] throws it instead of recording.
 *
 * The remaining members ([list], [read], [delete], [deleteAll]) are stubs
 * that satisfy the interface but are never exercised by these use-case tests.
 */
internal class FakeRecoveryRepositoryForUseCase : RecoveryRepository {

    val callLog: MutableList<String> = mutableListOf()

    /** When non-null, [save] throws this instead of recording. */
    var saveError: Throwable? = null

    /** The path string returned by [save] when no error is set. */
    val sentinelPath: String = "sentinel.txt"

    override fun save(text: String): String {
        saveError?.let { throw it }
        callLog.add("save:$text")
        return sentinelPath
    }

    override fun trim(keep: Int) {
        callLog.add("trim:$keep")
    }


    override fun list(): List<RecoveryNote> = emptyList()

    override fun read(path: String): String? = null

    override fun delete(path: String) {}

    override fun deleteAll() {}
}

// ---------------------------------------------------------------------------
// (Drops callSync / _trimSync helper tests: collapsed into one synchronous save.)
// ---------------------------------------------------------------------------

class SaveNoteToRecoveryTest {

    // -----------------------------------------------------------------------
    // Case 1 — Empty string → null, ZERO repository calls (EC-06, FR-07)
    // -----------------------------------------------------------------------

    @Test
    fun given_empty_string_when_invoked_then_returns_null_with_zero_repository_calls() {
        val fakeRepo = FakeRecoveryRepositoryForUseCase()
        val useCase = SaveNoteToRecovery(fakeRepo)

        val result = useCase("")

        assertNull(result, "invoke(\"\") must return null")
        assertTrue(
            fakeRepo.callLog.isEmpty(),
            "Expected zero repository calls, got: ${fakeRepo.callLog}"
        )
    }

    // -----------------------------------------------------------------------
    // Case 2 — Whitespace-only → null, ZERO repository calls (EC-06, FR-07)
    // -----------------------------------------------------------------------

    @Test
    fun given_whitespace_only_text_when_invoked_then_returns_null_with_zero_repository_calls() {
        val fakeRepo = FakeRecoveryRepositoryForUseCase()
        val useCase = SaveNoteToRecovery(fakeRepo)

        val result = useCase("   \n\t ")

        assertNull(result, "invoke(whitespace-only) must return null")
        assertTrue(
            fakeRepo.callLog.isEmpty(),
            "Expected zero repository calls, got: ${fakeRepo.callLog}"
        )
    }

    // -----------------------------------------------------------------------
    // Case 3 — Raw un-trimmed text delegated to save (FR-08)
    // The trim-guard uses text.trim() ONLY to decide empty/non-empty;
    // the RAW (un-trimmed) string is what reaches repository.save.
    // -----------------------------------------------------------------------

    @Test
    fun given_padded_non_empty_text_when_invoked_then_delegates_raw_untrimed_text_to_repository() {
        val fakeRepo = FakeRecoveryRepositoryForUseCase()
        val useCase = SaveNoteToRecovery(fakeRepo)

        useCase(" hi ")

        assertTrue(
            fakeRepo.callLog.contains("save: hi "),
            "repository.save must receive the raw un-trimmed text ' hi ', got: ${fakeRepo.callLog}"
        )
    }

    // -----------------------------------------------------------------------
    // Case 4 — After successful save, trim is called with exactly 10 (FR-08)
    // -----------------------------------------------------------------------

    @Test
    fun given_non_empty_text_when_invoked_then_trim_called_with_10() {
        val fakeRepo = FakeRecoveryRepositoryForUseCase()
        val useCase = SaveNoteToRecovery(fakeRepo)

        useCase("hello")

        assertTrue(
            fakeRepo.callLog.contains("trim:10"),
            "repository.trim must be called with keep=10, got: ${fakeRepo.callLog}"
        )
    }

    // -----------------------------------------------------------------------
    // Case 5 — save called BEFORE trim (strict order, FR-08)
    // -----------------------------------------------------------------------

    @Test
    fun given_non_empty_text_when_invoked_then_save_is_called_strictly_before_trim() {
        val fakeRepo = FakeRecoveryRepositoryForUseCase()
        val useCase = SaveNoteToRecovery(fakeRepo)

        useCase("hello")

        assertEquals(
            listOf("save:hello", "trim:10"),
            fakeRepo.callLog,
            "Call order must be [save:hello, trim:10]"
        )
    }

    // -----------------------------------------------------------------------
    // Case 6 — save throws → exception propagates, trim NOT called (FR-09, EC-05)
    // -----------------------------------------------------------------------

    @Test
    fun given_repository_save_throws_when_invoked_then_exception_propagates_and_trim_not_called() {
        val fakeRepo = FakeRecoveryRepositoryForUseCase()
        val useCase = SaveNoteToRecovery(fakeRepo)
        val expectedError = RuntimeException("disk full")
        fakeRepo.saveError = expectedError

        val thrown = assertFailsWith<RuntimeException>(
            message = "save's exception must propagate unchanged"
        ) {
            useCase("hello")
        }
        assertEquals(expectedError, thrown)
        assertTrue(
            fakeRepo.callLog.isEmpty(),
            "trim must NOT be called when save throws, got: ${fakeRepo.callLog}"
        )
    }
}
