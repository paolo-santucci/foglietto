package com.paolosantucci.foglietto.ui.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
/**
 * Tier B — the Copy/Paste seam over the real platform service (EC-03, iOS parity).
 *
 * The editor never talks to `ClipboardManager`: it talks to [ClipboardGateway], and this suite is
 * what proves the production gateway is a faithful, crash-free binding of it (Robolectric walks the
 * real `getSystemService` path, so a class-name or nullability mistake fails here rather than on a
 * device).
 *
 * The sdk 26 + 36 matrix stays pinned. Robolectric's API-36 sandbox needs a Java 21 runtime, so on
 * the mandated JDK 17 toolchain only the API-26 leg starts (the module announces the degradation);
 * the full matrix must execute on a JDK 21 host.
 *
 * What is *not* pinned here: the gating rules. Copy is never gated and paste's empty-clipboard no-op
 * lives in `EditorStateHolder.pasteAtCaret`, pinned by `EditorStateHolderTest` — this seam only
 * moves bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ClipboardGatewayTest {

    @Test
    fun `copied text round-trips through the system clipboard`() {
        val gateway = AndroidClipboardGateway(RuntimeEnvironment.getApplication())

        gateway.setText("  hello\nworld \n")

        // Verbatim: the buffer is copied exactly, never trimmed and never reformatted.
        assertEquals("  hello\nworld \n", gateway.getText())
    }

    @Test
    fun `an empty buffer still copies - copy is never gated`() {
        val gateway = AndroidClipboardGateway(RuntimeEnvironment.getApplication())

        gateway.setText("")

        // iOS parity: `UIPasteboard.general.string = viewModel.text` runs on an empty buffer too.
        assertEquals("", gateway.getText())
    }

    @Test
    fun `an empty clipboard reads as null so the paste is a silent no-op`() {
        val gateway = AndroidClipboardGateway(RuntimeEnvironment.getApplication())

        assertNull(gateway.getText())
    }
}
