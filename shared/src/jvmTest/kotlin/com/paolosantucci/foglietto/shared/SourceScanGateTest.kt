package com.paolosantucci.foglietto.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-07 source-scan absence gates.
 *
 * Reads the shared/src source tree at test runtime (via Java File I/O rooted at the
 * Gradle module directory, resolved from the JVM working directory) and asserts ABSENCE
 * of forbidden symbols, imports, and file artefacts.
 *
 * All eight gates from the plan section 3 TASK-07 description and section 5 conformance
 * rules are implemented here:
 *
 *   Gate 1 - no Apple/Flutter import in commonMain
 *   Gate 2 - no FileSystem.SYSTEM as a code expression in commonMain logic files
 *   Gate 3 - no .lines() or .lineSequence() anywhere in shared source
 *   Gate 4 - no valueOf / enumValueOf / .byName in the settings package
 *   Gate 5 - no emergencyRecoveryEnabled gate/toggle anywhere in recovery sources
 *   Gate 6 - M1 placeholders (Platform.kt, PlaceholderTest.kt, JvmSmokeTest.kt) do NOT exist
 *   Gate 7 - no saveSync / callSync / _writeChain / _trimSync in recovery sources
 *   Gate 8 - trim(keep: Int) exists, trim(keep: Int = ...) (default) does NOT
 *
 * TDD discipline (plan section 3 TASK-07):
 *   Gate 6 is RED while the three M1 placeholder files exist.
 *   All gates are GREEN once the placeholders are deleted and the dep removed.
 *
 * Scanning strategy:
 *   "Code lines" = lines that are not pure comment lines (not starting with // or *
 *   after stripping leading whitespace). This excludes KDoc block comment bodies and
 *   single-line comments that intentionally reference the forbidden symbols as
 *   documentation of what was dropped.
 *   Gate 6 uses java.io.File.exists() -- file-existence check, not content scan.
 *
 * Spec refs: plan section 3 TASK-07, section 5 conformance rules EC-08/EC-14/EC-15,
 * FR-08/FR-10/FR-22/FR-34.
 */
class SourceScanGateTest {

    // Module root resolution

    /**
     * Resolves the shared/ Gradle module root.
     *
     * Gradle sets the JVM working directory to the module root (shared/) when
     * running :shared:jvmTest. If that directory contains build.gradle.kts,
     * we use it directly. Otherwise we walk up from the working directory to find
     * the project root (contains settings.gradle.kts) and append shared/.
     */
    private val moduleRoot: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        // Gradle test runner sets cwd = module root (shared/)
        if (File(cwd, "build.gradle.kts").exists()) {
            cwd
        } else {
            // Fallback: search up for the project root containing settings.gradle.kts
            var dir: File? = cwd
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").exists()) {
                    val candidate = File(dir, "shared")
                    if (candidate.isDirectory) return@lazy candidate
                }
                dir = dir.parentFile
            }
            error("Cannot locate the shared/ module root from cwd=$cwd")
        }
    }

    private val sharedSrc: File get() = File(moduleRoot, "src")

    private fun allKotlinFiles(root: File): List<File> {
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "SourceScanGateTest.kt" }
            .toList()
    }

    /**
     * Returns only lines that are NOT pure KDoc / block-comment / single-line-comment lines.
     *
     * Excluded patterns (after trimStart):
     *   - starts with "//"    -- single-line comment
     *   - starts with "*"     -- KDoc/block-comment body or end (star-slash)
     *   - starts with "/ *"   -- block-comment open (no space in real code)
     *
     * This is deliberately conservative: a line like `val x = foo // valueOf` is NOT
     * excluded (it starts with code). Only lines whose first non-space character
     * indicates a comment delimiter are stripped.
     */
    private fun codeLines(file: File): List<String> {
        return file.readLines().filter { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("//") &&
                !trimmed.startsWith("*") &&
                !trimmed.startsWith("/*")
        }
    }

    private fun codeLinesWithOrigin(files: List<File>): List<Pair<File, String>> {
        return files.flatMap { file ->
            codeLines(file).map { line -> Pair(file, line) }
        }
    }

    // Gate 1 - No Apple/Flutter import in commonMain

    /**
     * Gate 1: no commonMain Kotlin file imports Foundation, UIKit, platform.-namespaced
     * symbols, flutter, or riverpod.
     *
     * Scoped to commonMain only -- iosMain IS allowed to import platform.Foundation
     * (the RecoveryDir iOS actual does so by design; spec section 5.1.1 and TASK-01 iosMain notes).
     *
     * Scan strategy: check ALL lines (including imports), because import directives are
     * not inside KDoc blocks.
     */
    @Test
    fun gate1_noAppleOrFlutterImportInCommonMain() {
        val commonMain = File(sharedSrc, "commonMain")
        assertTrue(commonMain.exists(), "commonMain directory must exist under shared/src")

        val forbidden = listOf(
            "import Foundation",
            "import UIKit",
            "import platform.",
            "import flutter",
            "import riverpod",
        )

        val violations = allKotlinFiles(commonMain).flatMap { file ->
            file.readLines().mapIndexedNotNull { idx, line ->
                val trimmed = line.trim()
                val hit = forbidden.firstOrNull { trimmed.startsWith(it) }
                if (hit != null) "${file.relativeTo(moduleRoot)}:${idx + 1}: $line" else null
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 1 FAILED -- Apple/Flutter imports found in commonMain:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // Gate 2 - No FileSystem.SYSTEM code expression in commonMain

    /**
     * Gate 2: FileSystem.SYSTEM must NOT appear as a live code expression inside
     * commonMain Kotlin files.
     *
     * It IS allowed in:
     *   - KDoc/comments (excluded by codeLines())
     *   - jvmMain actuals or test code (not in scope of this gate)
     *
     * The gate scans only non-comment lines in commonMain, so references in KDoc
     * ("never hardcoded as FileSystem.SYSTEM") are intentionally ignored.
     */
    @Test
    fun gate2_noFileSystemSystemInCommonMain() {
        val commonMain = File(sharedSrc, "commonMain")
        assertTrue(commonMain.exists(), "commonMain directory must exist under shared/src")

        val violations = codeLinesWithOrigin(allKotlinFiles(commonMain))
            .filter { (_, line) -> line.contains("FileSystem.SYSTEM") }
            .map { (file, line) -> "${file.relativeTo(moduleRoot)}: $line" }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 2 FAILED -- FileSystem.SYSTEM literal found in commonMain code (not a comment):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // Gate 3 - No .lines() or .lineSequence() anywhere in shared source

    /**
     * Gate 3: .lines() and .lineSequence() must NOT appear anywhere in the shared
     * source tree (commonMain, commonTest, jvmMain, jvmTest, iosMain).
     *
     * The spec mandates split("\n") only (NFR-04). Both .lines() and .lineSequence()
     * split on \r\n and \r as well, which diverges from the Dart oracle's split('\n')
     * behavior (no CR-awareness).
     *
     * Scan covers ALL lines (comments are theoretically possible but the pattern is
     * unlikely to appear in KDoc; scanning all lines avoids a false-green risk).
     */
    @Test
    fun gate3_noLinesOrLineSequenceAnywhereInSharedSrc() {
        val violations = allKotlinFiles(sharedSrc).flatMap { file ->
            file.readLines().mapIndexedNotNull { idx, line ->
                if (line.contains(".lines()") || line.contains(".lineSequence()")) {
                    "${file.relativeTo(moduleRoot)}:${idx + 1}: $line"
                } else null
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 3 FAILED -- .lines() or .lineSequence() found in shared source:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // Gate 4 - No valueOf / enumValueOf / .byName in settings package

    /**
     * Gate 4: the settings package (commonMain + commonTest) must not use reflective
     * enum lookups (valueOf, enumValueOf, .byName).
     *
     * Color scheme parsing must use an explicit when (FR-22, EC-01) -- these reflective
     * forms throw on unknown values, breaking the spec's "never throws on load" guarantee.
     *
     * Only non-comment code lines are scanned to avoid false positives from the KDoc
     * documentation strings that explain why these are forbidden.
     */
    @Test
    fun gate4_noValueOfOrEnumValueOfInSettings() {
        val settingsMainDir = File(
            sharedSrc,
            "commonMain/kotlin/com/paolosantucci/foglietto/shared/settings"
        )
        val settingsTestDir = File(
            sharedSrc,
            "commonTest/kotlin/com/paolosantucci/foglietto/shared/settings"
        )

        val forbidden = listOf("valueOf(", "enumValueOf(", ".byName(", ".byName ")

        val dirs = listOf(settingsMainDir, settingsTestDir).filter { it.exists() }
        assertTrue(dirs.isNotEmpty(), "At least one settings directory must exist")

        val violations = codeLinesWithOrigin(dirs.flatMap { allKotlinFiles(it) })
            .filter { (_, line) -> forbidden.any { token -> line.contains(token) } }
            .map { (file, line) -> "${file.relativeTo(moduleRoot)}: $line" }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 4 FAILED -- valueOf/enumValueOf/.byName found in settings package code:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // Gate 5 - No emergencyRecoveryEnabled in recovery sources

    /**
     * Gate 5: emergencyRecoveryEnabled (or any emergency-recovery toggle/gate) must NOT
     * appear in any recovery source or SaveNoteToRecovery.
     *
     * Recovery is always-on (FR-18, FR-10, R-A6): the Dart oracle's gate was dropped
     * in the KMP port. Any re-introduction of this gate would be a behavioral regression.
     *
     * Scoped to commonMain/recovery (which contains SaveNoteToRecovery) and
     * commonTest/recovery. Non-comment code lines only to avoid false positives from
     * the KDoc that documents the dropped symbol.
     */
    @Test
    fun gate5_noEmergencyRecoveryEnabledInRecoverySources() {
        val recoveryMainDir = File(
            sharedSrc,
            "commonMain/kotlin/com/paolosantucci/foglietto/shared/recovery"
        )
        val recoveryTestDir = File(
            sharedSrc,
            "commonTest/kotlin/com/paolosantucci/foglietto/shared/recovery"
        )

        val dirs = listOf(recoveryMainDir, recoveryTestDir).filter { it.exists() }
        assertTrue(dirs.isNotEmpty(), "At least one recovery directory must exist")

        val violations = codeLinesWithOrigin(dirs.flatMap { allKotlinFiles(it) })
            .filter { (_, line) -> line.contains("emergencyRecoveryEnabled") }
            .map { (file, line) -> "${file.relativeTo(moduleRoot)}: $line" }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 5 FAILED -- emergencyRecoveryEnabled found in recovery code (not a comment):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // Gate 6 - M1 placeholder files do NOT exist

    /**
     * Gate 6a: Platform.kt must not exist in commonMain.
     *
     * This file was the M1 shared module placeholder. It must be deleted in TASK-07
     * once the real M2 domain code is fully ported (EC-14).
     *
     * RED until the file is deleted. GREEN after deletion.
     */
    @Test
    fun gate6a_platformKtDoesNotExist() {
        val platformKt = File(
            sharedSrc,
            "commonMain/kotlin/com/paolosantucci/foglietto/shared/Platform.kt"
        )
        assertFalse(
            platformKt.exists(),
            "Gate 6a FAILED -- M1 placeholder Platform.kt still exists at: ${platformKt.absolutePath}. " +
                "Delete this file as part of TASK-07 (EC-14)."
        )
    }

    /**
     * Gate 6b: PlaceholderTest.kt must not exist in commonTest.
     *
     * This file was the M1 commonTest smoke test. It must be deleted in TASK-07 (EC-14).
     *
     * RED until the file is deleted. GREEN after deletion.
     */
    @Test
    fun gate6b_placeholderTestKtDoesNotExist() {
        val placeholderTest = File(
            sharedSrc,
            "commonTest/kotlin/com/paolosantucci/foglietto/shared/PlaceholderTest.kt"
        )
        assertFalse(
            placeholderTest.exists(),
            "Gate 6b FAILED -- M1 placeholder PlaceholderTest.kt still exists at: ${placeholderTest.absolutePath}. " +
                "Delete this file as part of TASK-07 (EC-14)."
        )
    }

    /**
     * Gate 6c: JvmSmokeTest.kt must not exist in jvmTest.
     *
     * This file was the M1 JVM smoke test referencing SharedPlaceholder. It must be
     * deleted in TASK-07 (EC-14).
     *
     * RED until the file is deleted. GREEN after deletion.
     */
    @Test
    fun gate6c_jvmSmokeTestKtDoesNotExist() {
        val jvmSmokeTest = File(
            sharedSrc,
            "jvmTest/kotlin/com/paolosantucci/foglietto/shared/JvmSmokeTest.kt"
        )
        assertFalse(
            jvmSmokeTest.exists(),
            "Gate 6c FAILED -- M1 placeholder JvmSmokeTest.kt still exists at: ${jvmSmokeTest.absolutePath}. " +
                "Delete this file as part of TASK-07 (EC-14)."
        )
    }

    // Gate 7 - No saveSync / callSync / _writeChain / _trimSync in recovery sources

    /**
     * Gate 7: the Dart async-save machinery symbols (saveSync, callSync,
     * _writeChain, _trimSync) must NOT appear as code in any recovery source.
     *
     * These were dropped in the KMP port because okio I/O is blocking -- the Dart
     * async split collapses to one synchronous save (EC-08, FR-11, OQ-A).
     *
     * Scoped to commonMain/recovery + commonTest/recovery. Non-comment lines only
     * (KDoc in RecoveryRepository.kt and FileRecoveryRepository.kt documents what
     * was dropped; those lines are excluded from the scan).
     */
    @Test
    fun gate7_noAsyncSyncSymbolsInRecoverySources() {
        val recoveryMainDir = File(
            sharedSrc,
            "commonMain/kotlin/com/paolosantucci/foglietto/shared/recovery"
        )
        val recoveryTestDir = File(
            sharedSrc,
            "commonTest/kotlin/com/paolosantucci/foglietto/shared/recovery"
        )

        val forbidden = listOf("saveSync", "callSync", "_writeChain", "_trimSync")
        val dirs = listOf(recoveryMainDir, recoveryTestDir).filter { it.exists() }
        assertTrue(dirs.isNotEmpty(), "At least one recovery directory must exist")

        val violations = codeLinesWithOrigin(dirs.flatMap { allKotlinFiles(it) })
            .filter { (_, line) -> forbidden.any { token -> line.contains(token) } }
            .map { (file, line) -> "${file.relativeTo(moduleRoot)}: $line" }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 7 FAILED -- async-save symbols found in recovery code (not a comment):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // Gate 8 - trim(keep: Int) exists; trim(keep: Int = ...) does NOT

    /**
     * Gate 8a: fun trim(keep: Int) must appear at least once in the shared source
     * (confirms the RecoveryRepository interface and FileRecoveryRepository both declare it).
     *
     * This is a positive assertion -- the absence of trim(keep: Int) would mean the
     * interface was accidentally renamed or removed.
     */
    @Test
    fun gate8a_trimWithKeepParamExists() {
        val occurrences = allKotlinFiles(sharedSrc).flatMap { file ->
            file.readLines().filter { line -> line.contains("fun trim(keep: Int)") }
        }
        assertTrue(
            occurrences.isNotEmpty(),
            "Gate 8a FAILED -- `fun trim(keep: Int)` not found anywhere in shared/src. " +
                "RecoveryRepository or FileRecoveryRepository may have been accidentally modified."
        )
    }

    /**
     * Gate 8b: trim(keep: Int = must NOT appear anywhere in the shared source.
     *
     * The literal 10 lives at the SaveNoteToRecovery use-case boundary, NOT on
     * the interface or implementation. A default value on trim would be a contract
     * violation (FR-08, plan section 5 conformance rule 9, EC-15).
     *
     * Scans ALL lines (the pattern trim(keep: Int = would be unusual in a comment
     * and scanning all lines is a stronger guard).
     */
    @Test
    fun gate8b_trimDoesNotHaveDefaultKeepValue() {
        val violations = allKotlinFiles(sharedSrc).flatMap { file ->
            file.readLines().mapIndexedNotNull { idx, line ->
                if (line.contains("trim(keep: Int =") || line.contains("trim(keep : Int =")) {
                    "${file.relativeTo(moduleRoot)}:${idx + 1}: $line"
                } else null
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Gate 8b FAILED -- trim has a default keep value (violates FR-08 / EC-15):\n" +
                    violations.joinToString("\n")
            )
        }
    }
}
