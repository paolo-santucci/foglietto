package com.paolosantucci.foglietto.shared.settings

import android.preference.PreferenceManager
import com.paolosantucci.foglietto.shared.AndroidContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tier B (Robolectric) contract tests for [createAndroidSettingsRepository].
 *
 * Covers the plan's TASK-07 vectors: an empty store yields [AppSettings] defaults, and
 * writing color-scheme + font-size round-trips through the app's **default**
 * `SharedPreferences` — the store named by `PreferenceManager.getDefaultSharedPreferences`,
 * which `androidApp` and the platform both see.
 *
 * The matrix is pinned to sdk 26 + 36 (NFR-12); on this JDK 17 host the 36 leg cannot start
 * ("Android SDK 36 requires Java 21") and `shared/build.gradle.kts` announces the degradation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AndroidSettingsFactoryTest {

    private val application get() = RuntimeEnvironment.getApplication()

    /**
     * The platform's default preferences: `PreferenceManager.getDefaultSharedPreferences`
     * maps to `context.getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)`.
     */
    @Suppress("DEPRECATION")
    private fun defaultPreferences() = PreferenceManager.getDefaultSharedPreferences(application)

    @Before
    fun setUp() {
        AndroidContext.init(application)
        // Robolectric can reuse a sdk sandbox's data dir across tests — start from an empty store.
        defaultPreferences().edit().clear().commit()
    }

    @After
    fun tearDown() {
        defaultPreferences().edit().clear().commit()
        AndroidContext.resetForTesting()
    }

    @Test
    fun `settings default on first run`() {
        assertEquals(
            AppSettings(colorScheme = AppColorScheme.follow, fontSizeIndex = 8),
            createAndroidSettingsRepository().load()
        )
    }

    @Test
    fun `settings round-trip both keys`() {
        val repository = createAndroidSettingsRepository()
        val saved = AppSettings(colorScheme = AppColorScheme.dark, fontSizeIndex = 12)

        repository.save(saved)

        // The write lands in the platform default SharedPreferences, under the two keys only.
        val platformPreferences = defaultPreferences()
        assertEquals(
            "dark",
            platformPreferences.getString(AppSettings.KEY_COLOR_SCHEME, null)
        )
        assertEquals(
            12,
            platformPreferences.getInt(AppSettings.KEY_FONT_SIZE, -1)
        )
        assertNull(platformPreferences.getString("use-monospace-font", null), "dropped GNOME keys stay unwritten")

        // A fresh factory instance reads the persisted values back identically.
        assertEquals(saved, createAndroidSettingsRepository().load())
    }
}
