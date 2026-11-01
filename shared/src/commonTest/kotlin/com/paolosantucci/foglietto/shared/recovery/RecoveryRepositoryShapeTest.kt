package com.paolosantucci.foglietto.shared.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Port of test/domain/recovery/recovery_repository_test.dart
// Oracle: Dart RecoveryRepository interface-shape test suite (3 cases)
//
// Test strategy:
//   1. Compile-time shape: FakeRecoveryRepository implements ALL six members
//      (save + list + read + delete + deleteAll + trim(keep: Int)).
//      If the interface is missing any member this file fails to compile.
//   2. Assertion: return types match the §5.1.1 contract.
//   3. NO default on keep: trim(keep: Int) with no default — the caller always
//      supplies the literal 10 at the use-case boundary (SaveNoteToRecovery).
//   4. NO saveSync on the interface.

// ---------------------------------------------------------------------------
// Compile-time shape assertion
// ---------------------------------------------------------------------------

private class FakeRecoveryRepository : RecoveryRepository {
    val calls = mutableListOf<String>()
    var savedPath: String = "fake/path.txt"

    override fun save(text: String): String {
        calls.add("save")
        return savedPath
    }

    override fun list(): List<RecoveryNote> {
        calls.add("list")
        return emptyList()
    }

    override fun read(path: String): String? {
        calls.add("read")
        return null
    }

    override fun delete(path: String) {
        calls.add("delete")
    }

    override fun deleteAll() {
        calls.add("deleteAll")
    }

    // No default on `keep` — the caller always supplies the literal
    override fun trim(keep: Int) {
        calls.add("trim($keep)")
    }
}

class RecoveryRepositoryShapeTest {

    @Test
    fun fakeImplementation_compilesWithAllSixMembers_trimHasNoDefault() {
        // The fact that FakeRecoveryRepository above compiles (with no default on keep)
        // IS the shape assertion. This test exercises it at runtime.
        val repo = FakeRecoveryRepository()

        // save returns String (written file path)
        val path: String = repo.save("hello")
        assertEquals("fake/path.txt", path)

        // list returns List<RecoveryNote>
        val notes: List<RecoveryNote> = repo.list()
        assertEquals(emptyList(), notes)

        // read returns String?
        val text: String? = repo.read("some/path.txt")
        assertNull(text)

        // delete returns Unit
        repo.delete("some/path.txt")

        // deleteAll returns Unit
        repo.deleteAll()

        // trim takes an explicit Int (no default), returns Unit
        repo.trim(10)

        assertEquals(listOf("save", "list", "read", "delete", "deleteAll", "trim(10)"), repo.calls)
    }

    @Test
    fun returnShapes_matchContractSpec() {
        // Verify return shape annotations via type-system:
        // save:String, list:List<RecoveryNote>, read:String?, delete/deleteAll/trim:Unit
        val repo = FakeRecoveryRepository()
        repo.savedPath = "/recovery/2026-06-20T13-04-09-512Z.txt"

        val savePath: String = repo.save("content")
        assertEquals("/recovery/2026-06-20T13-04-09-512Z.txt", savePath)

        val noteList: List<RecoveryNote> = repo.list()
        assertEquals(0, noteList.size)

        val readResult: String? = repo.read("/recovery/2026-06-20T13-04-09-512Z.txt")
        assertNull(readResult)
    }

    @Test
    fun interface_hasNoSaveSyncMethod() {
        // NO saveSync on the interface (dropped by construction — okio I/O is synchronous).
        // The Dart oracle has saveSync as a "Defect-B" sync stub; the KMP port drops it.
        // This test asserts it via the compile-time fake: if saveSync were on the interface,
        // FakeRecoveryRepository above would fail to compile (missing override).
        // Reaching here means the interface has no saveSync.
        val repo: RecoveryRepository = FakeRecoveryRepository()
        // Calling repo.saveSync here would be a compile error — which is the assertion.
        // We just confirm we can call all the VALID methods with no compilation issues.
        repo.save("text")
        repo.list()
        repo.read("path")
        repo.delete("path")
        repo.deleteAll()
        repo.trim(5) // explicit keep, no default
    }
}
