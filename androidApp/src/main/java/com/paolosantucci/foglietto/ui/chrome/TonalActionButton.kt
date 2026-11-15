package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 *  - [TonalActionButton] — a rounded-square tonal container. This is the top bar's control: Keep's
 *    Material 3 Expressive toolbar puts each action in its own rounded square
 *    (`RoundedCornerShape`, wider than tall) rather than in the circles the previous chrome drew.
 *    The top bar keeps Share alone now; the same container is what every remaining top-bar action
 *    would use.
 *  - [GlyphActionButton] — the glyph alone, no container: the floating bottom bar's controls (Copy,
 *    Paste, Outdent, Indent, Clear, Undo, Redo, ⋮), which Keep draws as bare outlined icons inside
 *    one shared pill surface. *
 * ## Styling
 *
 * The fill and the icon ink come from the scheme's `secondaryContainer`/`onSurface`, so the neutral
 * blue-grey palette in `theme/Color.kt` stays the single source of the colour — never restated at a
 * call site.
 *
 * Every control draws a [TouchTargetSize] 48dp interactive box (NFR-04 asks for the *target*) and
 * centres a smaller visual inside it: [ContainerWidth]×[ContainerHeight] for the tonal container,
 * [ContainerGlyphSize] or [PillGlyphSize] for the glyph. The visible shape is never coerced up to
 * the target — the same separation the circular buttons used to have.
 *
 * ## Enablement animation (FR-35, NFR-05)
 *
 * Disabled is a *lighter* container of the same tint (not a grey veil) and the glyph ink at 38 % —
 * the pair Keep shows for an empty history. The two states are crossfaded by a fraction driven by
 * [MotionSpec]. With `motion.collapsed` (system animator scale 0) the spec resolves to [snap], i.e.
 * the end state immediately, never a zero-length tween.
 *
 * ## Accessibility (NFR-04)
 *
 * Every control is icon-only, so it carries a string-resource `contentDescription` ([labelRes]) and
 * reports `disabled()` when it cannot accept input; the `Icon` underneath is decorative (`null`) to
 * avoid a doubled TalkBack label. The clickable role is [Role.Button].
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
    val colors = rememberChromeActionColors(enabled = enabled, motion = motion)

    ActionTarget(label = label, enabled = enabled, onClick = onClick, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(width = ContainerWidth, height = ContainerHeight)
                .clip(ContainerShape)
                .background(colors.container),
            contentAlignment = Alignment.Center,
        ) {
            ChromeGlyph(imageVector = imageVector, tint = colors.content, size = ContainerGlyphSize)
        }
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
    val colors = rememberChromeActionColors(enabled = enabled, motion = motion)

    ActionTarget(label = label, enabled = enabled, onClick = onClick, modifier = modifier) {
        ChromeGlyph(imageVector = imageVector, tint = colors.content, size = PillGlyphSize)
    }
}

/** The colour pair both controls share, resolved once from the scheme and the motion spec. */
internal data class ChromeActionColors(
    /** The tonal container fill (`TonalActionButton` only; [GlyphActionButton] ignores it). */
    val container: Color,
    /** The glyph ink. */
    val content: Color,
)

/**
 * The one place the chrome's action colours are computed, so the top bar and the bottom bar can
 * never drift apart: enabled is `onSurface` (Keep's near-black glyph), disabled is the same ink at
 * [DisabledContentAlpha] over a container of `secondaryContainer` at [DisabledContainerAlpha].
 */
@Composable
internal fun rememberChromeActionColors(enabled: Boolean, motion: MotionSpec): ChromeActionColors {
    val enabledFraction by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = animateOrSnap(motion = motion, durationMillis = motion.enabledSpringMillis),
        label = "chrome-action-enabled",
    )
    val scheme = MaterialTheme.colorScheme
    return ChromeActionColors(
        container = lerp(
            scheme.secondaryContainer.copy(alpha = DisabledContainerAlpha),
            scheme.secondaryContainer,
            enabledFraction,
        ),
        content = lerp(
            scheme.onSurface.copy(alpha = DisabledContentAlpha),
            scheme.onSurface,
            enabledFraction,
        ),
    )
}

/** The shared interactive box: 48dp target, button role, accessible name, disabled semantics. */
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
                // `clickable(enabled = false)` already drops the click action; this keeps the
                // reason explicit for TalkBack ("dimmed") instead of leaving an unnamed inert box.
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The glyph both controls draw: decorative (`contentDescription = null`) at the requested size. */
@Composable
private fun ChromeGlyph(imageVector: ImageVector, tint: Color, size: Dp) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size),
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
 * The touch target: NFR-04's 48dp floor. Every control below keeps this box and centres a smaller
 * visual inside it, so the hit area is never the shape the user sees.
 */
private val TouchTargetSize = 48.dp

/**
 * The rounded-square container's size — Keep's Expressive toolbar draws it slightly wider than
 * tall (40×30dp), not as a circle and not as a square.
 */
private val ContainerWidth = 40.dp
private val ContainerHeight = 30.dp

/** Corner radius of the rounded square; a third of the height, which is Keep's ratio. */
private val ContainerShape = RoundedCornerShape(10.dp)

/** Glyph size inside the rounded-square container: ~60 % of its height, as Keep's toolbar draws. */
private val ContainerGlyphSize = 18.dp

/** Glyph size inside the bottom pill: full-size Material glyph, which is what Keep's toolbar uses. */
private val PillGlyphSize = 24.dp

/** Disabled container: the same tint at 45 % over the canvas, never a grey veil. */
private const val DisabledContainerAlpha = 0.45f

/** M3 disabled content: `onSurface` at 38 % (the token the animated crossfade targets). */
private const val DisabledContentAlpha = 0.38f

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** The two controls, each enabled and disabled, which is where the crossfade lands. */
@Preview(name = "Chrome actions — light", showBackground = true)
@Preview(name = "Chrome actions — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChromeActionsPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row {
                TonalActionButton(
                    labelRes = R.string.undo,
                    imageVector = Icons.Filled.MoreVert,
                    enabled = true,
                    onClick = {},
                )
                TonalActionButton(
                    labelRes = R.string.clear_editor,
                    imageVector = Icons.Filled.Close,
                    enabled = false,
                    onClick = {},
                )
                Spacer(modifier = Modifier.width(16.dp))
                GlyphActionButton(
                    labelRes = R.string.share,
                    imageVector = Icons.Filled.MoreVert,
                    enabled = true,
                    onClick = {},
                )
            }
        }
    }
}
