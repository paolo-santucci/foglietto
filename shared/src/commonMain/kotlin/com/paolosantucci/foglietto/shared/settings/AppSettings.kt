package com.paolosantucci.foglietto.shared.settings

/**
 * Theme-mode preference.
 *
 * Lower-case entries so `.name` round-trips the wire value stored under
 * [AppSettings.KEY_COLOR_SCHEME] (i.e. `"follow"`, `"light"`, `"dark"`).
 *
 * Spec §5.1.2 / FR-19. Parse via explicit `when` — never via `valueOf`/`enumValueOf`
 * (which throw on an unknown value). That defensive parse is SettingsRepositoryImpl's
 * concern (FR-22, TASK-06).
 */
enum class AppColorScheme { follow, light, dark }

/**
 * Immutable settings model — MVP-trimmed port of the Dart `AppSettings` class.
 *
 * Trimmed surface (OQ-02 drop-list — the following DO NOT exist by construction):
 *   - useMonospaceFont, spellingEnabled, emergencyRecoveryEnabled, lineLengthEnabled
 *   - emergencyRecoveryFiles, lineLength (integer-proxy getters)
 *   - setUseMonospaceFont
 *   - keys: use-monospace-font, check-spelling, save-emergency-files,
 *            emergency-recovery-files, line-length
 *
 * @property colorScheme Theme-mode preference; default [AppColorScheme.follow].
 * @property fontSizeIndex Index into [slotList]; default 8 → 14pt.
 *
 * Spec refs: FR-19, FR-20, FR-21, §5.1.2; S-C1, S-I3 (assessment).
 */
data class AppSettings(
    val colorScheme: AppColorScheme = AppColorScheme.follow,
    val fontSizeIndex: Int = 8,
) {

    /**
     * Current font size in points derived from [fontSizeIndex].
     *
     * Computed val (never stored). Spec §5.1.2 declares this as `Int`; the Dart
     * oracle returns `double` for Dart's type system — the Kotlin port follows
     * the spec and returns `Int`.
     */
    val fontSizePt: Int get() = slotList[fontSizeIndex]

    /**
     * Returns a copy of this settings with [fontSizeIndex] set to [index],
     * clamped to `[0, slotList.lastIndex]`.
     *
     * Identity-stable (S-C1): when the clamped value equals the current
     * [fontSizeIndex], returns `this` — NOT a new `copy()` — so that callers
     * can test `assertSame` to catch regressions where an unconditional
     * `copy()` would produce a distinct object even on a no-op.
     *
     * The identity-stable guard (`if (clamped == fontSizeIndex) return this`)
     * MUST appear BEFORE `copy(...)`.
     *
     * Spec FR-20, S-C1, S-I3.
     */
    fun setFontSizeIndex(index: Int): AppSettings {
        val clamped = index.coerceIn(0, slotList.lastIndex)
        if (clamped == fontSizeIndex) return this
        return copy(fontSizeIndex = clamped)
    }

    /**
     * Returns a copy of this settings with [colorScheme] set to [scheme],
     * preserving [fontSizeIndex] verbatim.
     *
     * Identity-stable (S-C1, mirroring [setFontSizeIndex]): when [scheme]
     * equals the current [colorScheme], returns `this` — NOT a new `copy()`.
     * This allows callers (e.g. the theme picker) to use value-equality as a
     * no-op guard (FR-10) without allocating a new instance.
     *
     * Non-suspend. Additive — no existing member changed or removed.
     * Platform-neutral (commonMain) — no Apple/JVM platform dependency.
     *
     * Spec FR-04, FR-24, NFR-08; closes CM-2. Contract §5.1.a.
     */
    fun setColorScheme(scheme: AppColorScheme): AppSettings {
        if (scheme == colorScheme) return this
        return copy(colorScheme = scheme)
    }

    companion object {
        /**
         * 21-slot font-size table.
         *
         * Declared `val` (NOT `const`) because `const` requires a `List<Int>` to
         * be a compile-time constant, which is not supported for collection types
         * in Kotlin. The plan explicitly requires `val`, not `const`.
         *
         * Invariants (FR-21, S-I3):
         *   - length == 21
         *   - slotList[8] == 14  (the default index)
         *   - strictly ascending
         *   - no duplicates
         *   - slotList[0] == 6, slotList[20] == 38
         */
        val slotList: List<Int> = listOf(
            6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 26, 28, 30, 34, 38
        )

        /** Key for the [colorScheme] setting in the multiplatform-settings store. */
        const val KEY_COLOR_SCHEME: String = "color-scheme"

        /** Key for the [fontSizeIndex] setting in the multiplatform-settings store. */
        const val KEY_FONT_SIZE: String = "font-size"
    }
}
