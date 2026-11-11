package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/**
 * The chrome's one circular tonal action button — used by the top bar (Share) and by both clusters
 * of the bottom row (Outdent, Indent, Clear, Undo, Redo, ⋮).
 *
 * ## Styling
 *
 * A `FilledTonalIconButton` drawing the Material 3 40dp circular container over its own 48dp
 * touch target (NFR-04 asks for the *target*, so no `Modifier.size(48.dp)` here — that would coerce
 * the visible circle up to 48dp instead of only the hit area). The fill and the icon ink come from
 * the scheme's `secondaryContainer`/`onSecondaryContainer`, so the neutral blue-grey palette in
 * `theme/Color.kt` is the single source of the colour — never restated at a call site.
 *
 * ## Enablement animation (FR-35, NFR-05)
 *
 * Disabled is Material's standard pair — `onSurface` at 12% for the container and 38% for the
 * content — and the two states are crossfaded by a fraction driven by [MotionSpec]. With
 * `motion.collapsed` (system animator scale 0) the spec resolves to [snap], i.e. the end state
 * immediately, never a zero-length tween.
 *
 * ## Accessibility (NFR-04)
 *
 * The button is icon-only, so it carries a string-resource `contentDescription` ([labelRes]); the
 * `Icon` underneath is decorative (`null`) to avoid a doubled TalkBack label.
 *
 * @param labelRes the accessible name — always a string resource, never a literal.
 * @param imageVector the Material icon rendered at 24dp, tinted by the (animated) content colour.
 * @param enabled whether the control accepts input; the disabled pair is animated, not abrupt.
 * @param onClick invoked on tap; a disabled button never fires it.
 * @param modifier the host's slot (the top bar uses it for its end padding), applied once.
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
    val enabledFraction by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = animateOrSnap(motion = motion, durationMillis = motion.enabledSpringMillis),
        label = "chrome-action-enabled",
    )
    val containerColor = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContainerAlpha),
        MaterialTheme.colorScheme.secondaryContainer,
        enabledFraction,
    )
    val contentColor = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha),
        MaterialTheme.colorScheme.onSecondaryContainer,
        enabledFraction,
    )

    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = contentColor,
        )
    }
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

/** M3 disabled tonal container: `onSurface` at 12 % (the token the animated crossfade targets). */
private const val DisabledContainerAlpha = 0.12f

/** M3 disabled content: `onSurface` at 38 % (the token the animated crossfade targets). */
private const val DisabledContentAlpha = 0.38f

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** The two states side by side, which is where the enablement crossfade lands. */
@Preview(name = "Tonal action — light", showBackground = true)
@Preview(name = "Tonal action — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TonalActionButtonPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row {
                TonalActionButton(
                    labelRes = R.string.share,
                    imageVector = Icons.Filled.Share,
                    enabled = true,
                    onClick = {},
                )
                TonalActionButton(
                    labelRes = R.string.clear_editor,
                    imageVector = Icons.Filled.Close,
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}
