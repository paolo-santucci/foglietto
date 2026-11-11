package com.paolosantucci.foglietto.ui.about

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/*
 * The About sheet — a small, read-only identity block plus two link rows (FR-34).
 *
 * Presentation only: the sheet's open/closed state is owned by the shell (TASK-23), which hosts
 * this content in its stock M3 `ModalBottomSheet`. Nothing here reads or writes app state, so the
 * composable keeps no state of its own.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Design ethos ("subtract visual weight"): identity block + one hairline divider + two rows,
 *    no cards, no icons, no version-picker chrome.
 *  - §Accessibility: the rows are full-width `ListItem`s (≥ 48dp targets) and carry their own
 *    text labels, so they need no extra content description; the version line is secondary
 *    metadata (`onSurfaceVariant`), the primary ink stays `onSurface`.
 *  - CANON GAP: the canon has no About-sheet anatomy (it documents `Adw.AboutDialog` only via the
 *    popover menu row at §Mobile adaptation summary). The minimal identity block plus link rows is
 *    the spec's FR-34 shape; flagged for the canon owner rather than invented as canon.
 */

/**
 * The About sheet's data, resolved once so the URLs and the version can be unit-pinned (Tier B)
 * without a Compose runtime.
 *
 * [appName], [author] and [license] are proper nouns — a brand name, a person's name and an SPDX
 * licence identifier — so they are deliberately **not** string resources; the localised copy is the
 * sheet title ([R.string.about]) and the two row labels ([R.string.report_an_issue],
 * [R.string.website]), which the 17-key inventory (TASK-10) already carries.
 */
class AboutSheetModel(
    val versionName: String,
    val issuesUrl: String,
    val websiteUrl: String,
) {

    /** The two link targets in display order: "Report an issue", then "Website". Never localised. */
    val links: List<String> get() = listOf(issuesUrl, websiteUrl)

    companion object {
        /** Product identity — a proper noun, not translatable copy. */
        const val APP_NAME = "Foglietto"

        /** Maintainer credit (FR-34), shared verbatim with the iOS About menu. */
        const val AUTHOR = "Paolo Santucci"

        /** SPDX identifier (FR-34), shared verbatim with the iOS About menu. */
        const val LICENSE = "GPL-3.0"

        /** Canonical issue tracker — byte-identical to `iosApp/.../MenuBubble.swift`. */
        const val ISSUES_URL = "https://github.com/paolo-santucci/foglietto/issues"

        /** Canonical project site — byte-identical to `iosApp/.../MenuBubble.swift`. */
        const val WEBSITE_URL = "https://github.com/paolo-santucci/foglietto"

        /** Builds the model for the identity block; the links keep their canonical defaults. */
        fun from(
            versionName: String,
            issuesUrl: String = ISSUES_URL,
            websiteUrl: String = WEBSITE_URL,
        ): AboutSheetModel = AboutSheetModel(versionName, issuesUrl, websiteUrl)

        /**
         * Resolves the version from the installed package metadata — the value the build declares
         * in `defaultConfig.versionName` and the manifest merger carries into the APK (OQ-04).
         *
         * `BuildConfig` generation is off for this module, so package info is the source of truth;
         * either way no version literal exists in Kotlin.
         */
        fun fromContext(context: Context): AboutSheetModel =
            from(versionName = installedVersionName(context))

        /**
         * `getPackageInfo(String, Int)` is deprecated on API 33+, but the replacement
         * (`PackageInfoFlags`) needs an API-33 branch for a value that is identical on every
         * level; the deprecated overload still serves minSdk 26 through 36.
         */
        @Suppress("DEPRECATION")
        private fun installedVersionName(context: Context): String =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
    }
}

/**
 * The About sheet content: title, identity block, hairline divider, two link rows (FR-34).
 *
 * @param model version and link targets, normally [AboutSheetModel.fromContext].
 * @param onOpenLink invoked with the raw URL of the tapped row, so URL resolution stays testable
 *   and device-free; the host injects `LocalUriHandler.current::openUri` (or `ACTION_VIEW`).
 */
@Composable
fun AboutSheet(
    model: AboutSheetModel,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // A short viewport (or a large font scale) scrolls instead of clipping the last row.
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.about),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
        )
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(text = AboutSheetModel.APP_NAME, style = MaterialTheme.typography.titleMedium)
            Text(
                text = model.versionName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = AboutSheetModel.AUTHOR, style = MaterialTheme.typography.bodyMedium)
            Text(text = AboutSheetModel.LICENSE, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LinkRow(
            label = stringResource(R.string.report_an_issue),
            url = model.issuesUrl,
            onOpenLink = onOpenLink,
        )
        LinkRow(
            label = stringResource(R.string.website),
            url = model.websiteUrl,
            onOpenLink = onOpenLink,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** One link row: the whole 56dp+ row is the tap target, labelled by its visible text. */
@Composable
private fun LinkRow(
    label: String,
    url: String,
    onOpenLink: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = label) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenLink(url) },
    )
}

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

@Preview(name = "About — light", showBackground = true)
@Preview(name = "About — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "About — expanded", widthDp = 840, showBackground = true)
@Composable
private fun AboutSheetPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            // The version is the one `defaultConfig.versionName` declares (OQ-04); a preview needs
            // a literal because package metadata only exists on a device.
            AboutSheet(model = AboutSheetModel.from(versionName = "1.0.0"), onOpenLink = {})
        }
    }
}
