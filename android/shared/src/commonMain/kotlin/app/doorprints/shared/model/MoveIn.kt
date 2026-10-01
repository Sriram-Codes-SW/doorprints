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

package app.doorprints.shared.model

import app.doorprints.shared.records.RecordRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One item of the move-in checklist (docs/11 5.24, slice 5), in the format's key order `id, text, done, sort`; `done`
 * is written only when true. The TypeScript twin is `MoveInItem` in `web/src/app/core/models.ts`.
 */
@Serializable
data class MoveInItem(
    /** `[A-Za-z0-9._-]{1,64}` ([RecordRules.isValidId]), unique within the house; the defaults' ids are fixed (`mi_*`). */
    val id: String,
    /** 1..[MoveIn.MAX_TEXT] characters: the person's own words once added (a default is never re-translated). */
    val text: String = "",
    /** True when ticked; absent (null) otherwise, never `false` in a file. */
    val done: Boolean? = null,
    /** The order shown (0 upwards). */
    val sort: Int = 0,
) {
    val isDone: Boolean get() = done == true
}

/**
 * Moving in (docs/11 5.24, slice 5): nested in a TAKEN house as `moveIn`, right after `answers`, on the wire, in
 * `data.json` and (as JSON text in `houses.moveIn`, Room 9) in Room. `date` (epoch ms > 0), `notes` (at most
 * [MAX_NOTES]) and `items` (at most [MAX]) are each optional, and the object is absent when all three are.
 */
@Serializable
data class MoveIn(
    val date: Long? = null,
    val notes: String? = null,
    val items: List<MoveInItem>? = null,
) {
    /** True when every value is in range: what a backup's check and the server demand (a bad file is refused whole). */
    val isValid: Boolean
        get() {
            val list = items.orEmpty()
            return (date == null || date > 0) && (notes?.length ?: 0) <= MAX_NOTES && list.size <= MAX &&
                list.all { RecordRules.isValidId(it.id) && it.text.isNotBlank() && it.text.length <= MAX_TEXT && it.sort >= 0 } &&
                list.map { it.id }.toSet().size == list.size
        }

    /** How many items are ticked, and how many there are (the card's summary and the AI line). */
    val progress: Pair<Int, Int> get() = items.orEmpty().count { it.isDone } to items.orEmpty().size

    /** A default move-in item: its fixed id, its sort and its text by language (en, hi, ta, te). */
    data class Default(val id: String, val sort: Int, val text: Map<String, String>) {
        fun textIn(language: String?): String = text[language] ?: text.getValue("en")
    }

    @OptIn(ExperimentalUuidApi::class)
    companion object {
        const val MAX = 30
        const val MAX_TEXT = 200
        const val MAX_NOTES = 2000

        /** Past this a sort reads as 0, as the web's `whole(sort, 0, 1_000_000)` does. */
        private const val MAX_SORT = 1_000_000

        /** The languages of [DEFAULTS]' texts, in `default-movein.json`'s order. */
        val LANGUAGES = listOf("en", "hi", "ta", "te")

        /**
         * The six items of `docs/schemas/default-movein.json` (format `doorprints-default-movein/1`), embedded so the
         * app needs no file at run time; `DefaultMoveInFileTest` reads the file and compares, id for id and text for
         * text. hi, ta and te are under review.
         */
        val DEFAULTS: List<Default> = listOf(
            d(
                "mi_agreement", 0, "Rental agreement signed and registered",
                "किराया अनुबंध पर हस्ताक्षर और पंजीकरण हो गया",
                "வாடகை ஒப்பந்தம் கையெழுத்திடப்பட்டு பதிவு செய்யப்பட்டது",
                "అద్దె ఒప్పందం సంతకం చేసి నమోదు చేయబడింది",
            ),
            d(
                "mi_police", 1, "Police verification done",
                "पुलिस सत्यापन हो गया",
                "காவல்துறை சரிபார்ப்பு முடிந்தது",
                "పోలీసు ధృవీకరణ పూర్తయింది",
            ),
            d(
                "mi_id", 2, "ID copies exchanged",
                "पहचान पत्र की प्रतियां आपस में दी गईं",
                "அடையாள அட்டை நகல்கள் பரிமாறப்பட்டன",
                "గుర్తింపు కార్డు కాపీలు మార్చుకున్నారు",
            ),
            d(
                "mi_deposit", 3, "Deposit receipt received",
                "जमा राशि की रसीद मिली",
                "வைப்புத் தொகை ரசீது பெறப்பட்டது",
                "డిపాజిట్ రసీదు అందింది",
            ),
            d(
                "mi_meters", 4, "Meter readings noted (electricity, water, gas)",
                "मीटर रीडिंग नोट की (बिजली, पानी, गैस)",
                "மீட்டர் அளவீடுகள் குறிக்கப்பட்டன (மின்சாரம், தண்ணீர், எரிவாயு)",
                "మీటర్ రీడింగ్‌లు నమోదు చేశాను (విద్యుత్, నీరు, గ్యాస్)",
            ),
            d(
                "mi_keys", 5, "Keys received",
                "चाबियां मिल गईं",
                "சாவிகள் பெறப்பட்டன",
                "తాళాలు అందాయి",
            ),
        )

        private fun d(id: String, sort: Int, en: String, hi: String, ta: String, te: String) =
            Default(id, sort, mapOf("en" to en, "hi" to hi, "ta" to ta, "te" to te))

        /**
         * What a reader keeps (a file, the server, another device, the form's save), like the web's `cleanMoveIn`: the
         * date when it is > 0, the notes cut at [MAX_NOTES] (none when blank), the items with a bad id skipped, a
         * repeated id keeping the first usable one, a blank or over-long text skipped, `done` only when true and a bad
         * sort 0; then by sort and id and only then the first [MAX]. Null when it has no date, no notes and no items.
         */
        fun coerced(moveIn: MoveIn?): MoveIn? {
            if (moveIn == null) return null
            val date = moveIn.date?.takeIf { it > 0 }
            val notes = moveIn.notes?.takeIf { it.isNotBlank() }?.take(MAX_NOTES)
            val seen = HashSet<String>()
            val items = moveIn.items.orEmpty().asSequence()
                .filter { RecordRules.isValidId(it.id) && it.text.isNotBlank() && it.text.length <= MAX_TEXT && seen.add(it.id) }
                .map { MoveInItem(it.id, it.text, if (it.isDone) true else null, if (it.sort in 0..MAX_SORT) it.sort else 0) }
                .sortedWith(ORDER)
                .take(MAX)
                .toList()
            if (date == null && notes == null && items.isEmpty()) return null
            return MoveIn(date, notes, items.takeIf { it.isNotEmpty() })
        }

        /** The order shown: sort, then id. */
        val ORDER: Comparator<MoveInItem> = compareBy<MoveInItem> { it.sort }.thenBy { it.id }

        fun ordered(items: List<MoveInItem>?): List<MoveInItem> = items.orEmpty().sortedWith(ORDER)

        /** The next item's sort: one past the largest, 0 for the first. */
        fun nextSort(items: List<MoveInItem>?): Int = (items.orEmpty().maxOfOrNull { it.sort } ?: -1) + 1

        /**
         * *Start moving in* (M4, M5): [existing] and then each of [DEFAULTS] whose id is not there yet, in the file's
         * order, in [language] (anything but hi, ta or te is English), each at the next sort, until there are [MAX].
         * A second call adds nothing.
         */
        fun addDefaults(existing: List<MoveInItem>?, language: String?): List<MoveInItem> {
            val out = existing.orEmpty().toMutableList()
            val have = out.mapTo(HashSet()) { it.id }
            var sort = nextSort(out)
            for (d in DEFAULTS) {
                if (out.size >= MAX) break
                if (d.id in have) continue
                out += MoveInItem(id = d.id, text = d.textIn(language), sort = sort++)
            }
            return out
        }

        /** True when *Start moving in* would add something. */
        fun canAddDefaults(existing: List<MoveInItem>?): Boolean {
            val have = existing.orEmpty().mapTo(HashSet()) { it.id }
            return existing.orEmpty().size < MAX && DEFAULTS.any { it.id !in have }
        }

        /** One of the person's own items at the end (*Add your own*), or null at [MAX] or for a blank text. */
        fun add(existing: List<MoveInItem>?, text: String, newId: () -> String = { "mi_" + Uuid.random().toHexString().take(8) }): List<MoveInItem>? {
            val words = text.trim().take(MAX_TEXT)
            if (words.isEmpty() || existing.orEmpty().size >= MAX) return null
            return existing.orEmpty() + MoveInItem(id = newId(), text = words, sort = nextSort(existing))
        }

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        /** The move-in as compact JSON text in the format's key order, or null for none: `houses.moveIn` and the form's draft. */
        fun encode(moveIn: MoveIn?): String? = moveIn?.let { json.encodeToString(serializer(), it) }

        /** [encode]'s text back; null for none or for text that does not decode (never written here). */
        fun decode(text: String?): MoveIn? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
