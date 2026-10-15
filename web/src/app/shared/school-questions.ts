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

import type { DefaultQuestion } from './question';

/**
 * The school's question seed (kinds PR 29, docs/03 §18.23), copied from `docs/schemas/kinds/school-questions.json`
 * (`question.spec.ts` reads that file and compares): six questions to ask on a school visit or an open day, a DRAFT for the
 * owner's review; hi, ta and te are under review. The ids carry the kind (`qd_school_<name>`), so the house's bank and the
 * school's never meet (door 9). The Kotlin twin is `DefaultQuestions.SCHOOL`.
 */
export const SCHOOL_QUESTIONS: readonly DefaultQuestion[] = [
  {
    id: "qd_school_fees",
    category: "MONEY",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 0,
    text: {
      en: "What are the fees for the year, and what do they cover (books, uniform, transport, activities)?",
      hi: "साल की फ़ीस कितनी है, और उसमें क्या-क्या शामिल है (किताबें, यूनिफ़ॉर्म, परिवहन, गतिविधियाँ)?",
      ta: "ஆண்டுக் கட்டணம் எவ்வளவு, அதில் என்னென்ன அடங்கும் (புத்தகங்கள், சீருடை, போக்குவரத்து, செயல்பாடுகள்)?",
      te: "సంవత్సర ఫీజు ఎంత, అందులో ఏమేం ఉంటాయి (పుస్తకాలు, యూనిఫారం, రవాణా, కార్యకలాపాలు)?",
    },
  },
  {
    id: "qd_school_admission",
    category: "MONEY",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 1,
    text: {
      en: "How does admission work, what is the admission fee, and what is the last date to apply?",
      hi: "दाख़िला कैसे होता है, दाख़िला फ़ीस कितनी है और आवेदन की आख़िरी तारीख़ क्या है?",
      ta: "சேர்க்கை எப்படி நடக்கிறது, சேர்க்கைக் கட்டணம் எவ்வளவு, விண்ணப்பிக்க கடைசி தேதி என்ன?",
      te: "అడ్మిషన్ ఎలా జరుగుతుంది, అడ్మిషన్ ఫీజు ఎంత, దరఖాస్తుకు చివరి తేదీ ఏది?",
    },
  },
  {
    id: "qd_school_classsize",
    category: "RULES",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 2,
    text: {
      en: "How many children are in a class, and how many teachers and helpers are there?",
      hi: "एक कक्षा में कितने बच्चे हैं, और कितने शिक्षक और सहायक हैं?",
      ta: "ஒரு வகுப்பில் எத்தனை குழந்தைகள், எத்தனை ஆசிரியர்கள் மற்றும் உதவியாளர்கள் உள்ளனர்?",
      te: "ఒక తరగతిలో ఎంతమంది పిల్లలు ఉంటారు, ఎంతమంది ఉపాధ్యాయులు, సహాయకులు ఉన్నారు?",
    },
  },
  {
    id: "qd_school_safety",
    category: "BUILDING",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 3,
    text: {
      en: "How is safety looked after: gate and visitor checks, CCTV, first aid, and the school bus?",
      hi: "सुरक्षा कैसे सँभाली जाती है: गेट और आगंतुकों की जाँच, सीसीटीवी, प्राथमिक उपचार और स्कूल बस?",
      ta: "பாதுகாப்பு எப்படிப் பார்த்துக்கொள்ளப்படுகிறது: வாயில் மற்றும் வருகையாளர் சோதனை, சிசிடிவி, முதலுதவி, பள்ளிப் பேருந்து?",
      te: "భద్రతను ఎలా చూసుకుంటారు: గేటు, సందర్శకుల తనిఖీ, సీసీటీవీ, ప్రథమ చికిత్స, స్కూల్ బస్సు?",
    },
  },
  {
    id: "qd_school_water",
    category: "WATER_POWER",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 4,
    text: {
      en: "Is the drinking water clean, and are the toilets clean and separate for girls and boys?",
      hi: "क्या पीने का पानी साफ़ है, और क्या शौचालय साफ़ हैं और लड़कियों-लड़कों के लिए अलग हैं?",
      ta: "குடிநீர் சுத்தமாக உள்ளதா, கழிப்பறைகள் சுத்தமாகவும் பெண்கள், ஆண்களுக்குத் தனித்தனியாகவும் உள்ளனவா?",
      te: "తాగునీరు శుభ్రంగా ఉందా, మరుగుదొడ్లు శుభ్రంగా, అమ్మాయిలకు అబ్బాయిలకు వేర్వేరుగా ఉన్నాయా?",
    },
  },
  {
    id: "qd_school_schedule",
    category: "OTHER",
    appliesTo: "BOTH",
    defaultOn: true,
    sort: 5,
    text: {
      en: "How much homework is there, what are the school hours, and when are the holidays and exams?",
      hi: "कितना होमवर्क मिलता है, स्कूल का समय क्या है, और छुट्टियाँ और परीक्षाएँ कब होती हैं?",
      ta: "எவ்வளவு வீட்டுப்பாடம் உண்டு, பள்ளி நேரம் என்ன, விடுமுறைகளும் தேர்வுகளும் எப்போது?",
      te: "హోంవర్క్ ఎంత ఉంటుంది, స్కూలు సమయాలు ఏమిటి, సెలవులు, పరీక్షలు ఎప్పుడు?",
    },
  },
];
