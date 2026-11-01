package com.paolosantucci.foglietto.shared.settings

// ──────────────────────────────────────────────────────────────────────────────
// SettingsRepositoryTest — commonTest TDD oracle (RED phase first, then GREEN).
//
// Port of the Dart oracle
//   test/infrastructure/settings/shared_preferences_settings_repository_test.dart
// scoped to the MVP-trimmed surface (OQ-02 drop-list applied):
//   DROPPED: 7-key fidelity (use-monospace-font / check-spelling / save-emergency-files /
//            emergency-recovery-files / line-length), lineLengthEnabled derivation,
//            emergencyRecoveryEnabled, spellingEnabled, useMonospaceFont, setUseMonospaceFont.
//   KEPT:    color-scheme parse (3 happy paths + 2 EC-01 fallbacks), font-size
//            (absent/corrupt/clamp/round-trip), save 2-key fidelity, independent round-trip.
//
// Test runner: MockSettings — a minimal MutableMap-backed Settings implementation
// declared at the bottom of this file. The plan refers to this as "MapSettings (in
// the core multiplatform-settings artifact)", but com.russhwolf:multiplatform-settings:1.2.0
// does NOT ship MapSettings in the JVM artifact (only PreferencesSettings and
// PropertiesSettings, both JVM-only). To keep tests in commonTest with NO new
// dependency, MockSettings is implemented inline here — identical contract to
// MapSettings. [DEVIATION from plan wording; intent (commonTest, no new dep) satisfied.]
//
// [PORTING-ADDITION] wrong-type font-size: MockSettings.getIntOrNull on a key that
// holds no int returns null (no throw). To exercise the throw path required for
// NSUserDefaultsSettings parity (OQ-C / OQ-QA-02), a ThrowingSettings stub is
// provided that throws ClassCastException from getIntOrNull unconditionally.
//
// [IMPL NOTE — OQ-C finding] MockSettings does NOT throw on wrong-type (it returns
// null). NSUserDefaultsSettings (iOS) DOES throw ClassCastException. The runCatching
// wrapper in SettingsRepositoryImpl.load() absorbs both paths. The throw path is
// exercised via ThrowingSettings in §7 below. Plain MapSettings/PropertiesSettings
// path is exercised by all other tests.
//
// Spec refs: FR-22, FR-23, FR-24, FR-25, EC-01, EC-04, S-C2, S-I1, OQ-02, NFR-06.
// ──────────────────────────────────────────────────────────────────────────────

import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoryTest {

    // ─────────────────────────────────────────────────────────────────────────
    // § 1  load() on empty store — defaults (FR-22, FR-23)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_empty_store_when_load_then_returns_follow_and_index_8() {
        val repo = SettingsRepositoryImpl(MockSettings())
        val s = repo.load()
        assertEquals(AppColorScheme.follow, s.colorScheme)
        assertEquals(8, s.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 2  load() color-scheme happy paths (FR-22)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_color_scheme_dark_when_load_then_colorScheme_is_dark() {
        val settings = MockSettings()
        settings.putString(AppSettings.KEY_COLOR_SCHEME, "dark")
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(AppColorScheme.dark, s.colorScheme)
    }

    @Test
    fun given_color_scheme_light_when_load_then_colorScheme_is_light() {
        val settings = MockSettings()
        settings.putString(AppSettings.KEY_COLOR_SCHEME, "light")
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(AppColorScheme.light, s.colorScheme)
    }

    @Test
    fun given_color_scheme_follow_when_load_then_colorScheme_is_follow() {
        val settings = MockSettings()
        settings.putString(AppSettings.KEY_COLOR_SCHEME, "follow")
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(AppColorScheme.follow, s.colorScheme)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 3  load() color-scheme EC-01 fallback — unknown/absent must NOT throw
    //       and must yield AppColorScheme.follow (FR-22, EC-01)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_color_scheme_unknown_value_foo_when_load_then_follow_no_throw() {
        val settings = MockSettings()
        settings.putString(AppSettings.KEY_COLOR_SCHEME, "foo")
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(AppColorScheme.follow, s.colorScheme)
    }

    @Test
    fun given_color_scheme_absent_when_load_then_follow_no_throw() {
        // key not set at all
        val s = SettingsRepositoryImpl(MockSettings()).load()
        assertEquals(AppColorScheme.follow, s.colorScheme)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 4  load() font-size: absent → default 8 (FR-23)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_font_size_absent_when_load_then_fontSizeIndex_defaults_to_8() {
        val s = SettingsRepositoryImpl(MockSettings()).load()
        assertEquals(8, s.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 5  load() font-size: stored valid index → read back (FR-23)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_font_size_index_12_when_load_then_fontSizeIndex_is_12() {
        val settings = MockSettings()
        settings.putInt(AppSettings.KEY_FONT_SIZE, 12)
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(12, s.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 6  load() font-size: clamp out-of-range (FR-24, S-I1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_font_size_minus1_when_load_then_clamped_to_0() {
        val settings = MockSettings()
        settings.putInt(AppSettings.KEY_FONT_SIZE, -1)
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(0, s.fontSizeIndex)
    }

    @Test
    fun given_font_size_99_when_load_then_clamped_to_20() {
        val settings = MockSettings()
        settings.putInt(AppSettings.KEY_FONT_SIZE, 99)
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(20, s.fontSizeIndex)
    }

    @Test
    fun given_font_size_21_when_load_then_clamped_to_20() {
        val settings = MockSettings()
        settings.putInt(AppSettings.KEY_FONT_SIZE, 21)
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(20, s.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 7  [PORTING-ADDITION] font-size wrong-type: runCatching absorbs throw
    //      (FR-23, S-I1, OQ-C, OQ-QA-02)
    //
    // MockSettings.getIntOrNull on a key that was not set via putInt returns
    // null (no throw) — the runCatching wrapper handles the null path safely.
    //
    // This test seeds the wrong-type value via ThrowingSettings (a stub whose
    // getIntOrNull throws ClassCastException unconditionally), explicitly
    // exercising the THROW path that NSUserDefaultsSettings takes on a type
    // mismatch. SettingsRepositoryImpl.load() must catch this and return the
    // default index 8 without re-throwing.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_font_size_key_throws_class_cast_when_load_then_defaults_to_8_no_throw() {
        val throwingSettings = ThrowingSettings()
        val s = SettingsRepositoryImpl(throwingSettings).load()
        assertEquals(8, s.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 8  save() then load() round-trip (FR-25)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_save_dark_index_12_when_load_then_round_trips() {
        val settings = MockSettings()
        val repo = SettingsRepositoryImpl(settings)
        repo.save(AppSettings(colorScheme = AppColorScheme.dark, fontSizeIndex = 12))
        val loaded = repo.load()
        assertEquals(AppColorScheme.dark, loaded.colorScheme)
        assertEquals(12, loaded.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 9  save() writes EXACTLY two keys (FR-25, OQ-02)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_save_when_called_then_exactly_two_keys_written_color_scheme_and_font_size() {
        val settings = MockSettings()
        SettingsRepositoryImpl(settings).save(AppSettings())
        val keys = settings.keys
        assertEquals(2, keys.size, "save() must write exactly 2 keys (color-scheme + font-size)")
        assertTrue(keys.contains(AppSettings.KEY_COLOR_SCHEME))
        assertTrue(keys.contains(AppSettings.KEY_FONT_SIZE))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 10 save() writes colorScheme.name as String under KEY_COLOR_SCHEME (FR-25)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_save_dark_when_called_then_color_scheme_key_holds_string_dark() {
        val settings = MockSettings()
        SettingsRepositoryImpl(settings).save(AppSettings(colorScheme = AppColorScheme.dark))
        assertEquals("dark", settings.getStringOrNull(AppSettings.KEY_COLOR_SCHEME))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 11 save() writes fontSizeIndex as Int under KEY_FONT_SIZE (FR-25)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_save_index_5_when_called_then_font_size_key_holds_int_5() {
        val settings = MockSettings()
        SettingsRepositoryImpl(settings).save(AppSettings(fontSizeIndex = 5))
        assertEquals(5, settings.getIntOrNull(AppSettings.KEY_FONT_SIZE))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 12 None of the 5 dropped GNOME keys are present after save() (OQ-02)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_save_when_called_then_no_dropped_gnome_keys_are_present() {
        val settings = MockSettings()
        SettingsRepositoryImpl(settings).save(AppSettings())
        val keys = settings.keys
        assertFalse(keys.contains("use-monospace-font"))
        assertFalse(keys.contains("check-spelling"))
        assertFalse(keys.contains("save-emergency-files"))
        assertFalse(keys.contains("emergency-recovery-files"))
        assertFalse(keys.contains("line-length"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 13 Fields round-trip independently: two successive saves (FR-25)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_two_saves_when_load_then_second_save_wins_independently() {
        val settings = MockSettings()
        val repo = SettingsRepositoryImpl(settings)
        repo.save(AppSettings(colorScheme = AppColorScheme.dark, fontSizeIndex = 8))
        repo.save(AppSettings(colorScheme = AppColorScheme.follow, fontSizeIndex = 12))
        val loaded = repo.load()
        assertEquals(AppColorScheme.follow, loaded.colorScheme)
        assertEquals(12, loaded.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 14 load() from raw keys (bypass save, seed directly) — confirms impl
    //      reads the right key names (FR-22, FR-25)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_raw_keys_seeded_when_load_then_reads_them_correctly() {
        val settings = MockSettings()
        settings.putString(AppSettings.KEY_COLOR_SCHEME, "light")
        settings.putInt(AppSettings.KEY_FONT_SIZE, 10)
        val s = SettingsRepositoryImpl(settings).load()
        assertEquals(AppColorScheme.light, s.colorScheme)
        assertEquals(10, s.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 15 load() never throws — total safety check (FR-22, FR-23)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_completely_empty_store_when_load_called_multiple_times_then_no_exception() {
        val repo = SettingsRepositoryImpl(MockSettings())
        repeat(3) { repo.load() }
        // no assertion needed — must not throw
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § 16 save() followed by load() — default AppSettings round-trips cleanly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun given_default_AppSettings_when_save_then_load_then_round_trips() {
        val settings = MockSettings()
        val repo = SettingsRepositoryImpl(settings)
        repo.save(AppSettings())
        val loaded = repo.load()
        assertEquals(AppColorScheme.follow, loaded.colorScheme)
        assertEquals(8, loaded.fontSizeIndex)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// MockSettings — minimal MutableMap-backed Settings implementation for commonTest.
//
// Plan refers to this as "MapSettings" (com.russhwolf.settings.MapSettings), but
// multiplatform-settings:1.2.0 does NOT ship MapSettings in the JVM classpath
// (the artifact exposes only PropertiesSettings and PreferencesSettings, both
// JVM-only). To keep tests in commonTest with NO new dependency, MockSettings is
// implemented inline. Contract is identical to MapSettings:
//   - All typed getOrNull return null for absent keys.
//   - putX/getX operate on a single MutableMap<String, Any>.
//   - keys returns the current key set.
// [DEVIATION] Plan wording says "MapSettings from the core artifact"; the intent
// (commonTest, no new dependency, in-memory Settings) is satisfied by MockSettings.
// ──────────────────────────────────────────────────────────────────────────────

private class MockSettings : Settings {
    private val store = mutableMapOf<String, Any>()

    override val keys: Set<String> get() = store.keys.toSet()
    override val size: Int get() = store.size
    override fun clear() = store.clear()
    override fun remove(key: String) { store.remove(key) }
    override fun hasKey(key: String): Boolean = store.containsKey(key)

    override fun putString(key: String, value: String) { store[key] = value }
    override fun getString(key: String, defaultValue: String): String =
        store[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = store[key] as? String

    override fun putInt(key: String, value: Int) { store[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int =
        store[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = store[key] as? Int

    override fun putLong(key: String, value: Long) { store[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long =
        store[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = store[key] as? Long

    override fun putFloat(key: String, value: Float) { store[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float =
        store[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = store[key] as? Float

    override fun putDouble(key: String, value: Double) { store[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double =
        store[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = store[key] as? Double

    override fun putBoolean(key: String, value: Boolean) { store[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        store[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = store[key] as? Boolean
}

// ──────────────────────────────────────────────────────────────────────────────
// ThrowingSettings stub — Settings implementation whose getIntOrNull throws
// ClassCastException unconditionally, simulating NSUserDefaultsSettings behaviour
// on a type mismatch (OQ-C / OQ-QA-02).
//
// All other operations delegate to a backing MockSettings so that color-scheme
// reads (which use getString/getStringOrNull) work normally.
// ──────────────────────────────────────────────────────────────────────────────

private class ThrowingSettings : Settings {
    private val delegate = MockSettings()

    override val keys: Set<String> get() = delegate.keys
    override val size: Int get() = delegate.size
    override fun clear() = delegate.clear()
    override fun remove(key: String) = delegate.remove(key)
    override fun hasKey(key: String): Boolean = delegate.hasKey(key)

    // color-scheme reads pass through — only int reads throw
    override fun putString(key: String, value: String) = delegate.putString(key, value)
    override fun getString(key: String, defaultValue: String): String = delegate.getString(key, defaultValue)
    override fun getStringOrNull(key: String): String? = delegate.getStringOrNull(key)

    // INT: always throw ClassCastException — NSUserDefaultsSettings on type mismatch
    override fun putInt(key: String, value: Int) = delegate.putInt(key, value)
    override fun getInt(key: String, defaultValue: Int): Int =
        throw ClassCastException("wrong type (ThrowingSettings stub)")
    override fun getIntOrNull(key: String): Int? =
        throw ClassCastException("wrong type (ThrowingSettings stub)")

    override fun putLong(key: String, value: Long) = delegate.putLong(key, value)
    override fun getLong(key: String, defaultValue: Long): Long = delegate.getLong(key, defaultValue)
    override fun getLongOrNull(key: String): Long? = delegate.getLongOrNull(key)

    override fun putFloat(key: String, value: Float) = delegate.putFloat(key, value)
    override fun getFloat(key: String, defaultValue: Float): Float = delegate.getFloat(key, defaultValue)
    override fun getFloatOrNull(key: String): Float? = delegate.getFloatOrNull(key)

    override fun putDouble(key: String, value: Double) = delegate.putDouble(key, value)
    override fun getDouble(key: String, defaultValue: Double): Double = delegate.getDouble(key, defaultValue)
    override fun getDoubleOrNull(key: String): Double? = delegate.getDoubleOrNull(key)

    override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = delegate.getBoolean(key, defaultValue)
    override fun getBooleanOrNull(key: String): Boolean? = delegate.getBooleanOrNull(key)
}
