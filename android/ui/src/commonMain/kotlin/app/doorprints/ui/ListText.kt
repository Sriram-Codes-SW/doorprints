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
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * "a", "a and b", "a, b and c", "a, b, c and d", … : two items use [two]; three or more use [three], with everything
 * before the last two folded into its first slot by [middle] ("a, b"). Any number of items: the Export screen's
 * partial-backup note exists to say what a backup leaves out, so a fourth gap must never be dropped (Android review,
 * round 12). Common since CMP-6 (was `ImportWorker.joinList`); `:app`'s import notification builds its own patterns
 * with it from Android resources.
 */
fun joinList(
    items: List<String>,
    two: (String, String) -> String,
    three: (String, String, String) -> String,
    middle: (String, String) -> String,
): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> two(items[0], items[1])
    else -> three(items.subList(0, items.size - 2).reduce(middle), items[items.size - 2], items[items.size - 1])
}

/** [items] in the app language's own list pattern (`import_list_*`), in composition. */
@Composable
fun joinedList(items: List<String>): String {
    val two = stringResource(Res.string.import_list_two)
    val three = stringResource(Res.string.import_list_three)
    val middle = stringResource(Res.string.import_list_middle)
    return joinList(
        items,
        two = { a, b -> formatPositional(two, a, b) },
        three = { a, b, c -> formatPositional(three, a, b, c) },
        middle = { a, b -> formatPositional(middle, a, b) },
    )
}

/** [joinedList] outside composition (the house form's paste summary). */
suspend fun joinedListText(items: List<String>): String {
    val two = getString(Res.string.import_list_two)
    val three = getString(Res.string.import_list_three)
    val middle = getString(Res.string.import_list_middle)
    return joinList(
        items,
        two = { a, b -> formatPositional(two, a, b) },
        three = { a, b, c -> formatPositional(three, a, b, c) },
        middle = { a, b -> formatPositional(middle, a, b) },
    )
}
