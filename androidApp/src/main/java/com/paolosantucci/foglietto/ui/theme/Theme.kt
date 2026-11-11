package com.paolosantucci.foglietto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/*
 * The single app theme: a Material 3 colour scheme resolved from the system dark-mode setting.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md` (§Colour & branding tokens, §Scheme matrix):
 * the editor canvas is the platform view background (`light #FFFFFF` / `dark #202020`), the editor
 * ink is the platform text colour, and the hairline ring maps to `outlineVariant`. The branding
 * colours stay out of the scheme — the canon reserves them for the icon/splash (TASK-12).
 *
 * ## Why the scheme is driven token by token
 *
 * Every role the chrome paints is set from a named token in `Color.kt`. The Material *baseline*
 * defaults are deliberately not used for the tonal roles: `secondaryContainer` defaults to the
 * baseline scheme's lavender container (`#E9DDFF`-family), which is what painted the action
 * buttons purple. `FilledTonalIconButton` reads exactly `secondaryContainer`/`onSecondaryContainer`,
 * so those two roles are the fix; `surfaceContainerLow` is the sheet's container, and
 * `surfaceVariant`/`onSurfaceVariant`/`outlineVariant` carry the sheet's supporting ink and edges.
 *
 * CANON GAP: the canon recommends `ColorScheme.fromSeed(...)` following the *platform* accent and
 * exposing a Follow/Light/Dark three-way override. FR-19 pins "follows the system theme ... and
 * offers no theme or font picker anywhere", and this task additionally excludes dynamic colour, so
 * the scheme is fixed. No picker, no `dynamicLightColorScheme()`.
 */

/** Light scheme — the neutral chrome palette (light column of `Color.kt`). */
internal val FogliettoLightColorScheme = lightColorScheme(
    // Canon maps `--accent-bg-color` (the platform accent) to `primary`; the branding yellow must
    // never be used here (canon: "Do not tint the editing surface").
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    // `primaryContainer`/`secondary`/`tertiary` are not painted by today's chrome, but leaving the
    // Material *baseline* values there would keep lavender in the scheme for the first component
    // that reaches for them. Every tonal role is therefore a neutral palette role.
    primaryContainer = SecondaryContainerLight,
    onPrimaryContainer = OnSecondaryContainerLight,
    secondary = OnSurfaceVariantLight,
    onSecondary = EditorSurfaceLight,
    // The tonal-action fill: `FilledTonalIconButton` reads these two roles, and the action row's
    // enablement crossfade lerps between them and `onSurface`.
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = PrimaryLight,
    onTertiary = OnPrimaryLight,
    tertiaryContainer = SecondaryContainerLight,
    onTertiaryContainer = OnSecondaryContainerLight,
    surface = EditorSurfaceLight,
    onSurface = OnSurfaceLight,
    background = EditorSurfaceLight,
    onBackground = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outlineVariant = OutlineVariantLight,
    // The ⋮ sheet's container, the raised band inside it, and the top bar's scrolled container.
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainer = SurfaceVariantLight,
    // A flat elevation: `surfaceTint` == `surface` means no tonal hue shift on raised surfaces.
    surfaceTint = EditorSurfaceLight,
    // The Snackbar's action colour sits on the dark `inverseSurface`, so it is the dark-mode blue.
    inversePrimary = PrimaryDark,
)

/** Dark scheme — the dark column of the same tokens. */
internal val FogliettoDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = SecondaryContainerDark,
    onPrimaryContainer = OnSecondaryContainerDark,
    secondary = OnSurfaceVariantDark,
    onSecondary = EditorSurfaceDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = PrimaryDark,
    onTertiary = OnPrimaryDark,
    tertiaryContainer = SecondaryContainerDark,
    onTertiaryContainer = OnSecondaryContainerDark,
    surface = EditorSurfaceDark,
    onSurface = OnSurfaceDark,
    background = EditorSurfaceDark,
    onBackground = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainer = SurfaceVariantDark,
    surfaceTint = EditorSurfaceDark,
    // The Snackbar's action colour sits on the light `inverseSurface`, so it is the deep blue.
    inversePrimary = OnPrimaryDark,
)

/**
 * App theme. Follows the system dark-mode setting only — no user choice, no picker (FR-19).
 *
 * @param darkTheme defaults to [isSystemInDarkTheme]; configuration changes (uiMode) re-resolve
 *   the scheme with no manual work (EC-13).
 */
@Composable
internal fun FogliettoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FogliettoDarkColorScheme else FogliettoLightColorScheme,
        typography = FogliettoTypography,
        content = content,
    )
}
