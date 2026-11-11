package com.paolosantucci.foglietto

import android.app.Activity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier B (Robolectric) suite for the process-lifecycle wiring of the composition root — plan
 * TASK-18, spec §7.1 row 735, OQ-18.
 *
 * What is pinned here:
 *
 *  - [onStopSavesAndOnStartResetsThroughTheRegisteredObserver] — EC-09: driving the registered
 *    observer with `ON_STOP` / `ON_START` reaches the container seams (save the raw text, then
 *    empty the buffer and clear the history).
 *  - [theObserverIsRegisteredExactlyOnce] — the registration seam is idempotent: a second attach
 *    neither throws nor adds a second observer, and the rejected lifecycle demonstrably drives
 *    nothing.
 *  - [anActivityTransitionDrivesNeitherSeam] — EC-12/EC-13/EC-24 (FR-14): Activity-level
 *    pause/stop/destroy (exactly what a rotation, a uiMode change or a second visible Activity
 *    produces) never reaches the process seams. This is the behavioural half of the "no
 *    Activity-lifecycle wiring" pin; the structural half is
 *    [theObserverRegistrationLivesOnlyInTheCompositionRoot].
 *  - [theObserverRegistrationLivesOnlyInTheCompositionRoot] and
 *    [theActivityHostOwnsNoSaveOrReset] — source scans: `addObserver`/`ProcessLifecycleOwner` appear
 *    in `FogliettoApplication.kt` only, and `MainActivity` references neither the coordinator nor
 *    either seam (the m8 pin, FR-14).
 *
 * Determinism: the graph is built with an injected direct executor, so every save resolves on the
 * caller thread; no sleeps, no real I/O.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ProcessLifecycleWiringTest {

    @Test
    fun `ON_STOP saves and ON_START resets through the registered observer`() {
        val recovery = FakeRecoveryRepository()
        val container = container(recovery = recovery)
        val lifecycle = LifecycleRegistry(FakeLifecycleOwner())
        assertTrue(ProcessLifecycleRegistration().attach(container, lifecycle))

        // Cold start: ON_START on an already-empty buffer is a no-op (EC-15).
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("", container.editor.value.text)

        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(listOf("draft"), recovery.savedTexts)
        // Backgrounding saves but keeps the buffer in memory until the foreground return.
        assertEquals("draft", container.editor.value.text)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("", container.editor.value.text)
        assertFalse(container.editor.canUndo)
        assertFalse(container.editor.canRedo)
    }

    @Test
    fun `the observer is registered exactly once`() {
        val recovery = FakeRecoveryRepository()
        val container = container(recovery = recovery)
        val registration = ProcessLifecycleRegistration()

        val first = LifecycleRegistry(FakeLifecycleOwner())
        val second = LifecycleRegistry(FakeLifecycleOwner())
        assertTrue("the first attach must register", registration.attach(container, first))
        assertFalse("a second attach must be a no-op", registration.attach(container, second))

        // A registry starts INITIALIZED: bring it to STARTED so the next ON_STOP is a genuine
        // start→stop transition and not an up-move that only dispatches ON_CREATE.
        first.handleLifecycleEvent(Lifecycle.Event.ON_START)
        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        // The rejected lifecycle cannot drive the graph ...
        second.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals("the rejected lifecycle must have no observer", 0, recovery.saveInvocations)

        // ... while the registered one does, so the absence above is not an artefact.
        first.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(listOf("draft"), recovery.savedTexts)
    }

    @Test
    fun `an Activity transition drives neither seam`() {
        // FR-14 / EC-12 / EC-13 / EC-24: a configuration change destroys only the Activity, and a
        // second visible Activity keeps the process STARTED — neither reaches the process seams.
        // The observer is attached to the real process lifecycle, exactly as production does.
        val recovery = FakeRecoveryRepository()
        val container = container(recovery = recovery)
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val registry = processLifecycle as? LifecycleRegistry
        assertNotNull(
            "OQ-18: the process lifecycle must be headlessly drivable, was " +
                processLifecycle.javaClass.name,
            registry,
        )
        val processRegistry = registry!!
        assertTrue(ProcessLifecycleRegistration().attach(container, processLifecycle))
        processRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        first.pause().stop().destroy()
        second.pause().stop().destroy()

        assertEquals("an Activity transition must never save", 0, recovery.saveInvocations)
        assertEquals("draft", container.editor.value.text)
        assertTrue(container.editor.canUndo)

        // Positive control — the same observer, on the same real process owner, does save when the
        // *process* stops. Without this the assertion above could pass because nothing is wired.
        processRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(listOf("draft"), recovery.savedTexts)

        // Leave the process foregrounded (and prove ON_START resets) for the rest of the sandbox.
        processRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("", container.editor.value.text)
    }

    // ── Structural pins (m8 analogue; the dedicated gate lands in TASK-24) ────────────────────

    @Test
    fun `the observer registration lives only in the composition root`() {
        val registrars = mainSources().filter { it.code().contains("addObserver(") }.map { it.name }
        assertEquals(
            "The ProcessLifecycleOwner observer must be registered in exactly one place, and that " +
                "place is the composition root (FR-14).",
            listOf(FOGLIETTO_APPLICATION),
            registrars,
        )

        val processOwners = mainSources().filter { it.code().contains("ProcessLifecycleOwner") }.map { it.name }
        assertEquals(
            "Only the composition root may touch the process lifecycle owner.",
            listOf(FOGLIETTO_APPLICATION),
            processOwners,
        )
    }

    @Test
    fun `the activity host owns no save or reset`() {
        val host = mainSources().single { it.name == "MainActivity.kt" }.code()

        listOf(
            "LifecycleSaveCoordinator",
            "onProcessStop",
            "onProcessStart",
            "Coordinator",
            "createAndroidRecoveryRepository",
            "createAndroidSettingsRepository",
        ).forEach { forbidden ->
            assertFalse(
                "MainActivity must never own save/reset or build the graph — found '$forbidden' " +
                    "in its code (FR-14 structural pin).",
                host.contains(forbidden),
            )
        }

        assertTrue(
            "MainActivity must read the graph off the application instance.",
            host.contains("application as FogliettoApplication"),
        )
    }

    @Test
    fun `no buffer state is persisted to the saved instance state`() {
        // FR-10: the buffer is ephemeral. Nothing — text, caret, selection or history — may be
        // written to savedInstanceState, so process death always yields the empty buffer and the
        // ON_STOP save is the only recovery path. The scan covers the files that own the buffer
        // (composition root + graph); the shell added in TASK-23 owns no persistence either.
        listOf("MainActivity.kt", "FogliettoApplication.kt", "AppContainer.kt").forEach { name ->
            val code = mainSources().single { it.name == name }.code()
            assertFalse(
                "$name must not override onSaveInstanceState — the buffer is ephemeral.",
                code.contains("onSaveInstanceState"),
            )
            assertFalse(
                "$name must not write editor state into a Bundle.",
                code.contains("putString(") || code.contains("putBundle("),
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** Main-source files, resolved from either the module dir or the repo root. */
    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("androidApp/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("No androidApp main source dir from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { files -> assertTrue("main sources must not be empty", files.isNotEmpty()) }
    }

    /** Source with block and line comments removed, so the scan pins code, not prose. */
    private fun File.code(): String =
        readText()
            .replace(BLOCK_COMMENT, " ")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private class FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
    }

    private companion object {
        const val FOGLIETTO_APPLICATION = "FogliettoApplication.kt"
        val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    }
}
