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

    /** The kind whose bank this question is in, read from its id alone (kinds PR 29): `qd_school_` and `q_school_` are the school's, every other id is the house's. */
    val kind: String get() = kindOf(id)

    /** True for one of the seeded defaults (a fixed id, of any kind), which Reset to defaults brings back. */
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
        /** `q_`, the kind's id for a kind's own bank (a built-in one, or one a person added: `u_…`, docs/03 ADR-39), then 8 hex digits. */
        private val CUSTOM_ID = Regex("q_(?:(?:[a-z]{2,12}|u_[a-z][a-z0-9_]{1,29})_)?[0-9a-f]{8}")

        /** The kinds besides the house that have a question bank of their own; their ids carry the kind (docs/03 section 18.23). */
        val KINDS: List<String> = listOf("school")

        /**
         * The kind a question id belongs to: a kind's own ids start `qd_<kind>_` or `q_<kind>_`, every other id is the house's. The
         * longest id first: a kind a person added may be `u_gym_hall` beside `u_gym` (docs/03 ADR-39).
         */
        fun kindOf(id: String): String = (KINDS + UserKinds.ids()).sortedByDescending { it.length }
            .firstOrNull { id.startsWith("qd_${it}_") || id.startsWith("q_${it}_") } ?: Candidates.BASELINE_KIND

        /** The bank's order: [sort], then the id. */
        val ORDER: Comparator<Question> = compareBy<Question> { it.sort }.thenBy { it.id }

        fun isCustomId(id: String): Boolean = CUSTOM_ID.matches(id)

        /**
         * A fresh custom id, `q_` + 8 random lowercase hex digits (`q_<kind>_` first for a kind's own bank), drawn again while
         * [taken] says it is used.
         */
        fun newCustomId(taken: (String) -> Boolean, random: Random = Random.Default, kind: String = Candidates.BASELINE_KIND): String {
            while (true) {
                val id = (if (KindCapabilities.of(kind).baseline) CUSTOM_PREFIX else "${CUSTOM_PREFIX}${kind}_") + (1..8).joinToString("") { random.nextInt(16).toString(16) }
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
        /**
         * The scopes a kind's questions can have (its kind file's `questions.scopes`, docs/03 section 18.23): the house's are all
         * three; a school has neither rent nor sale, so its questions are all [BOTH] and the scope control is not offered. A kind
         * with no entry has them all. The website's twin is `questionScopesOf`.
         */
        private val BY_KIND: Map<String, List<QuestionScope>> = mapOf("school" to listOf(BOTH))

        fun of(kind: String): List<QuestionScope> = BY_KIND[kind] ?: if (UserKinds.kind(kind) != null) listOf(BOTH) else entries

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


    /**
     * The school's seed (kinds PR 29, docs/03 section 18.23): the six questions of `docs/schemas/kinds/school-questions.json`, a
     * DRAFT for the owner's review, copied here by hand and compared with the file by `DefaultQuestionsFileTest`. The ids carry
     * the kind (`qd_school_<name>`), so the house's bank and the school's never meet. The web's twin is `SCHOOL_QUESTIONS`.
     */
    val SCHOOL: List<Default> = listOf(
        d(
            "qd_school_fees", QuestionCategory.MONEY, QuestionScope.BOTH, true, 0,
            "What are the fees for the year, and what do they cover (books, uniform, transport, activities)?",
            "साल की फ़ीस कितनी है, और उसमें क्या-क्या शामिल है (किताबें, यूनिफ़ॉर्म, परिवहन, गतिविधियाँ)?",
            "ஆண்டுக் கட்டணம் எவ்வளவு, அதில் என்னென்ன அடங்கும் (புத்தகங்கள், சீருடை, போக்குவரத்து, செயல்பாடுகள்)?",
            "సంవత్సర ఫీజు ఎంత, అందులో ఏమేం ఉంటాయి (పుస్తకాలు, యూనిఫారం, రవాణా, కార్యకలాపాలు)?",
        ),
        d(
            "qd_school_admission", QuestionCategory.LEGAL, QuestionScope.BOTH, true, 1,
            "How does admission work, what is the admission fee, and what is the last date to apply?",
            "दाख़िला कैसे होता है, दाख़िला फ़ीस कितनी है और आवेदन की आख़िरी तारीख़ क्या है?",
            "சேர்க்கை எப்படி நடக்கிறது, சேர்க்கைக் கட்டணம் எவ்வளவு, விண்ணப்பிக்க கடைசி தேதி என்ன?",
            "అడ్మిషన్ ఎలా జరుగుతుంది, అడ్మిషన్ ఫీజు ఎంత, దరఖాస్తుకు చివరి తేదీ ఏది?",
        ),
        d(
            "qd_school_classsize", QuestionCategory.RULES, QuestionScope.BOTH, true, 2,
            "How many children are in a class, and how many teachers and helpers are there?",
            "एक कक्षा में कितने बच्चे हैं, और कितने शिक्षक और सहायक हैं?",
            "ஒரு வகுப்பில் எத்தனை குழந்தைகள், எத்தனை ஆசிரியர்கள் மற்றும் உதவியாளர்கள் உள்ளனர்?",
            "ఒక తరగతిలో ఎంతమంది పిల్లలు ఉంటారు, ఎంతమంది ఉపాధ్యాయులు, సహాయకులు ఉన్నారు?",
        ),
        d(
            "qd_school_safety", QuestionCategory.BUILDING, QuestionScope.BOTH, true, 3,
            "How is safety looked after: gate and visitor checks, CCTV, first aid, and the school bus?",
            "सुरक्षा कैसे सँभाली जाती है: गेट और आगंतुकों की जाँच, सीसीटीवी, प्राथमिक उपचार और स्कूल बस?",
            "பாதுகாப்பு எப்படிப் பார்த்துக்கொள்ளப்படுகிறது: வாயில் மற்றும் வருகையாளர் சோதனை, சிசிடிவி, முதலுதவி, பள்ளிப் பேருந்து?",
            "భద్రతను ఎలా చూసుకుంటారు: గేటు, సందర్శకుల తనిఖీ, సీసీటీవీ, ప్రథమ చికిత్స, స్కూల్ బస్సు?",
        ),
        d(
            "qd_school_water", QuestionCategory.WATER_POWER, QuestionScope.BOTH, true, 4,
            "Is the drinking water clean, and are the toilets clean and separate for girls and boys?",
            "क्या पीने का पानी साफ़ है, और क्या शौचालय साफ़ हैं और लड़कियों-लड़कों के लिए अलग हैं?",
            "குடிநீர் சுத்தமாக உள்ளதா, கழிப்பறைகள் சுத்தமாகவும் பெண்கள், ஆண்களுக்குத் தனித்தனியாகவும் உள்ளனவா?",
            "తాగునీరు శుభ్రంగా ఉందా, మరుగుదొడ్లు శుభ్రంగా, అమ్మాయిలకు అబ్బాయిలకు వేర్వేరుగా ఉన్నాయా?",
        ),
        d(
            "qd_school_schedule", QuestionCategory.OTHER, QuestionScope.BOTH, true, 5,
            "How much homework is there, what are the school hours, and when are the holidays and exams?",
            "कितना होमवर्क मिलता है, स्कूल का समय क्या है, और छुट्टियाँ और परीक्षाएँ कब होती हैं?",
            "எவ்வளவு வீட்டுப்பாடம் உண்டு, பள்ளி நேரம் என்ன, விடுமுறைகளும் தேர்வுகளும் எப்போது?",
            "హోంవర్క్ ఎంత ఉంటుంది, స్కూలు సమయాలు ఏమిటి, సెలవులు, పరీక్షలు ఎప్పుడు?",
        ),
    )

    /**
     * The school's category headers (`categoryLabels` of `school-questions.json`, compared with it by `DefaultQuestionsFileTest`): the bank's six
     * category names kept, so a stored question keeps its category, each worded for a school. hi, ta and te are under review. The web's twin is
     * `SCHOOL_CATEGORY_LABELS`.
     */
    private val SCHOOL_CATEGORY_LABELS: Map<QuestionCategory, Map<String, String>> = mapOf(
        QuestionCategory.MONEY to mapOf("en" to "Fees and money", "hi" to "फ़ीस और पैसा", "ta" to "கட்டணம் மற்றும் பணம்", "te" to "ఫీజులు మరియు డబ్బు"),
        QuestionCategory.WATER_POWER to mapOf("en" to "Water and toilets", "hi" to "पानी और शौचालय", "ta" to "தண்ணீர் மற்றும் கழிப்பறைகள்", "te" to "నీరు మరియు మరుగుదొడ్లు"),
        QuestionCategory.RULES to mapOf("en" to "Teaching", "hi" to "पढ़ाई", "ta" to "கற்பித்தல்", "te" to "బోధన"),
        QuestionCategory.BUILDING to mapOf("en" to "Safety and facilities", "hi" to "सुरक्षा और सुविधाएँ", "ta" to "பாதுகாப்பு மற்றும் வசதிகள்", "te" to "భద్రత మరియు సౌకర్యాలు"),
        QuestionCategory.LEGAL to mapOf("en" to "Admission", "hi" to "दाख़िला", "ta" to "சேர்க்கை", "te" to "అడ్మిషన్"),
        QuestionCategory.OTHER to mapOf("en" to "Daily routine and other", "hi" to "दिनचर्या और अन्य", "ta" to "அன்றாட நடைமுறை மற்றும் பிற", "te" to "దినచర్య మరియు ఇతరాలు"),
    )

    /**
     * The kind's own header for [category] in [language] (anything but en, hi, ta, te reads English), or null when the kind has none (the house, whose headers are
     * the `question_category_*` strings, or a category the kind does not word): the screen then shows the house's label.
     */
    fun categoryLabel(kind: String, category: QuestionCategory, language: String?): String? {
        val labels = (if (KindCapabilities.of(kind).baseline) null else if (kind == "school") SCHOOL_CATEGORY_LABELS else null)?.get(category) ?: return null
        return labels[language] ?: labels.getValue("en")
    }

    /** The languages a default has a text in. */
    val LANGUAGES: List<String> = listOf("en", "hi", "ta", "te")

    private val byId: Map<String, Default> = (ALL + SCHOOL).associateBy { it.id }

    /** The seed of a kind's bank: the house's `default-questions.json`, the school's `school-questions.json`; none for another kind. */
    fun of(kind: String): List<Default> = if (KindCapabilities.of(kind).baseline) ALL else if (kind == "school") SCHOOL else emptyList()

    /** The seeded question with this id (of any kind), or null for an id that is not one. */
    fun byId(id: String): Default? = byId[id]

    /** The whole seeded bank in [language] (what a fresh install holds after seeding). */
    fun bank(language: String?): List<Question> = ALL.map { it.question(language) }
}
