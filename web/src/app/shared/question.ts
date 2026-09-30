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

import type { Lang } from '../i18n/languages';

/**
 * The question bank (docs/11 5.5, slice 3a of the Sprint 4b data model): the questions a person likes to ask at a
 * viewing. A question is a record of type `question` whose id is the question's id; a seeded default has a FIXED id
 * (`qd_<name>`, so two devices seed the same records), a custom one `q_` and 8 lowercase hex characters. The twin of
 * Kotlin `Question`/`DefaultQuestions` in android/shared.
 */

export const QUESTION_TYPE = 'question';
export const MAX_QUESTIONS = 100;
export const MAX_QUESTION_TEXT = 300;

export type QuestionCategory = 'MONEY' | 'WATER_POWER' | 'RULES' | 'BUILDING' | 'LEGAL' | 'OTHER';
export type QuestionScope = 'RENT' | 'SALE' | 'BOTH';

/** In the order the Questions screen groups them. */
export const QUESTION_CATEGORIES: readonly QuestionCategory[] = ['MONEY', 'WATER_POWER', 'RULES', 'BUILDING', 'LEGAL', 'OTHER'];
export const QUESTION_SCOPES: readonly QuestionScope[] = ['RENT', 'SALE', 'BOTH'];

/** A custom question's id: `q_` and 8 lowercase hex characters. */
const CUSTOM_ID_PATTERN = /^q_[0-9a-f]{8}$/;
/** A record id: the pattern the records store accepts. */
const ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

export interface Question {
  id: string;
  /** 1..300 characters. */
  text: string;
  category: QuestionCategory;
  appliesTo: QuestionScope;
  /** True: "Add the usual questions" puts it on a house. */
  defaultOn: boolean;
  /** Integer >= 0. */
  sort: number;
  /** Written only when true; an archived question is hidden and never added. */
  archived?: boolean;
}

/** A stored question record, as read. */
export interface QuestionRow {
  id: string;
  updatedAt: string | null;
  question: Question;
}

/** One seeded default: the four texts are the app's languages (hi, ta and te are under review). */
export interface DefaultQuestion {
  readonly id: string;
  readonly category: QuestionCategory;
  readonly appliesTo: QuestionScope;
  readonly defaultOn: boolean;
  readonly sort: number;
  readonly text: Readonly<Record<Lang, string>>;
}

/**
 * The seed of the bank, copied from `docs/schemas/default-questions.json` (`question.spec.ts` reads that file and
 * compares). hi, ta and te are under review (I18N-B06).
 */
export const DEFAULT_QUESTIONS: readonly DefaultQuestion[] = [
  {
    id: "qd_maintenance",
    category: "MONEY",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 0,
    text: {
      en: "How much is the maintenance per month, and what does it cover?",
      hi: "मेंटेनेंस हर महीने कितना है और उसमें क्या-क्या शामिल है?",
      ta: "மாதாந்திர பராமரிப்புக் கட்டணம் எவ்வளவு, அதில் என்னென்ன அடங்கும்?",
      te: "నెలవారీ మెయింటెనెన్స్ ఎంత, అందులో ఏమేం ఉంటాయి?",
    },
  },
  {
    id: "qd_deposit",
    category: "MONEY",
    appliesTo: "RENT",
    defaultOn: true,
    sort: 1,
    text: {
      en: "How many months is the deposit, and when and how is it refunded?",
      hi: "डिपॉज़िट कितने महीने का है, और वापसी कब और कैसे होगी?",
      ta: "முன்பணம் (டெபாசிட்) எத்தனை மாதம், எப்போது எப்படித் திரும்பக் கிடைக்கும்?",
      te: "డిపాజిట్ ఎన్ని నెలలది, ఎప్పుడు ఎలా తిరిగి ఇస్తారు?",
    },
  },
  {
    id: "qd_brokerage",
    category: "MONEY",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 2,
    text: {
      en: "Is there brokerage, and who pays it?",
      hi: "क्या ब्रोकरेज लगेगी, और कौन देगा?",
      ta: "தரகுக் கட்டணம் உண்டா, யார் செலுத்த வேண்டும்?",
      te: "బ్రోకరేజ్ ఉందా, ఎవరు చెల్లించాలి?",
    },
  },
  {
    id: "qd_lockin",
    category: "MONEY",
    appliesTo: "RENT",
    defaultOn: true,
    sort: 3,
    text: {
      en: "What are the lock-in and notice periods?",
      hi: "लॉक-इन और नोटिस की अवधि क्या है?",
      ta: "லாக்-இன் மற்றும் நோட்டீஸ் காலம் என்ன?",
      te: "లాక్-ఇన్ మరియు నోటీసు వ్యవధి ఎంత?",
    },
  },
  {
    id: "qd_water",
    category: "WATER_POWER",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 4,
    text: {
      en: "Where does the water come from (corporation, borewell, tanker), and at what hours?",
      hi: "पानी कहाँ से आता है (नगर निगम, बोरवेल, टैंकर) और किस समय?",
      ta: "தண்ணீர் எங்கிருந்து வருகிறது (மாநகராட்சி, போர்வெல், லாரி), எந்த நேரங்களில்?",
      te: "నీళ్లు ఎక్కడి నుంచి వస్తాయి (మున్సిపల్, బోర్‌వెల్, ట్యాంకర్), ఏ సమయాల్లో?",
    },
  },
  {
    id: "qd_power",
    category: "WATER_POWER",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 5,
    text: {
      en: "Is there power backup: for the whole flat, or only the lift and common areas?",
      hi: "क्या बिजली बैकअप है: पूरे फ्लैट में या सिर्फ़ लिफ़्ट और कॉमन एरिया में?",
      ta: "மின்சாரக் காப்பு உண்டா: முழு வீட்டிற்கா, அல்லது லிஃப்ட் மற்றும் பொதுப் பகுதிகளுக்கு மட்டுமா?",
      te: "పవర్ బ్యాకప్ ఉందా: మొత్తం ఇంటికా, లేక లిఫ్ట్ మరియు కామన్ ఏరియాలకే?",
    },
  },
  {
    id: "qd_pets",
    category: "RULES",
    appliesTo: "RENT",
    defaultOn: false,
    sort: 6,
    text: {
      en: "Are pets allowed?",
      hi: "क्या पालतू जानवर रखने की अनुमति है?",
      ta: "செல்லப்பிராணிகளை வளர்க்க அனுமதி உண்டா?",
      te: "పెంపుడు జంతువులకు అనుమతి ఉందా?",
    },
  },
  {
    id: "qd_bachelors",
    category: "RULES",
    appliesTo: "RENT",
    defaultOn: false,
    sort: 7,
    text: {
      en: "Are bachelors or unmarried couples allowed?",
      hi: "क्या बैचलर या अविवाहित जोड़े रह सकते हैं?",
      ta: "தனியாக வசிக்கும் ஆண்கள் அல்லது திருமணமாகாத தம்பதிகள் தங்கலாமா?",
      te: "బ్యాచిలర్లు లేదా పెళ్లికాని జంటలు ఉండవచ్చా?",
    },
  },
  {
    id: "qd_nonveg",
    category: "RULES",
    appliesTo: "RENT",
    defaultOn: false,
    sort: 8,
    text: {
      en: "Is non-vegetarian cooking allowed?",
      hi: "क्या मांसाहारी खाना पकाने की अनुमति है?",
      ta: "அசைவ உணவு சமைக்க அனுமதி உண்டா?",
      te: "మాంసాహార వంటకు అనుమతి ఉందా?",
    },
  },
  {
    id: "qd_parking",
    category: "BUILDING",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 9,
    text: {
      en: "Is a parking slot allotted, for a car or a two-wheeler?",
      hi: "क्या पार्किंग स्लॉट मिला है, कार के लिए या दोपहिया के लिए?",
      ta: "வாகன நிறுத்த இடம் ஒதுக்கப்பட்டுள்ளதா, காருக்கா அல்லது இருசக்கர வாகனத்துக்கா?",
      te: "పార్కింగ్ స్లాట్ కేటాయించారా, కారుకా లేక ద్విచక్ర వాహనానికా?",
    },
  },
  {
    id: "qd_floor",
    category: "BUILDING",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 10,
    text: {
      en: "Which floor is it on, and is there a lift?",
      hi: "यह किस मंज़िल पर है, और क्या लिफ़्ट है?",
      ta: "இது எந்த மாடியில் உள்ளது, லிஃப்ட் உண்டா?",
      te: "ఇది ఏ అంతస్తులో ఉంది, లిఫ్ట్ ఉందా?",
    },
  },
  {
    id: "qd_occupancy",
    category: "LEGAL",
    appliesTo: "SALE",
    defaultOn: true,
    sort: 11,
    text: {
      en: "Have the occupancy and completion certificates (OC and CC) been received?",
      hi: "क्या ऑक्यूपेंसी और कंप्लीशन सर्टिफिकेट (OC और CC) मिल चुके हैं?",
      ta: "குடியிருப்புச் சான்றிதழ், நிறைவுச் சான்றிதழ் (OC, CC) கிடைத்துவிட்டதா?",
      te: "ఆక్యుపెన్సీ, కంప్లీషన్ సర్టిఫికెట్లు (OC, CC) వచ్చాయా?",
    },
  },
  {
    id: "qd_rera",
    category: "LEGAL",
    appliesTo: "SALE",
    defaultOn: true,
    sort: 12,
    text: {
      en: "What is the RERA registration number?",
      hi: "RERA रजिस्ट्रेशन नंबर क्या है?",
      ta: "RERA பதிவு எண் என்ன?",
      te: "RERA రిజిస్ట్రేషన్ నంబర్ ఎంత?",
    },
  },
  {
    id: "qd_khata",
    category: "LEGAL",
    appliesTo: "SALE",
    defaultOn: true,
    sort: 13,
    text: {
      en: "Is the khata in order and the property tax paid?",
      hi: "क्या खाता सही है और प्रॉपर्टी टैक्स चुकाया हुआ है?",
      ta: "பட்டா/கணக்கு சரியாக உள்ளதா, சொத்து வரி செலுத்தப்பட்டுள்ளதா?",
      te: "ఖాతా సరిగా ఉందా, ఆస్తి పన్ను చెల్లించారా?",
    },
  },
];

export function isDefaultQuestionId(id: string): boolean {
  return DEFAULT_QUESTIONS.some((d) => d.id === id);
}

export function isCustomQuestionId(id: string): boolean {
  return CUSTOM_ID_PATTERN.test(id);
}

/** True when `id` can be a record id at all (`.` and `..` are path segments, so never). */
export function isQuestionId(id: string): boolean {
  return ID_PATTERN.test(id) && id !== '.' && id !== '..';
}

/** The default's text in [language]; anything but hi, ta, te is English. */
export function defaultQuestionText(def: DefaultQuestion, language: string): string {
  return language === 'hi' || language === 'ta' || language === 'te' ? def.text[language] : def.text.en;
}

/** A default as a question in [language], with the bank's fields (not archived). */
export function defaultQuestion(def: DefaultQuestion, language: string): Question {
  return {
    id: def.id,
    text: defaultQuestionText(def, language),
    category: def.category,
    appliesTo: def.appliesTo,
    defaultOn: def.defaultOn,
    sort: def.sort,
  };
}

/**
 * Reads a record payload as a question. An unknown category is OTHER and an unknown scope BOTH, a sort or flag out of
 * range takes its default; a blank or over-long text, or an id outside the record-id pattern, is untrusted, so the row
 * is `null` and the caller skips it.
 */
export function questionFromPayload(id: string, payload: Record<string, unknown> | null | undefined): Question | null {
  if (!isQuestionId(id) || !payload || typeof payload !== 'object') return null;
  const text = payload['text'];
  if (typeof text !== 'string' || text.trim() === '' || text.length > MAX_QUESTION_TEXT) return null;
  const category = payload['category'];
  const scope = payload['appliesTo'];
  const sort = payload['sort'];
  const out: Question = {
    id,
    text,
    category: QUESTION_CATEGORIES.includes(category as QuestionCategory) ? (category as QuestionCategory) : 'OTHER',
    appliesTo: QUESTION_SCOPES.includes(scope as QuestionScope) ? (scope as QuestionScope) : 'BOTH',
    defaultOn: payload['defaultOn'] === true,
    sort: typeof sort === 'number' && Number.isInteger(sort) && sort >= 0 ? sort : 0,
  };
  if (payload['archived'] === true) out.archived = true;
  return out;
}

/** The payload keys in the contract's order: text, category, appliesTo, defaultOn, sort, archived (only true). */
export function questionToPayload(question: Question): Record<string, unknown> {
  const out: Record<string, unknown> = {
    text: question.text,
    category: question.category,
    appliesTo: question.appliesTo,
    defaultOn: question.defaultOn,
    sort: question.sort,
  };
  if (question.archived === true) out['archived'] = true;
  return out;
}

/** By `sort`, then id (code-unit order, so the same on every device). */
export function sortQuestions<T extends { sort: number; id: string }>(list: readonly T[]): T[] {
  return [...list].sort((a, b) => a.sort - b.sort || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** `q_` and 8 random lowercase hex characters: a custom question's id. */
export function newQuestionId(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return 'q_' + Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
}
