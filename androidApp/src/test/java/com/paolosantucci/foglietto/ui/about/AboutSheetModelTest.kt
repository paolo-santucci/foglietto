package com.paolosantucci.foglietto.ui.about

import android.content.Context
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tier B — the pure seam behind [AboutSheet] (FR-34, NFR-12).
 *
 * Compose UI interaction tests are Tier C and out of scope this wave, so the sheet's contract is
 * pinned here: the two link targets are the canonical URLs the iOS About menu opens (byte-exact and
 * **not** localised, so both locales resolve the same address), and the displayed version is read
 * from the installed package metadata rather than from a Kotlin literal.
 *
 * The sdk 26 + 36 matrix stays pinned. Robolectric's API-36 sandbox needs a Java 21 runtime, so on
 * the mandated JDK 17 toolchain only the API-26 leg starts (same announced degradation as
 * `MotionProviderTest`); the full matrix must execute on a JDK 21 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AboutSheetModelTest {

    private lateinit var context: Context

    @Before
    fun captureApplication() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `the about links are exactly the canonical urls`() {
        assertEquals(
            listOf(
                "https://github.com/paolo-santucci/foglietto/issues",
                "https://github.com/paolo-santucci/foglietto",
            ),
            AboutSheetModel.from(versionName = "1.0.0").links,
        )
    }

    @Test
    fun `the version is resolved from the installed package, not a literal`() {
        // This JVM has no AGP test-config integration (`android_merged_manifest` is null), so the
        // shadow package carries no build metadata. Install a version no source file could
        // hard-code: an implementation returning a constant cannot follow it, which is exactly the
        // "read the version, never a literal" invariant (FR-34, OQ-04).
        installVersion("7.4.2")

        assertEquals("7.4.2", AboutSheetModel.fromContext(context).versionName)
    }

    @Test
    fun `both rows keep their own absolute, unlocalised address`() {
        val model = AboutSheetModel.from(versionName = "1.0.0")

        assertEquals(2, model.links.size)
        assertTrue(model.links.all { it.startsWith("https://github.com/paolo-santucci/foglietto") })
        assertEquals(AboutSheetModel.ISSUES_URL, model.issuesUrl)
        assertEquals(AboutSheetModel.WEBSITE_URL, model.websiteUrl)
    }

    @Test
    fun `the identity block is the app name, the author and the licence`() {
        assertEquals("Foglietto", AboutSheetModel.APP_NAME)
        assertEquals("Paolo Santucci", AboutSheetModel.AUTHOR)
        assertEquals("GPL-3.0", AboutSheetModel.LICENSE)
    }

    @Suppress("DEPRECATION")
    private fun installVersion(versionName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = context.packageName
                this.versionName = versionName
            },
        )
    }
}
