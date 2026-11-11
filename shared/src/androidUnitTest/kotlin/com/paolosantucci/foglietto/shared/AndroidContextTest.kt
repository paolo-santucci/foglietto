package com.paolosantucci.foglietto.shared

import android.app.Activity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tier B (Robolectric) contract tests for [AndroidContext] — spec §7.1 rows 730-731:
 * happy path (`init` stores `context.applicationContext`, a second `init` is a harmless
 * no-op) and failure path (reading before `init` throws a remediation-bearing
 * `IllegalStateException`). Covers FR-04, EC-06 and the sdk 26 + 36 matrix (NFR-12).
 *
 * The holder is process-scoped state and Robolectric reuses one class loader per sdk
 * sandbox, so every test resets it through the internal test hook to stay
 * order-independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AndroidContextTest {

    @After
    fun tearDown() {
        AndroidContext.resetForTesting()
    }

    @Test
    fun `init is idempotent and always stores the application context`() {
        val application = RuntimeEnvironment.getApplication()

        AndroidContext.init(application)
        val first = AndroidContext.applicationContext

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AndroidContext.init(activity) // second init: no throw, no replacement by the Activity

        assertSame(first, AndroidContext.applicationContext, "the holder must keep the same context")
        assertSame(application, AndroidContext.applicationContext, "the holder must store the Application")
    }

    @Test
    fun `reading the context before init throws a descriptive error`() {
        AndroidContext.resetForTesting()

        val failure = assertFailsWith<IllegalStateException> { AndroidContext.applicationContext }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("AndroidContext.init"), "message must name the init call: $message")
        assertTrue(message.contains("Application.onCreate"), "message must name the call site: $message")
    }

    @Test
    fun `a repeat init with the same context is a no-op`() {
        val application = RuntimeEnvironment.getApplication()

        AndroidContext.init(application)
        val first = AndroidContext.applicationContext
        AndroidContext.init(application)

        assertSame(first, AndroidContext.applicationContext)
        assertEquals(application, AndroidContext.applicationContext)
    }
}
