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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.doorprints.shared.model.UserKinds
import org.jetbrains.compose.resources.StringResource

/**
 * A word a kind label names (docs/03 ADR-39): one of the app's Compose strings, in the app's language, or a person's own words
 * for a kind they added ([UserKinds]), shown as written in every language and always as text (a Compose `Text`, never markup,
 * never a link). A screen reads either with [stringResource] (or [getString] outside composition), as it reads a string.
 */
@Immutable
sealed interface UiWord {
    /** One of the app's Compose strings. */
    data class Res(val res: StringResource) : UiWord

    /** A person's own words, as written. */
    data class Own(val text: String) : UiWord
}

/** What names [this] word: the Compose string's name, or the person's own words as they are (the tests and the logs read it). */
val UiWord.key: String
    get() = when (this) {
        is UiWord.Res -> res.key
        is UiWord.Own -> text
    }

/** The word a kind label's [key] names: a person's own words for a `user:` key ([UserKinds.isTextKey]), else the Compose string of that name. */
fun kindWord(key: String): UiWord =
    if (UserKinds.isTextKey(key)) UiWord.Own(UserKinds.text(key).orEmpty()) else UiWord.Res(kindStringResource(key))

/** The text of [word] in the app's language; a person's own words are shown as they are. */
@Composable
fun stringResource(word: UiWord): String = when (word) {
    is UiWord.Res -> org.jetbrains.compose.resources.stringResource(word.res)
    is UiWord.Own -> word.text
}

/**
 * The text of [word] with [args] for the app's strings that are templates (*Visits (%1$d)*); a person's own words take no
 * arguments and are shown as they are.
 */
@Composable
fun stringResource(word: UiWord, vararg args: Any): String = when (word) {
    is UiWord.Res -> org.jetbrains.compose.resources.stringResource(word.res, *args)
    is UiWord.Own -> word.text
}

/** [stringResource] outside composition. */
suspend fun getString(word: UiWord): String = when (word) {
    is UiWord.Res -> org.jetbrains.compose.resources.getString(word.res)
    is UiWord.Own -> word.text
}
