package com.paolosantucci.foglietto.shared.recovery

import com.paolosantucci.foglietto.shared.AndroidContext
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tier B (Robolectric) contract tests for the Android [recoveryBaseDir] actual —
 * spec §7.1 rows 730-731: it resolves `<filesDir>/recovery` under the initialised
 * holder (app-private internal storage, NFR-09) and fails loudly before init.
 * Covers FR-05 and the sdk 26 + 36 matrix (NFR-12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RecoveryDirAndroidTest {

    private val filesDir: Path
        get() = RuntimeEnvironment.getApplication().filesDir.toOkioPath()

    @Before
    fun setUp() {
        AndroidContext.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        AndroidContext.resetForTesting()
    }

    @Test
    fun `recoveryBaseDir is filesDir slash recovery`() {
        assertEquals(filesDir / "recovery", recoveryBaseDir())
        assertEquals("recovery", recoveryBaseDir().name)
        assertTrue(
            recoveryBaseDir().toString().startsWith(filesDir.toString()),
            "recovery must live under app-private internal storage: ${recoveryBaseDir()}"
        )
    }

    @Test
    fun `the android actual shares nothing with the jvm tmpdir actual`() {
        // FR-05: the androidMain actual must never resolve the non-production jvmMain value.
        val tmpDir = requireNotNull(System.getProperty("java.io.tmpdir"))
        val jvmActual = (tmpDir + "/foglietto-recovery").toPath()

        assertNotEquals(jvmActual, recoveryBaseDir())
        assertNotEquals(jvmActual.parent, recoveryBaseDir())
    }

    @Test
    fun `an uninitialised holder fails loudly instead of resolving a wrong directory`() {
        AndroidContext.resetForTesting()

        val failure = assertFailsWith<IllegalStateException> { recoveryBaseDir() }

        assertTrue(
            failure.message.orEmpty().contains("AndroidContext.init"),
            "failure must name the remediation: ${failure.message}"
        )
    }

    @Test
    fun `the base directory is stable across repeated reads`() {
        assertEquals(recoveryBaseDir(), recoveryBaseDir())
    }
}
