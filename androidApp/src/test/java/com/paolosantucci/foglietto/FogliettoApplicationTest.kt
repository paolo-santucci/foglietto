package com.paolosantucci.foglietto

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paolosantucci.foglietto.shared.AndroidContext
import com.paolosantucci.foglietto.shared.settings.AppColorScheme
import com.paolosantucci.foglietto.shared.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tier B (Robolectric) contract suite for [FogliettoApplication] — the single composition root
 * (plan TASK-18, spec §4.3 cold start, §7.1 row 734).
 *
 * Robolectric instantiates the real application class from the manifest (`@Config(application =
 * FogliettoApplication::class)`), so `onCreate` runs the production wiring against a real
 * `Context` — including the `ProcessLifecycleOwner` registration, which is what makes the
 * "registered exactly once" assertion meaningful here (OQ-18).
 *
 * Pins:
 *  - [onCreateBuildsTheProcessGraph] — the graph exists and starts empty (FR-10 cold start).
 *  - [onCreateInitialisesTheSharedAndroidContextFirst] — FR-04/EC-06 at the real call site: the
 *    process context is the application, so `filesDir`/`SharedPreferences` resolved.
 *  - [theProcessObserverIsRegisteredExactlyOnce] — two attach attempts, one registration.
 *  - [theGraphIsWiredWithTheRealSharedFactories] — FR-06: the production repositories actually
 *    round-trip through the app's private storage (no okio / multiplatform-settings type crosses
 *    the graph).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = FogliettoApplication::class)
class FogliettoApplicationTest {

    private val application: FogliettoApplication
        get() = RuntimeEnvironment.getApplication() as FogliettoApplication

    @After
    fun cleanUpGraphState() {
        // Process-scoped stores are reused across test methods inside one sdk sandbox, so the real
        // factories' output is returned to its empty state (no destructive API on the graph).
        runCatching { application.container.recovery.trim(keep = 0) }
        runCatching { application.container.settings.save(AppSettings()) }
    }

    @Test
    fun `onCreateBuildsTheProcessGraph`() {
        val container = application.container

        assertEquals("", container.editor.value.text)
        assertFalse(container.editor.canUndo)
        assertFalse(container.editor.canRedo)
        assertEquals("the recent-notes sheet starts empty", 0, container.recoveryList.rows.size)
        assertNotNull("the share seam must be part of the graph", container.share)
    }

    @Test
    fun `onCreateInitialisesTheSharedAndroidContextFirst`() {
        // A factory constructed before `AndroidContext.init` would have thrown its loud
        // IllegalStateException while `onCreate` ran, so reaching this line at all proves the
        // ordering (FR-04, EC-06); the holder must point at this process's application.
        assertEquals(
            application.packageName,
            AndroidContext.applicationContext.packageName,
        )
    }

    @Test
    fun `theProcessObserverIsRegisteredExactlyOnce`() {
        // `onCreate` already registered the observer on the process lifecycle; every later attach
        // must be refused instead of adding a second observer (FR-11..FR-14: one save/reset pair
        // per process transition).
        val secondLifecycle = LifecycleRegistry(FakeLifecycleOwner())

        assertFalse(application.attachProcessLifecycle(secondLifecycle))
    }

    @Test
    fun `theGraphIsWiredWithTheRealSharedFactories`() {
        val container = application.container

        val path = container.recovery.save("wired through <filesDir>/recovery")
        assertEquals("wired through <filesDir>/recovery", container.recovery.read(path))

        val custom = AppSettings(colorScheme = AppColorScheme.dark, fontSizeIndex = 10)
        container.settings.save(custom)
        assertEquals(custom, container.settings.load())
    }

    @Test
    fun `theActivityHostRendersWithoutOwningSaveOrReset`() {
        val container = application.container
        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        val host = Robolectric.buildActivity(MainActivity::class.java).setup()
        host.pause().stop().destroy()

        // FR-14: an Activity teardown is not a background trip — the buffer and its history stay
        // exactly as they were, because MainActivity owns neither seam.
        assertEquals("draft", container.editor.value.text)
        assertTrue(container.editor.canUndo)
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
    }
}
