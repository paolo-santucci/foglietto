package com.paolosantucci.foglietto.ui.chrome

import android.content.Context
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Resolved chrome motion for the current system animator scale (FR-35, NFR-05, EC-26).
 *
 * [collapsed] is a semantic branch, not a duration: consumers must **jump to the end state**
 * when it is `true` (NFR-05 reduced motion), never run a zero-length animation. Durations are
 * only meaningful while `collapsed == false`.
 *
 * [enabledSpringMillis] is the only duration with a production consumer today (the action row's
 * per-button enablement crossfade). [revealMillis]/[hideMillis] were the authored durations of the
 * row's IME choreography; that animation was removed — the IME inset is the row's single mover —
 * so they are unread for now. They stay in the table (and in `MotionTest`) because the spec pins
 * them and a future authored transition (e.g. the restore cross-fade of FR-29/FR-35) would take
 * them from here rather than inventing numbers.
 */
data class MotionSpec(
    val revealMillis: Int,
    val hideMillis: Int,
    val enabledSpringMillis: Int,
    val collapsed: Boolean,
)

/**
 * Maps the system `ANIMATOR_DURATION_SCALE` (0f disabled, 1f default, 0.5f/1.5f/2f… user picks)
 * onto the tuned table. Pure and total: non-positive or non-finite scales collapse the motion;
 * a positive scale multiplies the table and never yields a zero-length transition.
 *
 * Tuned baseline (scale 1f): reveal 200 ms, hide 150 ms, enablement 200 ms.
 * Source: Material 3 standard motion — short enter/exit and the standard effect spring.
 * `ui-design-bible.md` §Motion → §Reveal / hide timings pins the crossfade *policy* and the
 * chrome/toast *dwell* times (4 s steady, 2 s first run, 3 s toast) but publishes no
 * crossfade millisecond table. CANON GAP: the reveal/hide/spring durations above are the
 * Material 3 standard values, not canon-derived; revisit if the canon gains a table.
 */
fun resolveMotion(animatorScale: Float): MotionSpec {
    if (!animatorScale.isFinite() || animatorScale <= 0f) {
        return MotionSpec(
            revealMillis = 0,
            hideMillis = 0,
            enabledSpringMillis = 0,
            collapsed = true,
        )
    }
    return MotionSpec(
        revealMillis = scaled(REVEAL_BASE_MILLIS, animatorScale),
        hideMillis = scaled(HIDE_BASE_MILLIS, animatorScale),
        enabledSpringMillis = scaled(ENABLED_SPRING_BASE_MILLIS, animatorScale),
        collapsed = false,
    )
}

/**
 * The single seam that reads `Settings.Global.ANIMATOR_DURATION_SCALE` in this module (FR-35).
 *
 * A missing setting means "animations enabled" (Android's own default), so it resolves at 1f.
 */
fun provideMotion(context: Context): MotionSpec =
    resolveMotion(
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            DEFAULT_ANIMATOR_SCALE,
        ),
    )

/** Reveal duration at scale 1f (M3 short enter). */
private const val REVEAL_BASE_MILLIS = 200

/** Hide duration at scale 1f (M3 short exit). */
private const val HIDE_BASE_MILLIS = 150

/** Enablement spring duration at scale 1f (M3 standard effect spring). */
private const val ENABLED_SPRING_BASE_MILLIS = 200

/** Android's own default for `ANIMATOR_DURATION_SCALE` when the setting is absent. */
private const val DEFAULT_ANIMATOR_SCALE = 1f

/**
 * A positive scale must never collapse into a zero-length animation: [MotionSpec.collapsed]
 * alone owns the "jump to the end state" meaning.
 */
private fun scaled(baseMillis: Int, factor: Float): Int =
    (baseMillis * factor).roundToInt().coerceAtLeast(1)
