package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/**
 * The chrome's two icon-only controls, both built on one enablement animation:
 *
 *  - [TonalActionButton] — a **rounded-square tonal tile**: the top bar's control (Undo, Redo,
 *    Share, ⋮). Keep's Material 3 toolbar puts each action in its own rounded square rather than in
 *    a circle, which is what the product owner asked for.
 *  - [GlyphActionButton] — the glyph alone, no container: the floating bottom pill's controls
 *    (Copy, Paste, Outdent, Indent, Clear), which Keep draws as bare icons inside one shared
 *    surface.
 *
 * ## The tile is a Material 3 component, restyled
 *
 * [TonalActionButton] delegates to [FilledTonalIconButton] and changes exactly two things: the
 * shape ([TileShape]) and the colour pair (the animated [ChromeActionColors]). Everything else is
 * the component's:
 *
 *  - **Visual size**: M3's icon-button state layer is a 40dp box — the "40dp box" the tile
 *    proportion below is drawn on. Do **not** wrap this control in `Modifier.size(...)`: an outer
 *    `size()` is `enforceIncoming`, so a `Modifier.size(48.dp)` would coerce the 40dp visual up to
 *    48 and inflate the tile instead of growing the hit area.
 *  - **Touch target**: `minimumInteractiveComponentSize()` gives the 48dp target NFR-04 asks for
 *    while the drawn tile stays 40dp — the separation the circular buttons used to hand-roll.
 *
 * [GlyphActionButton] keeps a hand-rolled [ActionTarget] box because the pill's `Surface` is its
 * container and there is nothing for a Material 3 button to draw.
 *
 * ## Styling
 *
 * The tile's fill and ink are the scheme's `secondaryContainer`/`onSecondaryContainer` — the pair
 * `FilledTonalIconButton` is defined against, resolved from `theme/Color.kt`'s neutral palette and
 * never restated at a call site. The pill's glyph ink is `onSurface`: inside the pill the ink sits
 * directly on `surfaceContainerLow`, not on a tonal container.
 *
 * ## Enablement animation (FR-35, NFR-05)
 *
 * Disabled is a *lighter* container of the same tint (not a grey veil) and the ink at 38 % — the
 * pair Keep shows for an empty history. The two states are crossfaded by a fraction driven by
 * [MotionSpec]. With `motion.collapsed` (system animator scale 0) the spec resolves to [snap], i.e.
 * the end state immediately, never a zero-length tween. `FilledTonalIconButton`'s own colours are
 * not animated, so the animated pair is handed over as *both* its enabled and disabled palette;
 * the crossfade stays ours.
 *
 * ## Accessibility (NFR-04)
 *
 * Every control is icon-only, so it carries a string-resource `contentDescription` ([labelRes]) and
 * reports `disabled()` when it cannot accept input; the `Icon` underneath is decorative (`null`) to
 * avoid a doubled TalkBack label. The role is [Role.Button] — set by the Material 3 component for
 * the tile and by [ActionTarget] for the glyph.
 *
 * @param labelRes the accessible name — always a string resource, never a literal.
 * @param imageVector the Material icon rendered at the variant's glyph size, tinted by the
 *   (animated) content colour.
 * @param enabled whether the control accepts input; the disabled pair is animated, not abrupt.
 * @param onClick invoked on tap; a disabled button never fires it.
 * @param modifier the host's slot, applied to the interactive box exactly once.
 * @param motion the resolved chrome motion; production reads the system animator scale once.
 */
@Composable
fun TonalActionButton(
    @StringRes labelRes: Int,
    imageVector: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
) {
    val label = stringResource(labelRes)
    val colors = rememberTonalActionColors(enabled = enabled, motion = motion)

    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label
            // `enabled = false` already drops the click action; this keeps the reason explicit for
            // TalkBack ("dimmed") instead of leaving an unnamed inert box.
            if (!enabled) disabled()
        },
        enabled = enabled,
        shape = TileShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            // The animated pair is passed for both states: M3 switches its palette abruptly, the
            // crossfade is `rememberTonalActionColors`'.
            containerColor = colors.container,
            contentColor = colors.content,
            disabledContainerColor = colors.container,
            disabledContentColor = colors.content,
        ),
    ) {
        ChromeGlyph(imageVector = imageVector, tint = colors.content, size = TileGlyphSize)
    }
}

/**
 * The bottom pill's control: the same enablement crossfade and the same 48dp target as
 * [TonalActionButton], but no container of its own — the pill's `Surface` is the container.
 */
@Composable
fun GlyphActionButton(
    @StringRes labelRes: Int,
    imageVector: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
) {
    val label = stringResource(labelRes)
    val ink = rememberGlyphInk(enabled = enabled, motion = motion)

    ActionTarget(label = label, enabled = enabled, onClick = onClick, modifier = modifier) {
        ChromeGlyph(imageVector = imageVector, tint = ink, size = PillGlyphSize)
    }
}

/** The tonal tile's colour pair, resolved once from the scheme and the motion spec. */
internal data class ChromeActionColors(
    /** The tonal container fill. */
    val container: Color,
    /** The ink inside it. */
    val content: Color,
)

/**
 * The one place the **tile's** colours are computed, so four tiles can never drift apart: enabled is
 * `secondaryContainer` with `onSecondaryContainer` ink — `FilledTonalIconButton`'s own contract —
 * and disabled is the same pair dimmed ([DisabledContainerAlpha] fill, [DisabledContentAlpha] ink).
 */
@Composable
internal fun rememberTonalActionColors(enabled: Boolean, motion: MotionSpec): ChromeActionColors {
    val enabledFraction = rememberEnabledFraction(enabled = enabled, motion = motion)
    val scheme = MaterialTheme.colorScheme
    return ChromeActionColors(
        container = lerp(
            scheme.secondaryContainer.copy(alpha = DisabledContainerAlpha),
            scheme.secondaryContainer,
            enabledFraction,
        ),
        content = crossfade(scheme.onSecondaryContainer, enabledFraction),
    )
}

/**
 * The pill glyphs' ink: the same crossfade on `onSurface`, because those glyphs sit on the pill's
 * own `surfaceContainerLow`, not on a tonal container.
 */
@Composable
internal fun rememberGlyphInk(enabled: Boolean, motion: MotionSpec): Color {
    val enabledFraction = rememberEnabledFraction(enabled = enabled, motion = motion)
    return crossfade(MaterialTheme.colorScheme.onSurface, enabledFraction)
}

/** The enablement crossfade as a fraction: 1 = enabled, 0 = disabled (EC-26: snaps when collapsed). */
@Composable
private fun rememberEnabledFraction(enabled: Boolean, motion: MotionSpec): Float {
    val enabledFraction by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = animateOrSnap(motion = motion, durationMillis = motion.enabledSpringMillis),
        label = "chrome-action-enabled",
    )
    return enabledFraction
}

/** [role] at full strength when enabled, at [DisabledContentAlpha] when not. */
private fun crossfade(role: Color, enabledFraction: Float): Color =
    lerp(role.copy(alpha = DisabledContentAlpha), role, enabledFraction)

/** The shared interactive box of the pill's glyphs: 48dp target, role, name, disabled semantics. */
@Composable
private fun ActionTarget(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(TouchTargetSize)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * The glyph both controls draw: decorative (`contentDescription = null`) at the requested size.
 *
 * `requiredSize` rather than `size`: the tile's Material 3 container is a 40dp box that propagates
 * its minimum constraint to the content, and `size()` is `enforceIncoming` — it would coerce the
 * 24dp glyph up to 40dp. `requiredSize` states the glyph size independent of the host's minimum.
 */
@Composable
private fun ChromeGlyph(imageVector: ImageVector, tint: Color, size: Dp) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.requiredSize(size),
    )
}

/** Reads the animator scale once per composition (the TASK-11 seam is the only reader). */
@Composable
internal fun rememberMotionSpec(): MotionSpec {
    val context = LocalContext.current
    return remember(context) { provideMotion(context) }
}

/**
 * `MotionSpec.collapsed` means "jump to the end state", never "animate in 0 ms" — so the reduced
 * motion branch resolves to [snap] instead of a zero-length tween.
 */
internal fun <T> animateOrSnap(motion: MotionSpec, durationMillis: Int): AnimationSpec<T> =
    if (motion.collapsed) snap() else tween(durationMillis = durationMillis)

/**
 * The pill glyph's touch target: NFR-04's 48dp floor. The glyph is centred inside this box, so the
 * hit area is never the shape the user sees. The tonal tile gets the same floor from
 * `FilledTonalIconButton`'s `minimumInteractiveComponentSize()`.
 */
private val TouchTargetSize = 48.dp

/** Corner radius of the rounded square drawn on M3's 40dp box — Keep's tile proportion. */
private val TileShape = RoundedCornerShape(14.dp)

/** Glyph size inside the rounded-square tile: the 24dp Material icon scale. */
private val TileGlyphSize = 24.dp

/** Glyph size inside the bottom pill: full-size Material glyph, which is what Keep's toolbar uses. */
private val PillGlyphSize = 24.dp

/** Disabled container: the same tint at 45 % over the canvas, never a grey veil. */
private const val DisabledContainerAlpha = 0.45f

/** M3 disabled content: the ink at 38 % (the token the animated crossfade targets). */
private const val DisabledContentAlpha = 0.38f

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** Both variants, each enabled and disabled, which is where the crossfade lands. */
@Preview(name = "Chrome actions — light", showBackground = true)
@Preview(name = "Chrome actions — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChromeActionsPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row {
                TonalActionButton(
                    labelRes = R.string.undo,
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    enabled = true,
                    onClick = {},
                )
                TonalActionButton(
                    labelRes = R.string.redo,
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    enabled = false,
                    onClick = {},
                )
                Spacer(modifier = Modifier.width(16.dp))
                GlyphActionButton(
                    labelRes = R.string.copy,
                    imageVector = Icons.Filled.ContentCopy,
                    enabled = true,
                    onClick = {},
                )
            }
        }
    }
}
