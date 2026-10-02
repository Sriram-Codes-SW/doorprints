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

package app.doorprints.drive.connect

/**
 * The text of `Read me.txt` in the Doorprints folder's root (docs/15 §5.1, S4b-BL-117), four languages in one file
 * (Hindi, Tamil and Telugu under review). Identical to `docs/schemas/drive-readme.txt` and to the website's
 * `drive-readme.ts`; a test on each stack compares them. It names no account, no id and no key, and the app never
 * reads it back: it is for the person who opens the folder in Drive.
 */
object DriveReadme {
    const val NAME = "Read me.txt"
    const val KIND = "readme"
    const val MIME = "text/plain"

    val TEXT: String = """Doorprints: your house-hunting notes
====================================
This folder was made by the Doorprints app (https://doorprints.web.app) in your own Google Drive. Nobody else can see what is in it, not even the people who make Doorprints.

What is here:
- Backups: encrypted copies of your houses. They open only in the Doorprints app or website, with a key kept on your device or with your recovery key.
- keys.json and doorprints.json: the list of keys and the settings of this folder.

Please do not change, move or rename these files: Doorprints finds its own files by itself.
To remove everything Doorprints keeps here, open Doorprints > Settings > Google Drive > Delete everything Doorprints keeps in my Google Drive. Files you delete by hand cannot be brought back.

------------------------------------
हिन्दी (समीक्षा के अधीन)
Doorprints: आपके मकान खोजने के नोट्स
यह फ़ोल्डर Doorprints ऐप ने आपके अपने Google Drive में बनाया है। इसे कोई और नहीं देख सकता, Doorprints बनाने वाले भी नहीं।

इसमें क्या है:
- Backups: आपके मकानों की एन्क्रिप्ट की हुई प्रतियाँ। ये सिर्फ़ Doorprints ऐप या वेबसाइट में खुलती हैं, आपके डिवाइस की कुंजी या आपकी रिकवरी कुंजी से।
- keys.json और doorprints.json: कुंजियों की सूची और इस फ़ोल्डर की सेटिंग।

कृपया इन फ़ाइलों को न बदलें, न हटाएँ और न इनका नाम बदलें: Doorprints अपनी फ़ाइलें खुद ढूँढ लेता है।
Doorprints जो कुछ यहाँ रखता है उसे हटाना हो तो Doorprints > सेटिंग > Google Drive > "मेरे Google Drive से Doorprints का सारा डेटा हटाएँ" चुनें। हाथ से हटाई गई फ़ाइलें वापस नहीं आतीं।

------------------------------------
தமிழ் (மதிப்பாய்வில் உள்ளது)
Doorprints: உங்கள் வீடு தேடல் குறிப்புகள்
இந்தக் கோப்புறையை Doorprints செயலி உங்கள் சொந்த Google Drive-இல் உருவாக்கியது. இதில் உள்ளதை வேறு யாரும் பார்க்க முடியாது, Doorprints-ஐ உருவாக்கியவர்களும் கூட.

உள்ளே இருப்பவை:
- Backups: உங்கள் வீடுகளின் குறியாக்கம் செய்யப்பட்ட நகல்கள். அவை Doorprints செயலி அல்லது இணையதளத்தில், உங்கள் சாதனத்தில் உள்ள விசையுடன் அல்லது உங்கள் மீட்பு விசையுடன் மட்டுமே திறக்கும்.
- keys.json மற்றும் doorprints.json: இந்தக் கோப்புறையின் விசைப் பட்டியலும் அமைப்புகளும்.

இந்தக் கோப்புகளை மாற்றவோ, நகர்த்தவோ, பெயர் மாற்றவோ வேண்டாம்: Doorprints தன் கோப்புகளைத் தானே கண்டுபிடிக்கும்.
Doorprints இங்கே வைத்திருக்கும் அனைத்தையும் நீக்க, Doorprints > அமைப்புகள் > Google Drive > "என் Google Drive-இல் Doorprints வைத்திருக்கும் அனைத்தையும் நீக்கு" என்பதைத் தேர்ந்தெடுக்கவும். கையால் நீக்கிய கோப்புகள் திரும்பக் கிடைக்காது.

------------------------------------
తెలుగు (సమీక్షలో ఉంది)
Doorprints: మీ ఇల్లు వెతుకులాట గమనికలు
ఈ ఫోల్డర్‌ను Doorprints యాప్ మీ స్వంత Google Drive లో సృష్టించింది. దీనిలో ఉన్నది మరెవరూ చూడలేరు, Doorprints తయారుచేసినవారు కూడా.

లోపల ఉన్నవి:
- Backups: మీ ఇళ్ల ఎన్‌క్రిప్ట్ చేసిన కాపీలు. అవి Doorprints యాప్ లేదా వెబ్‌సైట్‌లో, మీ పరికరంలోని కీతో లేదా మీ రికవరీ కీతో మాత్రమే తెరుచుకుంటాయి.
- keys.json మరియు doorprints.json: ఈ ఫోల్డర్‌లోని కీల జాబితా, సెట్టింగ్‌లు.

ఈ ఫైళ్లను మార్చకండి, తరలించకండి, పేరు మార్చకండి: Doorprints తన ఫైళ్లను తానే కనుగొంటుంది.
Doorprints ఇక్కడ ఉంచినవన్నీ తొలగించాలంటే Doorprints > సెట్టింగ్‌లు > Google Drive > "నా Google Drive లో Doorprints ఉంచినవన్నీ తొలగించు" ఎంచుకోండి. చేతితో తొలగించిన ఫైళ్లు తిరిగి రావు.
"""

    fun bytes(): ByteArray = TEXT.encodeToByteArray()
}
