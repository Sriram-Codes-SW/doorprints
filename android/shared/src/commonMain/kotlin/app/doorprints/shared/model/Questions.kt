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
import app.doorprints.shared.records.RecordType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

/**
 * A viewing question of the bank (docs/11 5.5, slice 3a of 5.30): a record of type `question` whose id is [id]. A
 * seeded default has a fixed id `qd_<name>` ([DefaultQuestions], so two devices seed the same records), a custom one
 * `q_` + 8 lowercase hex. The payload keys are, in this order, [text], [category], [appliesTo], [defaultOn], [sort] and
 * [archived] (written only when true). The TypeScript twin is `Question` in `web/src/app/shared/question.ts`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Question(
    /** The record id, not part of the payload. */
    @Transient val id: String = "",
    /** 1..[MAX_TEXT] characters: the words the person asks, in the language they were seeded or typed in. */
    val text: String = "",
    /** A [QuestionCategory] name, kept as text so a category from a newer app reads as [QuestionCategory.OTHER]. */
    val category: String = QuestionCategory.OTHER.name,
    /** A [QuestionScope] name: which houses *Add the usual questions* asks it for. */
    val appliesTo: String = QuestionScope.BOTH.name,
    /** True: *Add the usual questions* puts it on a house. */
    val defaultOn: Boolean = false,
    /** The place in the bank, 0 first; ties go by id. */
    val sort: Int = 0,
    /** Hidden from the pickers and never added; written only when true. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val archived: Boolean = false,
) {
    val questionCategory: QuestionCategory get() = QuestionCategory.fromWire(category)
    val scope: QuestionScope get() = QuestionScope.fromWire(appliesTo)

    /** True for one of the fourteen seeded defaults (a fixed id), which Reset to defaults brings back. */
    val isDefault: Boolean get() = DefaultQuestions.byId(id) != null

    /**
     * True when *Add the usual questions* asks it for a house of [priceType]: BOTH always, RENT for a rent (or a house
     * with no price type yet), SALE for a sale.
     */
    fun appliesToHouse(priceType: String?): Boolean = when (scope) {
        QuestionScope.BOTH -> true
        QuestionScope.RENT -> priceType != "SALE"
        QuestionScope.SALE -> priceType == "SALE"
    }

    /**
     * What a reader keeps (the records table, a sync, another device), like [Criterion.coerced]: an unknown category
     * is OTHER, an unknown scope BOTH, a negative sort 0. Null for an untrusted row (an id outside
     * `RecordRules.isValidId`, a blank or over-long text): it is skipped, as the web's `questionFromPayload` does.
     */
    fun coerced(): Question? {
        if (!RecordRules.isValidId(id) || text.isBlank() || text.length > MAX_TEXT) return null
        return copy(category = questionCategory.name, appliesTo = scope.name, sort = sort.coerceAtLeast(0))
    }

    /** True when every value is in range: what a backup's check demands (a bad file is refused whole). */
    val isValid: Boolean
        get() = RecordRules.isValidId(id) && text.isNotBlank() && text.length <= MAX_TEXT && sort >= 0

    companion object {
        const val MAX_TEXT = 300

        /** At most this many questions in all, seeded and custom, archived ones included. */
        const val MAX_QUESTIONS = 100
        const val CUSTOM_PREFIX = "q_"
        private val CUSTOM_ID = Regex("q_[0-9a-f]{8}")

        /** The bank's order: [sort], then the id. */
        val ORDER: Comparator<Question> = compareBy<Question> { it.sort }.thenBy { it.id }

        fun isCustomId(id: String): Boolean = CUSTOM_ID.matches(id)

        /** A fresh custom id, `q_` + 8 random lowercase hex digits, drawn again while [taken] says it is used. */
        fun newCustomId(taken: (String) -> Boolean, random: Random = Random.Default): String {
            while (true) {
                val id = CUSTOM_PREFIX + (1..8).joinToString("") { random.nextInt(16).toString(16) }
                if (!taken(id)) return id
            }
        }
    }
}

/** The `question` record type of the `records` table (slice 0). */
val QuestionType: RecordType<Question> = RecordType("question", Question.serializer())

/** The six categories, in the order the Questions screen and the picker group them. Stored and sent by name. */
enum class QuestionCategory {
    MONEY, WATER_POWER, RULES, BUILDING, LEGAL, OTHER;

    companion object {
        fun fromWire(value: String?): QuestionCategory = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

/** Which houses a question is for: a rent, a sale (*Buy*) or both. Stored and sent by name. */
enum class QuestionScope {
    RENT, SALE, BOTH;

    companion object {
        fun fromWire(value: String?): QuestionScope = entries.firstOrNull { it.name == value } ?: BOTH
    }
}

/**
 * The seed of the bank: the fourteen questions of `docs/schemas/default-questions.json` with their four texts (hi, ta
 * and te are under review, I18N-B06), copied here by hand and compared with the file by `DefaultQuestionsFileTest`.
 * The web's twin is `DEFAULT_QUESTIONS` in `question.ts`.
 */
object DefaultQuestions {
    /**
     * The `updatedAt` of a seeded default (S4b-BL-90a): 2000-01-01T00:00:00Z, the earliest the server accepts, so any
     * real edit or deletion made on another device is later and wins. Seeds are also written clean (not pushed): an
     * untouched default never reaches the server, so it cannot overwrite another device's record, not even on a tie,
     * and a pulled edit or tombstone always replaces it. The web's twin is `DEFAULT_QUESTIONS_SEEDED_AT`.
     */
    const val SEEDED_AT = 946_684_800_000L

    /** One default: its fixed id, its fields and its text by language (en, hi, ta, te). */
    data class Default(
        val id: String,
        val category: QuestionCategory,
        val appliesTo: QuestionScope,
        val defaultOn: Boolean,
        val sort: Int,
        val text: Map<String, String>,
    ) {
        /** The text in [language]: en, hi, ta or te; anything else is English. */
        fun textIn(language: String?): String = text[language] ?: text.getValue("en")

        /** This default as a question of the bank in [language], not archived. */
        fun question(language: String?): Question = Question(
            id = id, text = textIn(language), category = category.name, appliesTo = appliesTo.name,
            defaultOn = defaultOn, sort = sort,
        )
    }

    private fun d(
        id: String, category: QuestionCategory, appliesTo: QuestionScope, defaultOn: Boolean, sort: Int,
        en: String, hi: String, ta: String, te: String,
    ) = Default(id, category, appliesTo, defaultOn, sort, mapOf("en" to en, "hi" to hi, "ta" to ta, "te" to te))

    val ALL: List<Default> = listOf(
        d(
            "qd_maintenance", QuestionCategory.MONEY, QuestionScope.BOTH, true, 0,
            "How much is the maintenance per month, and what does it cover?",
            "मेंटेनेंस हर महीने कितना है और उसमें क्या-क्या शामिल है?",
            "மாதாந்திர பராமரிப்புக் கட்டணம் எவ்வளவு, அதில் என்னென்ன அடங்கும்?",
            "నెలవారీ మెయింటెనెన్స్ ఎంత, అందులో ఏమేం ఉంటాయి?",
        ),
        d(
            "qd_deposit", QuestionCategory.MONEY, QuestionScope.RENT, true, 1,
            "How many months is the deposit, and when and how is it refunded?",
            "डिपॉज़िट कितने महीने का है, और वापसी कब और कैसे होगी?",
            "முன்பணம் (டெபாசிட்) எத்தனை மாதம், எப்போது எப்படித் திரும்பக் கிடைக்கும்?",
            "డిపాజిట్ ఎన్ని నెలలది, ఎప్పుడు ఎలా తిరిగి ఇస్తారు?",
        ),
        d(
            "qd_brokerage", QuestionCategory.MONEY, QuestionScope.BOTH, true, 2,
            "Is there brokerage, and who pays it?",
            "क्या ब्रोकरेज लगेगी, और कौन देगा?",
            "தரகுக் கட்டணம் உண்டா, யார் செலுத்த வேண்டும்?",
            "బ్రోకరేజ్ ఉందా, ఎవరు చెల్లించాలి?",
        ),
        d(
            "qd_lockin", QuestionCategory.MONEY, QuestionScope.RENT, true, 3,
            "What are the lock-in and notice periods?",
            "लॉक-इन और नोटिस की अवधि क्या है?",
            "லாக்-இன் மற்றும் நோட்டீஸ் காலம் என்ன?",
            "లాక్-ఇన్ మరియు నోటీసు వ్యవధి ఎంత?",
        ),
        d(
            "qd_water", QuestionCategory.WATER_POWER, QuestionScope.BOTH, true, 4,
            "Where does the water come from (corporation, borewell, tanker), and at what hours?",
            "पानी कहाँ से आता है (नगर निगम, बोरवेल, टैंकर) और किस समय?",
            "தண்ணீர் எங்கிருந்து வருகிறது (மாநகராட்சி, போர்வெல், லாரி), எந்த நேரங்களில்?",
            "నీళ్లు ఎక్కడి నుంచి వస్తాయి (మున్సిపల్, బోర్‌వెల్, ట్యాంకర్), ఏ సమయాల్లో?",
        ),
        d(
            "qd_power", QuestionCategory.WATER_POWER, QuestionScope.BOTH, true, 5,
            "Is there power backup: for the whole flat, or only the lift and common areas?",
            "क्या बिजली बैकअप है: पूरे फ्लैट में या सिर्फ़ लिफ़्ट और कॉमन एरिया में?",
            "மின்சாரக் காப்பு உண்டா: முழு வீட்டிற்கா, அல்லது லிஃப்ட் மற்றும் பொதுப் பகுதிகளுக்கு மட்டுமா?",
            "పవర్ బ్యాకప్ ఉందా: మొత్తం ఇంటికా, లేక లిఫ్ట్ మరియు కామన్ ఏరియాలకే?",
        ),
        d(
            "qd_pets", QuestionCategory.RULES, QuestionScope.RENT, false, 6,
            "Are pets allowed?",
            "क्या पालतू जानवर रखने की अनुमति है?",
            "செல்லப்பிராணிகளை வளர்க்க அனுமதி உண்டா?",
            "పెంపుడు జంతువులకు అనుమతి ఉందా?",
        ),
        d(
            "qd_bachelors", QuestionCategory.RULES, QuestionScope.RENT, false, 7,
            "Are bachelors or unmarried couples allowed?",
            "क्या बैचलर या अविवाहित जोड़े रह सकते हैं?",
            "தனியாக வசிக்கும் ஆண்கள் அல்லது திருமணமாகாத தம்பதிகள் தங்கலாமா?",
            "బ్యాచిలర్లు లేదా పెళ్లికాని జంటలు ఉండవచ్చా?",
        ),
        d(
            "qd_nonveg", QuestionCategory.RULES, QuestionScope.RENT, false, 8,
            "Is non-vegetarian cooking allowed?",
            "क्या मांसाहारी खाना पकाने की अनुमति है?",
            "அசைவ உணவு சமைக்க அனுமதி உண்டா?",
            "మాంసాహార వంటకు అనుమతి ఉందా?",
        ),
        d(
            "qd_parking", QuestionCategory.BUILDING, QuestionScope.BOTH, true, 9,
            "Is a parking slot allotted, for a car or a two-wheeler?",
            "क्या पार्किंग स्लॉट मिला है, कार के लिए या दोपहिया के लिए?",
            "வாகன நிறுத்த இடம் ஒதுக்கப்பட்டுள்ளதா, காருக்கா அல்லது இருசக்கர வாகனத்துக்கா?",
            "పార్కింగ్ స్లాట్ కేటాయించారా, కారుకా లేక ద్విచక్ర వాహనానికా?",
        ),
        d(
            "qd_floor", QuestionCategory.BUILDING, QuestionScope.BOTH, true, 10,
            "Which floor is it on, and is there a lift?",
            "यह किस मंज़िल पर है, और क्या लिफ़्ट है?",
            "இது எந்த மாடியில் உள்ளது, லிஃப்ட் உண்டா?",
            "ఇది ఏ అంతస్తులో ఉంది, లిఫ్ట్ ఉందా?",
        ),
        d(
            "qd_occupancy", QuestionCategory.LEGAL, QuestionScope.SALE, true, 11,
            "Have the occupancy and completion certificates (OC and CC) been received?",
            "क्या ऑक्यूपेंसी और कंप्लीशन सर्टिफिकेट (OC और CC) मिल चुके हैं?",
            "குடியிருப்புச் சான்றிதழ், நிறைவுச் சான்றிதழ் (OC, CC) கிடைத்துவிட்டதா?",
            "ఆక్యుపెన్సీ, కంప్లీషన్ సర్టిఫికెట్లు (OC, CC) వచ్చాయా?",
        ),
        d(
            "qd_rera", QuestionCategory.LEGAL, QuestionScope.SALE, true, 12,
            "What is the RERA registration number?",
            "RERA रजिस्ट्रेशन नंबर क्या है?",
            "RERA பதிவு எண் என்ன?",
            "RERA రిజిస్ట్రేషన్ నంబర్ ఎంత?",
        ),
        d(
            "qd_khata", QuestionCategory.LEGAL, QuestionScope.SALE, true, 13,
            "Is the khata in order and the property tax paid?",
            "क्या खाता सही है और प्रॉपर्टी टैक्स चुकाया हुआ है?",
            "பட்டா/கணக்கு சரியாக உள்ளதா, சொத்து வரி செலுத்தப்பட்டுள்ளதா?",
            "ఖాతా సరిగా ఉందా, ఆస్తి పన్ను చెల్లించారా?",
        ),
    )

    /** The languages a default has a text in. */
    val LANGUAGES: List<String> = listOf("en", "hi", "ta", "te")

    private val byId: Map<String, Default> = ALL.associateBy { it.id }

    fun byId(id: String): Default? = byId[id]

    /** The whole seeded bank in [language] (what a fresh install holds after seeding). */
    fun bank(language: String?): List<Question> = ALL.map { it.question(language) }
}
