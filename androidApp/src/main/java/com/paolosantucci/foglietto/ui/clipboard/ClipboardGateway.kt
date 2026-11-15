package com.paolosantucci.foglietto.ui.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The system clipboard, reduced to what the bottom bar's Copy/Paste need.
 *
 * ## Why a seam instead of a Compose clipboard API
 *
 * This module's Compose version ships both `LocalClipboardManager` and the newer `Clipboard`
 * interface behind `LocalClipboard`. `LocalClipboardManager` is deprecated, so it is not used.
 * `Clipboard` itself is deliberately not used either: its `getClipEntry()` is a *suspending*
 * platform-object hand-off (`ClipEntry`), so every paste would need a coroutine plus a platform
 * unwrap (`nativeClipData`) to reach a plain `String`, and the editor's paste path is a synchronous
 * `EditorStateHolder` mutation with no suspend point in it. The platform `ClipboardManager` below
 * is exactly what Compose's `Clipboard` wraps on Android, so this seam adds a fakeable interface
 * and loses no capability — and it keeps [com.paolosantucci.foglietto.state.EditorStateHolder]
 * platform-free, since only the UI layer ever touches it.
 *
 * The two calls are the whole contract (iOS parity): copy the **whole buffer**, read the
 * clipboard's plain text. Gating belongs to the caller:
 *  - copy is never gated (iOS copies even an empty buffer — the *share* surface is what is gated);
 *  - paste is never gated either; an empty clipboard resolves to `null` and the caller's
 *    `EditorStateHolder.pasteAtCaret` is a no-op for `null`-free empty text (EC-03).
 */
interface ClipboardGateway {

    /** Puts [text] on the system clipboard as `text/plain`. */
    fun setText(text: String)

    /** The clipboard's current text, or `null` when it holds nothing usable. */
    fun getText(): String?
}

/**
 * The production gateway over the platform service (`ClipboardManager` + `ClipData.newPlainText`),
 * with no new dependency.
 *
 * Both calls no-op (or return `null`) when the device exposes no clipboard service at all — a
 * stripped-down image. Copying is never worth a crash in the editor.
 *
 * Reading the clipboard on Android 12+ makes the system show its "pasted from" chip; that is the
 * platform's own affordance and is why Paste stays an explicit tap rather than anything automatic.
 *
 * The clip label is the app's own localized label read off `applicationInfo`, not a Kotlin literal:
 * the inventory has no `app_name` key (`StringInventoryTest` pins the exact key set) and this string
 * is user-visible in the clipboard preview, so it must not be hard-coded copy.
 *
 * @param context any context; only its application context is retained, so an Activity cannot be
 *   leaked by a remembered gateway across a configuration change.
 */
class AndroidClipboardGateway(context: Context) : ClipboardGateway {

    private val appContext: Context = context.applicationContext ?: context

    private val clipboard: ClipboardManager? =
        appContext.getSystemService(ClipboardManager::class.java)

    override fun setText(text: String) {
        val label = appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun getText(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        // `coerceToText` also resolves a clip that arrived as a URI (another app putting a
        // content:// text document on the clipboard) instead of throwing on a null `.text`.
        return clip.getItemAt(0).coerceToText(appContext).toString()
    }
}

/**
 * The clipboard that holds nothing and keeps nothing: the previews' and the shell preview's
 * binding. A preview is a static frame — there is no clipboard to round-trip there, and faking one
 * would show a paste that never happened.
 */
object EmptyClipboardGateway : ClipboardGateway {

    override fun setText(text: String) = Unit

    override fun getText(): String? = null
}
