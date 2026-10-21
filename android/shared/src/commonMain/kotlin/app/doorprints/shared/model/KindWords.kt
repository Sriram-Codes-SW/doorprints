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

/**
 * The words of a kind's typed extras that search and the readable copies need in `:shared` (kinds PR 6, S4b-BL-210;
 * docs/03 §18.10): each Android key of an extra's label and of an enum's options, in en, hi, ta and te. The screens read
 * the same keys as Compose strings in `:ui`, which `:shared` cannot reach (the copies are written here, in the copy's
 * language, which need not be the app's), so this is a copy of those strings, as [app.doorprints.shared.export.ExportStrings]
 * is of the copies' own words. `KindSearchVectorsTest` holds it to `school-vectors.json`'s Android words, and
 * `SchoolKindScreensTest` holds the Compose strings to the same file, so the two cannot drift. A house needs none: its
 * extras are written by hand in search and in every copy. Hindi, Tamil and Telugu are under review.
 */
object KindWords {
    private val LANGUAGES = listOf("en", "hi", "ta", "te")

    private val WORDS: Map<String, List<String>> = mapOf(
        "school_board" to listOf("Board", "बोर्ड", "கல்வி வாரியம்", "బోర్డు"),
        "school_board_CBSE" to listOf("CBSE", "CBSE", "CBSE", "CBSE"),
        "school_board_ICSE" to listOf("ICSE", "ICSE", "ICSE", "ICSE"),
        "school_board_STATE" to listOf("State board", "राज्य बोर्ड", "மாநில வாரியம்", "రాష్ట్ర బోర్డు"),
        "school_board_IB" to listOf("IB", "IB", "IB", "IB"),
        "school_board_IGCSE" to listOf("Cambridge (IGCSE)", "कैम्ब्रिज (IGCSE)", "கேம்பிரிட்ஜ் (IGCSE)", "కేంబ్రిడ్జ్ (IGCSE)"),
        "school_option_OTHER" to listOf("Other", "अन्य", "மற்றவை", "ఇతర"),
        "school_medium" to listOf("Medium of instruction", "पढ़ाई का माध्यम", "பயிற்று மொழி", "బోధనా మాధ్యమం"),
        "school_medium_ENGLISH" to listOf("English", "अंग्रेज़ी", "ஆங்கிலம்", "ఇంగ్లీష్"),
        "school_medium_HINDI" to listOf("Hindi", "हिन्दी", "இந்தி", "హిందీ"),
        "school_medium_REGIONAL" to listOf("State language", "राज्य की भाषा", "மாநில மொழி", "రాష్ట్ర భాష"),
        "school_grades" to listOf("Grades offered", "कक्षाएँ", "வகுப்புகள்", "తరగతులు"),
        "school_commute" to listOf("Commute (minutes)", "आने-जाने का समय (मिनट)", "பயண நேரம் (நிமிடங்கள்)", "ప్రయాణ సమయం (నిమిషాలు)"),
        "school_bus" to listOf("School bus", "स्कूल बस", "பள்ளிப் பேருந்து", "స్కూల్ బస్సు"),
        "school_fees" to listOf("Fees per year (₹)", "सालाना फ़ीस (₹)", "ஆண்டுக் கட்டணம் (₹)", "సంవత్సర ఫీజు (₹)"),
        "school_admission_fee" to listOf("Admission fee (₹)", "दाख़िला फ़ीस (₹)", "சேர்க்கைக் கட்டணம் (₹)", "ప్రవేశ ఫీజు (₹)"),
    )

    /**
     * The words of [key] in [language] (any language outside the four reads as English); null for a key it has not. A key of a
     * person's own words (a kind they added, `user:<id>:<ref>`, docs/03 ADR-39) is their words, as written, in every language.
     */
    fun of(key: String, language: String): String? =
        if (UserKinds.isTextKey(key)) UserKinds.text(key) else WORDS[key]?.get(LANGUAGES.indexOf(language).coerceAtLeast(0))

    /** Every key it has. */
    val keys: Set<String> get() = WORDS.keys
}
