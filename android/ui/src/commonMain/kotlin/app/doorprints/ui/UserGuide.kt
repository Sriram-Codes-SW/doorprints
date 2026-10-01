/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.settings_help
import app.doorprints.ui.res.settings_help_desc
import app.doorprints.ui.res.settings_help_hint
import app.doorprints.ui.res.settings_link_failed
import org.jetbrains.compose.resources.stringResource

/**
 * The user guide (`guide/` in the repository, published by pages.yml; docs/10 S4b-BL-60): English at the site's root,
 * Hindi, Tamil and Telugu under their language code, so the app's Help link opens the guide in the app's language.
 */
object UserGuide {
    const val URL = "https://sriram-codes-sw.github.io/doorprints/"

    /** The languages the guide has beside English, each at `URL<code>/`. */
    val TRANSLATED = listOf("hi", "ta", "te")

    /** The guide's home page in [language] (a language code, as [uiLanguage] gives it); English for any other. */
    fun url(language: String): String = if (language in TRANSLATED) "$URL$language/" else URL
}

/**
 * *Help* in Settings → About on Android and iOS (S4b-BL-60): a line on what the guide is and a link that opens it in
 * [language]; when no app can open it, its address is shown instead, as for the source link ([LegalNoticeBlock]). The
 * link's accessible name starts with its visible label (WCAG 2.5.3) and says it opens the browser.
 */
@Composable
fun HelpLink(openUrl: (String) -> Boolean, language: String = uiLanguage()) {
    var failedUrl by remember { mutableStateOf<String?>(null) }
    Text(stringResource(Res.string.settings_help_hint), style = MaterialTheme.typography.bodySmall)
    val desc = stringResource(Res.string.settings_help_desc)
    TextButton(
        onClick = { failedUrl = UserGuide.url(language).takeUnless(openUrl) },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
    ) { Text(stringResource(Res.string.settings_help)) }
    failedUrl?.let { url ->
        Text(
            stringResource(Res.string.settings_link_failed, url),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
