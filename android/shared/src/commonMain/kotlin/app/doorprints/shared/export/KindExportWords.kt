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

/**
 * The words inside a copy that name the candidate, in a copy that speaks in another kind (kinds PR 16 phone half,
 * S4b-BL-266; docs/03 §18.9): the cover and ranking nouns, the headings of the Schools table, the Visits and Photos tables'
 * *School* and *School id* columns, the cover's scope lines, a school's own *Website* and the Cost block's *Fees*. They replace
 * the house's words of [ExportStrings] key for key, so a copy of houses is untouched (`HouseCopyPinsTest`) and
 * [ExportStrings] stays the contract of the keys. The twin of the website's `export/kind-export-words.ts` (the same words,
 * plus `section.cost`, which the website takes from its dictionary's `school.fees`). Hindi, Tamil and Telugu are under review.
 */
internal object KindExportWords {
    private val SCHOOL: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "doc.title" to "Doorprints — my schools",
            "cover.houses" to "Schools",
            "cover.scope" to "Schools included",
            "cover.rejected" to "Rejected schools included",
            "scope.SELECTED" to "Selected schools",
            "table.houses" to "Schools",
            "col.houseId" to "School id",
            "col.house" to "School",
            "col.label" to "School",
            "col.listingUrl" to "Website",
            "col.houses" to "Schools",
            "section.questions" to "Questions for a visit",
            "section.viewings" to "School visits",
            "table.viewings" to "School visits",
            "section.cost" to "Fees",
        ),
        "hi" to mapOf(
            "doc.title" to "Doorprints — मेरे स्कूल",
            "cover.houses" to "स्कूल",
            "cover.scope" to "शामिल स्कूल",
            "cover.rejected" to "अस्वीकृत स्कूल शामिल",
            "scope.SELECTED" to "चयनित स्कूल",
            "table.houses" to "स्कूल",
            "col.houseId" to "स्कूल आईडी",
            "col.house" to "स्कूल",
            "col.label" to "स्कूल",
            "col.listingUrl" to "वेबसाइट",
            "col.houses" to "स्कूल",
            "section.questions" to "दौरे के लिए सवाल",
            "section.viewings" to "स्कूल का दौरा",
            "table.viewings" to "स्कूल का दौरा",
            "section.cost" to "फ़ीस",
        ),
        "ta" to mapOf(
            "doc.title" to "Doorprints — என் பள்ளிகள்",
            "cover.houses" to "பள்ளிகள்",
            "cover.scope" to "சேர்க்கப்பட்ட பள்ளிகள்",
            "cover.rejected" to "நிராகரிக்கப்பட்ட பள்ளிகளும் உள்ளன",
            "scope.SELECTED" to "தேர்ந்தெடுத்த பள்ளிகள்",
            "table.houses" to "பள்ளிகள்",
            "col.houseId" to "பள்ளி அடையாளம்",
            "col.house" to "பள்ளி",
            "col.label" to "பள்ளி",
            "col.listingUrl" to "இணையதளம்",
            "col.houses" to "பள்ளிகள்",
            "section.questions" to "வருகைக்கான கேள்விகள்",
            "section.viewings" to "பள்ளி வருகைகள்",
            "table.viewings" to "பள்ளி வருகைகள்",
            "section.cost" to "கட்டணம்",
        ),
        "te" to mapOf(
            "doc.title" to "Doorprints — నా పాఠశాలలు",
            "cover.houses" to "పాఠశాలలు",
            "cover.scope" to "చేర్చిన పాఠశాలలు",
            "cover.rejected" to "తిరస్కరించిన పాఠశాలలు కూడా ఉన్నాయి",
            "scope.SELECTED" to "ఎంచుకున్న పాఠశాలలు",
            "table.houses" to "పాఠశాలలు",
            "col.houseId" to "పాఠశాల ఐడీ",
            "col.house" to "పాఠశాల",
            "col.label" to "పాఠశాల",
            "col.listingUrl" to "వెబ్‌సైట్",
            "col.houses" to "పాఠశాలలు",
            "section.questions" to "సందర్శన కోసం ప్రశ్నలు",
            "section.viewings" to "పాఠశాల సందర్శనలు",
            "table.viewings" to "పాఠశాల సందర్శనలు",
            "section.cost" to "ఫీజు",
        ),
    )

    private val OVERLAYS: Map<String, Map<String, Map<String, String>>> = mapOf("school" to SCHOOL)

    /** The words of [kind] for the keys it names, in [language]; null for the house or a kind with none. */
    fun of(kind: String?, language: String): Map<String, String>? = OVERLAYS[kind]?.let { it[language] ?: it["en"] }
}
