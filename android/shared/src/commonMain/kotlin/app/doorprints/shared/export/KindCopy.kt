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

package app.doorprints.shared.export

import app.doorprints.shared.model.KindCapabilities
import app.doorprints.shared.model.DevKinds
import app.doorprints.shared.model.KindExtra
import app.doorprints.shared.model.KindFields
import app.doorprints.shared.model.KindSettingRules
import app.doorprints.shared.model.KindValue
import app.doorprints.shared.model.KindValues
import app.doorprints.shared.model.KindWords

/**
 * A candidate's typed extras in the copies (kinds PR 6, S4b-BL-210; docs/03 ADR-36 §18.10), through its kind, never a
 * branch per kind, the twin of the website's `export/kind-copy.ts`: each extra the kind has no `was` for, labelled with
 * the kind's Android key in the copy's language ([KindWords]) and written by its type (money as rupees in a readable
 * copy and a number in a table, a switch as the copy's yes or no, an option by its label, a number's digits, a text as
 * typed). A candidate is read as the screens draw it: its own kind only while [ExportOptions.devKinds] allows it, else
 * the house, which has no such extra, so a house's copies are as they were (`HouseCopyPinsTest`); a school's are pinned
 * by `docs/schemas/kinds/kind-search-vectors.json` (`KindCopyVectorsTest`).
 */
object KindCopy {
    /**
     * One extra of a candidate in a copy: its column (`<kind>.<extra id>`), its label, its table cell and its readable text,
     * and the kind [section] it belongs to (`details` or `cost`), under whose heading a readable copy writes it (S4b-BL-266:
     * a school's fees under *Fees*).
     */
    data class Extra(val key: String, val label: String, val cell: Cell, val text: String, val section: String = "details")

    /** One column of the Houses table for an extra: every extra of each kind the copy has a candidate of. */
    data class Column(val key: String, val label: String)

    private fun fieldsOf(h: ExportHouse, options: ExportOptions): KindFields = DevKinds.of(h.kind, options.devKinds).fields

    private fun labelOf(fields: KindFields, extra: KindExtra, options: ExportOptions): String =
        fields.label(extra).let { KindWords.of(it, options.language) ?: it }

    /** The extras [h] holds, in its kind's order. */
    fun extrasOf(h: ExportHouse, options: ExportOptions): List<Extra> {
        val fields = fieldsOf(h, options)
        val s = ExportStrings.of(options.language)
        return KindValues.of(fields, h.extrasOther).map { v ->
            val cell = cellOf(v, options, s)
            val text = if (cell is Cell.Money) ExportRows.rupees(cell.amount) else ExportRows.plain(cell, options)
            Extra("${fields.kind}.${v.field.id}", labelOf(fields, v.field, options), cell, text, v.field.section)
        }
    }

    /** The columns: each kind in the order its first candidate comes in the copy, each with all its other extras. */
    fun columnsOf(houses: List<ExportHouse>, options: ExportOptions): List<Column> =
        houses.map { fieldsOf(it, options) }.distinctBy { it.kind }
            .flatMap { fields -> fields.others().map { Column("${fields.kind}.${it.id}", labelOf(fields, it, options)) } }

    private fun cellOf(v: KindValue, options: ExportOptions, s: ExportStrings): Cell = when (v) {
        is KindValue.Money -> Cell.Money(v.amount)
        is KindValue.Count -> Cell.Count(v.value)
        is KindValue.Bool -> Cell.Text(s[if (v.value) "yes" else "no"])
        is KindValue.Option -> Cell.Text(KindWords.of(v.label, options.language) ?: v.label)
        is KindValue.Text -> Cell.Text(v.value)
    }

    /** The kind's fields a candidate is read with (the house while the developer flag is off), also for the screens' copy words. */
    internal fun fieldsOfKind(kind: String?, options: ExportOptions): KindFields = DevKinds.of(kind, options.devKinds).fields

    /**
     * The kind a copy speaks in (S4b-BL-266; the twin of the website's `copyVoice`): that of a copy of one space, or of
     * candidates that are all of one kind, when that kind is not the house; null for a copy of houses or of several
     * kinds, which is written as it always was (`HouseCopyPinsTest`). Its words, columns, rows and sections shape the copy.
     */
    fun voice(houses: List<ExportHouse>, options: ExportOptions): KindFields? {
        val own = options.space?.let { fieldsOfKind(it.kind, options) }
        val all = houses.map { fieldsOf(it, options) }.distinctBy { it.kind }
        val voice = own ?: all.singleOrNull()
        return voice?.takeIf { !KindCapabilities.of(it.kind).baseline }
    }
}
