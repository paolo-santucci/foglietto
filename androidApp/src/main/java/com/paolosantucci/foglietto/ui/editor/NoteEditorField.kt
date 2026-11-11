package com.paolosantucci.foglietto.ui.editor

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.state.EditorHistory
import com.paolosantucci.foglietto.state.EditorStateHolder
import com.paolosantucci.foglietto.ui.theme.EditorFontSize
import com.paolosantucci.foglietto.ui.theme.EditorTextStyle
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/*
 * The editor surface: a full-bleed, borderless multiline `BasicTextField` over the hoisted
 * `EditorStateHolder` (canon §Components 3 "Editor text view", mobile adaptation).
 *
 * The field renders; it owns no editor state. Text, selection, undo/redo granularity, Enter
 * continuation and indent/outdent all resolve in the holder — this composable is a binding layer,
 * so the displayed value is always `holder.value` and the only write path back is
 * `holder.onInput` (which programmatic holder writes never re-enter). That is FR-02's single
 * source of truth at the UI boundary.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Components 3: full-bleed, borderless, soft-wrapping multiline text view; no search bar, no
 *    accessory bar, no Find affordance (FR-19, FR-23..FR-25) and no in-app Copy/Paste.
 *  - §Typography: `FontFamily.Default` (the platform/user font, never a bundled family) at the
 *    system font scale with line-height 1.4 — the multiple comes from [lineHeightFor], which is
 *    the only place the 1.4 is computed, so FR-17 is unit-pinned rather than restated here.
 *  - §Spacing & layout / §Components 3: the static text-view margin (10) is the default
 *    [contentPadding]. It is a **content margin**, not a system-bar inset, so the host replaces it
 *    wholesale.
 *  - §Scheme matrix: editor canvas = the scheme surface (a transparent field over the Scaffold's
 *    surface), editor ink = `onSurface`, caret = the platform accent (`primary`).
 *
 * CANON GAP: the canon's `MARGIN_BELOW_CURSOR` (22px) and the *dynamic* vertical-margin
 * interpolation (10 → 36 by viewport width) are desktop-window behaviours with no mobile token.
 * This wave keeps the canon's narrow-viewport minimum margin (10) and leaves the space under the
 * caret to the framework's caret-follow scrolling; re-open with the canon owner if the
 * interpolation is wanted on phones too.
 *
 * Insets are the shell's (FR-18): the host applies the Scaffold's `innerPadding` (whose bottom is
 * the action row's height, so the editor ends where the row begins, keyboard open or closed) and
 * the horizontal safe-drawing inset through [modifier] — **once**. The field never applies a
 * system-bar or IME inset itself, so there is no path to double bottom padding.
 */

/**
 * Full-bleed, borderless editor surface bound to [holder].
 *
 * ## Restore (FR-29, partial)
 *
 * `EditorStateHolder.populate` writes text and caret to offset 0 in one [TextFieldValue], so the
 * buffer *is* re-anchored at line one. Scrolling the viewport there is left to the field's own
 * caret-follow, which is the only mechanism the value-based `BasicTextField` offers on this Compose
 * version — it exposes no scroll control, and a `BringIntoViewRequester` on the field itself was a
 * no-op (the field is always fully visible, so there is nothing to bring into view). That seam has
 * been removed rather than left reading as implemented; FR-29's scroll half is **unverified**
 * (see the task report). `EditorStateHolder.scrollSignal` remains the holder-side event the real
 * implementation will consume.
 *
 * @param holder the editor's single source of truth; the field reads [EditorStateHolder.value] and
 *   writes only through [EditorStateHolder.onInput].
 * @param modifier the host's slot for size and *system* insets. The shell passes
 *   `Modifier.fillMaxSize()` (or a `weight` inside its column) already padded by the Scaffold's
 *   `innerPadding` plus the horizontal safe-drawing inset; the field then fills the resulting
 *   space and insets its text by [contentPadding] only.
 * @param contentPadding the editor's own margin around the text — canon's static text-view margin
 *   (10) by default. It is **not** a system-bar/IME inset: the host pushes insets through
 *   [modifier] and replaces this only when it wants a different content margin.
 */
@Composable
fun NoteEditorField(
    holder: EditorStateHolder,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
) {
    // The ink is the scheme's onSurface; the line height resolves through the unit-pinned seam,
    // never a second literal (FR-17).
    val ink = MaterialTheme.colorScheme.onSurface
    val cursor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val textStyle = remember(density, ink) {
        EditorTextStyle.copy(
            color = ink,
            lineHeight = with(density) { lineHeightFor(EditorFontSize.toPx()).toSp() },
        )
    }

    BasicTextField(
        value = holder.value,
        onValueChange = holder::onInput,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        textStyle = textStyle,
        cursorBrush = SolidColor(cursor),
        // Auto-correct on and a plain newline IME action: the Enter continuation itself is the
        // holder's shared-rule decision (FR-15), and Android shows no accessory bar (FR-23).
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = true,
            imeAction = ImeAction.Default,
        ),
        maxLines = Int.MAX_VALUE,
    )
}

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** Seeded the way the IME seeds it, so the preview shows real content without a fake state type. */
@Preview(name = "Editor — light", showBackground = true)
@Preview(name = "Editor — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Editor — narrow, large font", widthDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun NoteEditorFieldPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            NoteEditorField(holder = remember { previewEditor() })
        }
    }
}

/** A holder holding the canon's sample list, caret at the end (nothing else in this type is fake). */
private fun previewEditor(): EditorStateHolder =
    EditorStateHolder(EditorHistory()).apply {
        onInput(TextFieldValue(PREVIEW_TEXT, TextRange(PREVIEW_TEXT.length)))
    }

private const val PREVIEW_TEXT = "Groceries\n- milk\n- bread\n- coffee\n\nDraft: an ephemeral note."
