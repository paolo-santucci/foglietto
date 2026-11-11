package com.paolosantucci.foglietto.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier A unit suite for [EditorMetrics] — the only headless seam [NoteEditorField] owns
 * (plan TASK-20, FR-17).
 *
 * Pins the one thing a screenshot cannot: the editor line height is exactly 1.4× the font size at
 * every canon size slot, matching canon §Typography ("preserve line-height 1.4 verbatim") and the
 * tracked iOS rendering. `lineHeightFor` is the only place that multiple is computed —
 * `EditorTextStyle` deliberately carries no `lineHeight` of its own — so this ratio is the whole
 * FR-17 proof.
 *
 * The former `shouldScrollToTop` assertions were removed with the seam: the value-based
 * `BasicTextField` exposes no scroll control on this Compose version, so a requester on the field
 * could not scroll anything (FR-29's scroll half is unverified — see the task report).
 *
 * Plain JVM: no Robolectric, no Compose runtime, no UI host.
 */
class EditorMetricsTest {

    @Test
    // The plan's bullet spells this "1.4 times the font size"; a `.` is illegal in a function name
    // on the Android compilation ("Name contains illegal characters: ."), so the same assertion is
    // pinned under a JVM-legal name.
    fun `line height is 1_4 times the font size`() {
        assertEquals(14f * 1.4f, lineHeightFor(fontSizePx = 14f), 1e-4f)
        assertEquals(39.2f, lineHeightFor(fontSizePx = 28f), 1e-4f)
    }

    @Test
    fun `the line height multiple holds at every size, not only the canon default`() {
        assertEquals(1.4f, lineHeightFor(fontSizePx = 10f) / 10f, 1e-4f)
        assertEquals(1.4f, lineHeightFor(fontSizePx = 56f) / 56f, 1e-4f)
    }
}
