package com.paolosantucci.foglietto.shared.editor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural gate for PinchZoom.kt — gate-kt items A, B, C.
 *
 * Gate A: PinchZoom.kt exists in the commonMain editor package.
 * Gate B: PinchZoom.kt contains zero Apple/UIKit/Foundation/SwiftUI import strings.
 * Gate C: PinchZoom.kt is the sole M3-added file under the commonMain editor package —
 *         the only files present are the two M2 files (LineIndent.kt, ListContinuation.kt)
 *         and the new PinchZoom.kt.
 *
 * Adding gate checks here (rather than extending SourceScanGateTest.kt) avoids any risk
 * of touching M2-gated assertions in that file, per plan TASK-01 scope rules.
 *
 * Spec refs: FR-17, NFR-05; plan §4 TASK-01 acceptance criteria (gate-A/B/C).
 */
class PinchZoomGateTest {

    private val moduleRoot: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        if (File(cwd, "build.gradle.kts").exists()) {
            cwd
        } else {
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

    private val editorDir: File get() = File(
        moduleRoot,
        "src/commonMain/kotlin/com/paolosantucci/foglietto/shared/editor"
    )

    private val pinchZoomFile: File get() = File(editorDir, "PinchZoom.kt")

    // -------------------------------------------------------------------------
    // Gate A — PinchZoom.kt exists in the editor package
    // -------------------------------------------------------------------------

    /**
     * Gate A: PinchZoom.kt must exist at the expected commonMain editor path.
     *
     * If this fails, either the file was not created or it was placed in the wrong
     * package directory.
     */
    @Test
    fun gateA_pinchZoomKtExistsInEditorPackage() {
        assertTrue(
            pinchZoomFile.exists(),
            "Gate A FAILED — PinchZoom.kt not found at: ${pinchZoomFile.absolutePath}. " +
                "The file must exist in shared/src/commonMain/kotlin/…/editor/ (NFR-05, TASK-01)."
        )
    }

    // -------------------------------------------------------------------------
    // Gate B — PinchZoom.kt contains zero Apple/UIKit/Foundation/SwiftUI imports
    // -------------------------------------------------------------------------

    /**
     * Gate B: PinchZoom.kt must not import any Apple-platform symbol.
     *
     * Checks for the import strings: UIKit, Foundation, SwiftUI, platform.UIKit,
     * platform.Foundation, platform.darwin, apple (as an import prefix).
     *
     * This enforces NFR-05 commonMain purity — the object must remain platform-neutral
     * so it compiles and tests on JVM without an Xcode toolchain.
     */
    @Test
    fun gateB_pinchZoomKtHasZeroAppleImports() {
        assertTrue(
            pinchZoomFile.exists(),
            "Gate B prereq FAILED — PinchZoom.kt not found; run Gate A first."
        )

        val forbiddenTokens = listOf(
            "import UIKit",
            "import Foundation",
            "import SwiftUI",
            "import platform.UIKit",
            "import platform.Foundation",
            "import platform.darwin",
            "import platform.apple",
            "import apple.",
        )

        val violations = pinchZoomFile.readLines().mapIndexedNotNull { idx, line ->
            val trimmed = line.trim()
            val hit = forbiddenTokens.firstOrNull { token -> trimmed.startsWith(token) }
            if (hit != null) "PinchZoom.kt:${idx + 1}: $line" else null
        }

        if (violations.isNotEmpty()) {
            fail(
                "Gate B FAILED — Apple/UIKit/Foundation/SwiftUI import strings found in " +
                    "PinchZoom.kt (commonMain purity, NFR-05):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // -------------------------------------------------------------------------
    // Gate C — PinchZoom.kt is the sole M3 addition under commonMain editor/
    // -------------------------------------------------------------------------

    /**
     * Gate C: the commonMain editor package must contain exactly three .kt files:
     *   - LineIndent.kt   (M2 pre-existing)
     *   - ListContinuation.kt  (M2 pre-existing)
     *   - PinchZoom.kt    (M3 new — the sole M3 addition, NFR-05)
     *
     * Any additional .kt file under this directory is an untracked M3 addition that
     * violates the "sole M3 addition" constraint.
     */
    @Test
    fun gateC_pinchZoomIsOnlyM3AdditionInEditorPackage() {
        assertTrue(
            editorDir.exists(),
            "Gate C prereq FAILED — commonMain editor directory not found at: ${editorDir.absolutePath}."
        )

        val expectedFiles = setOf("LineIndent.kt", "ListContinuation.kt", "PinchZoom.kt")

        val actualFiles = editorDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".kt") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val unexpected = actualFiles - expectedFiles
        val missing = expectedFiles - actualFiles

        val messages = mutableListOf<String>()
        if (unexpected.isNotEmpty()) {
            messages += "Unexpected files (M3 adds only PinchZoom.kt; NFR-05): $unexpected"
        }
        if (missing.isNotEmpty()) {
            messages += "Missing expected files: $missing"
        }

        if (messages.isNotEmpty()) {
            fail(
                "Gate C FAILED — commonMain editor/ package contents diverge from expected " +
                    "{LineIndent.kt, ListContinuation.kt, PinchZoom.kt}:\n" +
                    messages.joinToString("\n")
            )
        }
    }
}
