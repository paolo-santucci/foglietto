package com.paolosantucci.foglietto.ui.chrome

import android.content.Context
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tier B — the one seam that reads the system animator scale (FR-35, NFR-05, EC-26, NFR-12).
 *
 * The sdk 26 + 36 matrix stays pinned. Robolectric's API-36 sandbox needs a Java 21 runtime,
 * so on the mandated JDK 17 toolchain only the API-26 leg starts (the shared module announces
 * the same degradation); the full matrix must execute on a JDK 21 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class MotionProviderTest {

    private lateinit var context: Context

    @Before
    fun captureApplication() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun restoreAnimatorScale() {
        if (::context.isInitialized) {
            writeAnimatorScale(1f)
        }
    }

    @Test
    fun `the provider collapses when the animator scale is zero`() {
        writeAnimatorScale(0f)

        val spec = provideMotion(context)

        assertTrue(spec.collapsed)
        assertEquals(0, spec.revealMillis)
        assertEquals(0, spec.hideMillis)
        assertEquals(0, spec.enabledSpringMillis)
    }

    @Test
    fun `the provider keeps the tuned table at scale one`() {
        writeAnimatorScale(1f)

        val spec = provideMotion(context)

        assertFalse(spec.collapsed)
        assertEquals(resolveMotion(1f), spec)
    }

    private fun writeAnimatorScale(scale: Float) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
    }
}
