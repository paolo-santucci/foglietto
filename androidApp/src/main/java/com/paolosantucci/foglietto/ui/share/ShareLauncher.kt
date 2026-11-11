package com.paolosantucci.foglietto.ui.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.paolosantucci.foglietto.shared.editor.canShare

/**
 * Outbound share seam: the buffer text handed to the platform share sheet (FR-33, EC-30).
 *
 * The payload is `ACTION_SEND` + `text/plain` (OQ-02 assumption) carrying the **raw, untrimmed**
 * buffer text in [Intent.EXTRA_TEXT], wrapped in [Intent.createChooser] so the user always picks
 * the target. `ACTION_SEND` needs no permission and no `<queries>` manifest entry, so the
 * least-privilege invariant (m8) stays intact.
 *
 * **The shareability decision belongs to `shared`.** The transport guard below calls the shared
 * [canShare] predicate — the same function behind `EditorStateHolder.shareEnabled` (TASK-05/TASK-13)
 * — instead of a local `text.isEmpty()` check, so the button's enablement and the payload that
 * actually leaves the app can never disagree about whitespace-only text (EC-30: no local
 * re-derivation).
 *
 * [context] may be an `Activity` or any other `Context`. A non-Activity context cannot start an
 * activity on its own, so the chooser carries [Intent.FLAG_ACTIVITY_NEW_TASK] in exactly that case
 * (and only then — an Activity host stays in the current task).
 */
class ShareLauncher(private val context: Context) {

    private val requiresNewTask: Boolean = context !is Activity

    /** Opens the system share sheet with [text]. No-op when the shared predicate rejects it. */
    fun share(text: String) {
        if (!canShare(text)) return

        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_TEXT
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null)
        if (requiresNewTask) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private companion object {
        const val MIME_TYPE_TEXT = "text/plain"
    }
}
