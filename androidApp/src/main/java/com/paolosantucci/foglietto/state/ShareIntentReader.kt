package com.paolosantucci.foglietto.state

/**
 * Extracts inbound share text from an Activity intent without touching the platform (FR-22, NFR-03).
 *
 * ## Why this is a pure function
 *
 * The Activity owns everything that is actually platform-bound: it reads the action off the
 * incoming intent and passes `::getCharSequenceExtra` in as [read]'s `getExtra` lambda. This object
 * only maps an action name to the single extra key that carries the payload, so it can be exercised
 * by a plain JVM unit test with no device, no emulator and no Robolectric.
 *
 * ## One probe, never three
 *
 * Only the key belonging to the matching action is ever requested. A `SEND` intent is asked for
 * [EXTRA_TEXT] and nothing else; a `PROCESS_TEXT` intent is asked for [EXTRA_PROCESS_TEXT] and
 * nothing else; every other action — including a `null` action from a plain launcher start — returns
 * `null` without invoking the lambda at all, so the Activity's extra lookup is not probed
 * speculatively.
 *
 * ## Acceptance is not decided here
 *
 * Blank payloads are returned verbatim. Judging acceptance (blank, CRLF rewriting, the one notion
 * of "shareable") is the shared rule's job, so there is a single source of truth for what counts as
 * inbound text (D5). This object neither trims nor validates nor truncates.
 */
object ShareIntentReader {

    /** Intent action for a plain share sheet send. */
    const val ACTION_SEND = "android.intent.action.SEND"

    /** Intent action for a text selection forwarded from another app. */
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** Extra carrying the shared body for [ACTION_SEND]. */
    const val EXTRA_TEXT = "android.intent.extra.TEXT"

    /** Extra carrying the selected text for [ACTION_PROCESS_TEXT]. */
    const val EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT"

    /**
     * Returns the payload carried by [action], or `null` when the action is not inbound text or
     * carries nothing.
     *
     * @param action the incoming intent's action name, `null` when the intent has none.
     * @param getExtra reads one extra by name; supplied by the Activity. Invoked at most once, and
     *   only with the key that belongs to [action].
     */
    fun read(action: String?, getExtra: (String) -> CharSequence?): String? =
        when (action) {
            ACTION_SEND -> getExtra(EXTRA_TEXT)?.toString()
            ACTION_PROCESS_TEXT -> getExtra(EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
}
