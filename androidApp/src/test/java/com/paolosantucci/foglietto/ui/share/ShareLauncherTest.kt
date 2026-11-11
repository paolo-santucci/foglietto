package com.paolosantucci.foglietto.ui.share

import android.app.Activity
import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tier B — the outbound share seam (FR-33, OQ-02 `text/plain`, EC-30, NFR-12).
 *
 * The sdk 26 + 36 matrix stays pinned. Robolectric's API-36 sandbox needs a Java 21 runtime, so on
 * the mandated JDK 17 toolchain only the API-26 leg starts (the module announces the degradation);
 * the full matrix must execute on a JDK 21 host.
 *
 * Enablement is decided by the shared `canShare` predicate and nothing else: the launcher calls the
 * same function behind `EditorStateHolder.shareEnabled` (TASK-05/13), so the button and the
 * payload can never disagree about whitespace-only text (EC-30 — no local re-derivation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ShareLauncherTest {

    @Test
    fun `share launches a chooser carrying ACTION_SEND text-plain and the raw untrimmed text`() {
        val application = RuntimeEnvironment.getApplication()
        // Leading/trailing whitespace and the newline must survive verbatim: the payload is the
        // buffer, not a trimmed preview.
        val rawText = "  hello\nworld \n"

        ShareLauncher(application).share(rawText)

        val chooser = shadowOf(application).nextStartedActivity
        assertNotNull("share must start an intent", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)

        val send = chooser.chooserTarget()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(rawText, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a non-Activity context starts the chooser in a new task`() {
        val application = RuntimeEnvironment.getApplication()

        ShareLauncher(application).share("hello")

        val chooser = shadowOf(application).nextStartedActivity
        assertNotNull("share must start an intent", chooser)
        assertTrue(
            "a Context that is not an Activity must carry FLAG_ACTIVITY_NEW_TASK",
            chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK == Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `an Activity context starts the chooser inside the current task`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        ShareLauncher(activity).share("hello")

        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("share must start an intent", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(
            "an Activity host must not be pushed into a new task",
            0,
            chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `an empty text launches nothing`() {
        val application: Application = RuntimeEnvironment.getApplication()

        ShareLauncher(application).share("")

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `whitespace-only text is rejected by the shared predicate`() {
        val application: Application = RuntimeEnvironment.getApplication()

        // EC-30: the guard is the shared `canShare` (which trims), not a local `isEmpty()` check —
        // otherwise this payload would open a share sheet with no visible content.
        ShareLauncher(application).share(" \n ")

        assertNull(
            "whitespace-only text must not reach the share sheet",
            shadowOf(application).nextStartedActivity,
        )
    }
}

@Suppress("DEPRECATION") // getParcelableExtra(String) is the pre-API-33 form the chooser payload uses.
private fun Intent.chooserTarget(): Intent =
    requireNotNull(getParcelableExtra<Intent>(Intent.EXTRA_INTENT)) {
        "a chooser must wrap its target in EXTRA_INTENT"
    }
