package com.paolosantucci.foglietto.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import com.paolosantucci.foglietto.AppContainer
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.state.ClearController
import com.paolosantucci.foglietto.state.EditorHistory
import com.paolosantucci.foglietto.state.EditorStateHolder
import com.paolosantucci.foglietto.ui.about.AboutSheet
import com.paolosantucci.foglietto.ui.about.AboutSheetModel
import com.paolosantucci.foglietto.ui.chrome.EditorActionRow
import com.paolosantucci.foglietto.ui.chrome.TopActionBar
import com.paolosantucci.foglietto.ui.clipboard.AndroidClipboardGateway
import com.paolosantucci.foglietto.ui.clipboard.ClipboardGateway
import com.paolosantucci.foglietto.ui.clipboard.EmptyClipboardGateway
import com.paolosantucci.foglietto.ui.editor.NoteEditorField
import com.paolosantucci.foglietto.ui.recovery.MenuPopoverContent
import com.paolosantucci.foglietto.ui.recovery.RecoverySheetModel
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme
import kotlinx.coroutines.launch

/*
 * The app shell — the manifest's one activity, the one place that assembles the chrome over the
 * app-scoped holders (FR-20, FR-21, FR-32, FR-18).
 *
 * The layout is the current Google Keep note editor (Material 3 Expressive), not a generic Material
 * app bar:
 *
 * ```
 *                                                          (Share)   ← title-less top bar
 *   editor (full-bleed, the screen)
 *   ╭──────────────────────────────────────────────────────────────╮
 *   │ (Copy)(Paste)(Outdent)(Indent)(Clear)      (Undo)(Redo)(⋮)     │   ← one floating bar
 *   ╰──────────────────────────────────────────────────────────────╯
 * ```
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Components 1 "App shell" (mobile adaptation): the editor is the entire screen and the chrome
 *    is minimal — a title-less `Scaffold` + `TopAppBar`, so the bar occupies one compact band and
 *    the `NoteEditorField` keeps the rest (canon §Design ethos, "content-is-everything").
 *  - §Components 8 "Timed notification toast": non-blocking, dismissible, timed. The *bottom*
 *    placement is Material's `Snackbar`; the canon's top-centre toast has no M3 equivalent and
 *    FR-32 pins a Snackbar — see the CANON GAP note on [FogliettoApp].
 *  - §Accessibility / §Touch-target note: every icon-only control is labelled by a string resource
 *    ("Share", "Copy", "Paste", "Undo", "Redo", "Open menu", …), never by a Kotlin literal, and
 *    carries its own ≥48dp target — see [TonalActionButton] and [GlyphActionButton].
 *  - §Iconography: `open-menu-symbolic` → `more_vert` (the ⋮ overflow).
 *  - §Motion: stock Material 3 menu/popover, sheet and Snackbar motion; the shell adds no animation
 *    of its own (the buttons' enablement crossfade stays in [TonalActionButton]).
 *
 * CANON GAP: the canon's shell is a floating, auto-hiding chrome cluster over the editor with no
 * top app bar and no Share cluster; FR-20 pins a Material 3 `Scaffold` + `TopAppBar` instead, and
 * FR-32 pins a Snackbar (canon §Components 8 places its toast top-centre). Both are spec
 * decisions, flagged for the canon owner rather than invented here.
 *
 * CANON GAP: the canon publishes no bottom action row. The floating bar (one `Surface`, two
 * clusters) follows the current Google Keep reference the product owner supplied — its Material 3
 * Expressive toolbar — and is pinned by `ActionRowChromeTest` rather than derived from a canon
 * table. The rounded-square top-bar container and the ⋮ popover's card are the same reference's
 * shapes, one deliberate departure from the canon's circular chrome.
 */

/**
 * The Clear notification's localized copy (FR-32): the message and the label of its one action.
 *
 * @property messageRes `editor_cleared`.
 * @property actionLabelRes `undo` — the same word the action bar renders.
 */
data class ClearSnackbar(
    @StringRes val messageRes: Int,
    @StringRes val actionLabelRes: Int,
)

/** The single Clear notification definition; the shell resolves it, never a literal. */
val clearSnackbar: ClearSnackbar =
    ClearSnackbar(messageRes = R.string.editor_cleared, actionLabelRes = R.string.undo)

/**
 * The production binding of the shell: it resolves the Clear notification, builds the Clear adapter
 * over the process graph, binds the clipboard seam and hands the shell plain values and callbacks.
 *
 * @param container the process-scoped graph. Read only — the shell constructs the Clear adapter
 *   (which needs the save coordinator) but never drives save/reset itself (FR-14).
 * @param modifier the host's slot, applied to the `Scaffold` exactly once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FogliettoApp(container: AppContainer, modifier: Modifier = Modifier) {
    val editor = container.editor
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Resolved once per locale/config change, not per keystroke.
    val clearedMessage = stringResource(clearSnackbar.messageRes)
    val undoLabel = stringResource(clearSnackbar.actionLabelRes)

    // A platform service, not graph state: it carries no app data, so it is built here rather than
    // in AppContainer. `remember(context)` keeps one instance across recompositions; the gateway
    // itself retains only the application context, so a configuration change leaks nothing.
    val clipboard = remember(context) { AndroidClipboardGateway(context) }

    // Clear = save → empty + reset Undo/Redo → notify (FR-32). The Snackbar's Undo is the one-shot
    // restore the controller hands over; the bar's Undo is deliberately empty after a Clear.
    val clearController = remember(editor, snackbarHostState, clearedMessage, undoLabel) {
        ClearController(
            editor = editor,
            coordinator = container.coordinator,
            onCleared = { restore ->
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = clearedMessage,
                        actionLabel = undoLabel,
                        // Canon §Components 8 is a ~3s timed toast; M3's `Short` (4s) is the closest
                        // stock duration and stays non-blocking. Missing the action is safe by
                        // design: the text is already in the recovery store (FR-32).
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) restore()
                }
            },
        )
    }

    FogliettoShell(
        editor = editor,
        clipboard = clipboard,
        onShare = { text -> container.share.share(text) },
        snackbarHostState = snackbarHostState,
        onClear = { clearController.clear() },
        modifier = modifier,
        menuContent = { onDismissRequest, onOpenAbout ->
            val model = remember(container.recoveryList) {
                RecoverySheetModel(
                    rowSource = { container.recoveryList.rows },
                    onRefresh = { container.recoveryList.refresh() },
                )
            }
            MenuPopoverContent(
                model = model,
                // Restore first, then close: the holder populates the buffer, so the menu closes on
                // an editor that already holds the note (FR-29).
                onRestore = { row ->
                    container.recoveryList.select(row.path)
                    onDismissRequest()
                },
                onOpenAbout = onOpenAbout,
            )
        },
        sheetContent = { sheet, onDismissRequest ->
            when (sheet) {
                ShellSheet.About -> {
                    val uriHandler = LocalUriHandler.current
                    val aboutModel = remember(context) { AboutSheetModel.fromContext(context) }
                    ModalBottomSheet(
                        onDismissRequest = onDismissRequest,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        AboutSheet(
                            model = aboutModel,
                            onOpenLink = { url -> openLink(uriHandler, url) },
                        )
                    }
                }
            }
        },
    )
}

/**
 * The shell's content, with every process-graph dependency lifted to parameters: it composes from a
 * plain [EditorStateHolder], a [ClipboardGateway] plus callbacks, so it can be previewed (and
 * tested) without an [AppContainer], whose construction needs an Android context and the file
 * system.
 *
 * ## State ownership
 *
 * Every behaviour — buffer, selection, history, enablement, Clear, restore, share, copy and paste —
 * belongs to the passed-in [editor], [clipboard] and [onClear]; this composable owns only which
 * overlay is open and whether the ⋮ popover is showing, both hoisted here so each surface can be
 * composed exclusively while visible. Nothing is persisted: buffer, caret, selection and history
 * never reach `savedInstanceState` (FR-10), and neither does the menu's open flag.
 *
 * The popover body and the sheet body arrive through [menuContent] and [sheetContent] and are
 * composed **only while open** — that is what makes `MenuPopoverContent`'s `LaunchedEffect(Unit)` a
 * true per-open refresh (FR-26), and it keeps a closed menu out of the composition entirely.
 * [menuContent]'s second parameter lets the popover hand over to the About sheet, which is why the
 * shell owns the routing rather than the menu body.
 *
 * ## Insets (FR-18, EC-27)
 *
 * Edge-to-edge is kept (`MainActivity.enableEdgeToEdge`). Each band owns exactly one inset, once:
 *  - the status bar is the `TopAppBar`'s (`TopAppBarDefaults.windowInsets`);
 *  - the bottom is the **floating** bar's (`imePadding()` + `navigationBarsPadding()`, in
 *    [EditorActionRow]), which is why it is hosted as `bottomBar` and not stacked in the content
 *    column: `Scaffold` then offsets the `SnackbarHost` above it, so the "Editor cleared" toast can
 *    never paint over the buttons the user just tapped;
 *  - the horizontal safe-drawing inset is applied once to the editor and once to the bottom band (a
 *    landscape cutout would otherwise clip both);
 *  - the editor's bottom edge comes from the Scaffold's `innerPadding`, whose bottom is the band's
 *    measured height — no second bottom padding, keyboard open or closed.
 *
 * `contentWindowInsets = WindowInsets(0)` is what keeps the Scaffold from adding any insets of its
 * own on top of those owners.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FogliettoShell(
    editor: EditorStateHolder,
    clipboard: ClipboardGateway,
    onShare: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    menuContent: @Composable ColumnScope.(onDismissRequest: () -> Unit, onOpenAbout: () -> Unit) -> Unit =
        { _, _ -> },
    sheetContent: @Composable (sheet: ShellSheet, onDismissRequest: () -> Unit) -> Unit = { _, _ -> },
) {
    var openSheet by remember { mutableStateOf<ShellSheet?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopBar(editor = editor, onShare = onShare)
        },
        bottomBar = {
            ActionDock(
                editor = editor,
                clipboard = clipboard,
                onClear = onClear,
                // The bar owns its bottom/IME edge; the horizontal safe-drawing inset is the
                // shell's to hand it, once (a landscape cutout would clip the bar).
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                ),
                menuOpen = menuOpen,
                onOpenMenu = { menuOpen = true },
                onMenuDismiss = { menuOpen = false },
                menuContent = {
                    menuContent(
                        { menuOpen = false },
                        {
                            menuOpen = false
                            openSheet = ShellSheet.About
                        },
                    )
                },
            )
        },
        snackbarHost = {
            // No padding of its own: `Scaffold` already lifts the host clear of the bottomBar,
            // and the bottomBar already carries the navigation-bar and IME insets.
            SnackbarHost(hostState = snackbarHostState)
        },
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        NoteEditorField(
            holder = editor,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        )
    }

    // Overlays are composed only while open (FR-26); the popover is the bar's, the sheet is here.
    openSheet?.let { sheet ->
        sheetContent(sheet, { openSheet = null })
    }
}

/**
 * The floating bottom bar, reading every enablement flag **itself** (S1): scaffolded as `bottomBar`,
 * this subcomposition — not the editor and not the shell — is the one invalidated when a keystroke
 * flips Clear, or a history change flips Undo/Redo.
 *
 * Copy and Paste resolve here rather than in the bar, so `EditorActionRow` stays a rendering layer
 * and knows nothing about `android.content`. Copy hands over the **whole buffer** (never the
 * selection) exactly as iOS does; Paste asks for the clipboard text and lets
 * `EditorStateHolder.pasteAtCaret` decide — an empty clipboard is its no-op (EC-03).
 */
@Composable
private fun ActionDock(
    editor: EditorStateHolder,
    clipboard: ClipboardGateway,
    onClear: () -> Unit,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onMenuDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    menuContent: @Composable ColumnScope.() -> Unit = {},
) {
    EditorActionRow(
        canClear = editor.canClear,
        canUndo = editor.canUndo,
        canRedo = editor.canRedo,
        onCopy = { clipboard.setText(editor.value.text) },
        onPaste = { clipboard.getText()?.let { text -> editor.pasteAtCaret(text) } },
        onOutdent = { editor.outdent() },
        onIndent = { editor.indent() },
        onClear = onClear,
        onUndo = { editor.undo() },
        onRedo = { editor.redo() },
        menuOpen = menuOpen,
        onOpenMenu = onOpenMenu,
        onMenuDismiss = onMenuDismiss,
        modifier = modifier,
        menuContent = menuContent,
    )
}

/**
 * The top bar: Share alone, right-aligned in the same rounded-square tonal container the chrome
 * uses — the title-less bar leaves the editor as the screen's content (FR-20).
 *
 * Undo, Redo and the ⋮ used to sit here; they now live in the bottom bar, which is where the
 * current reference puts the whole editing surface (see [EditorActionRow]).
 *
 * Enablement is read **here** so a keystroke recomposes the bar and not the editor (FR-20); the
 * actual band is [TopActionBar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    editor: EditorStateHolder,
    onShare: (String) -> Unit,
) {
    TopAppBar(
        // Title-less by contract: the editor is the screen's content, the bar is chrome (FR-20).
        title = {},
        actions = {
            TopActionBar(
                canShare = editor.shareEnabled,
                onShare = {
                    // S9: re-read the predicate at click time — the rendering flag above is the
                    // bar's, the shared rule is the gate on what actually leaves the app (EC-30).
                    if (editor.shareEnabled) onShare(editor.value.text)
                },
            )
        },
    )
}

/**
 * Opens [url], tolerating a device with no handler.
 *
 * `UriHandler.openUri` throws `IllegalArgumentException` when no activity claims the scheme (a
 * stripped-down tablet/TV image, or a device with every browser disabled). An About-sheet link must
 * never take the editor down with it, so the click degrades to a no-op.
 */
internal fun openLink(uriHandler: UriHandler, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (_: IllegalArgumentException) {
        // No handler for this URL: the row is inert, exactly like a link on a page with no browser.
    }
}

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** The whole shell without an `AppContainer`: preview-friendly by construction. */
@Preview(name = "Shell — light", showBackground = true)
@Preview(name = "Shell — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Shell — expanded", widthDp = 840, heightDp = 480, showBackground = true)
@Composable
private fun FogliettoShellPreview() {
    FogliettoTheme {
        FogliettoShell(
            editor = remember { previewEditor() },
            clipboard = EmptyClipboardGateway,
            onShare = {},
            snackbarHostState = remember { SnackbarHostState() },
            onClear = {},
        )
    }
}

/** A holder seeded the way the IME seeds one, so the preview shows real content. */
private fun previewEditor(): EditorStateHolder =
    EditorStateHolder(EditorHistory()).apply {
        onInput(TextFieldValue(PREVIEW_TEXT, TextRange(PREVIEW_TEXT.length)))
    }

private const val PREVIEW_TEXT = "Groceries\n- milk\n- bread"
