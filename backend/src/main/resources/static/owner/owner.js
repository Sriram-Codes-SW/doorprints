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

// The owner page (docs/03 §12.1, ADR-25): devices and their keys, connecting a device by its code or a QR code, and
// the browsers signed in here. Everything a device or a browser sends (names) is shown with textContent, never as HTML.
// Hindi, Tamil and Telugu are machine-drafted and under review.
'use strict';

const TEXT = {
  en: {
    title: 'Doorprints owner page', language: 'Language',
    signInHeading: 'Sign in',
    signInBody: 'Open the one-time link your server wrote to its log when it started. It works once, for one hour.',
    signInHow: 'With Docker: run docker compose logs api and look for "Doorprints owner page". Restart the server for a new link.',
    connectHeading: 'Connect a device',
    connectBody: 'On the device, open Connect (on a phone: Settings) and choose Get a code. Type the code it shows here.',
    codeLabel: 'Code on the device', find: 'Find',
    deviceAsking: 'Device asking to connect:',
    checkName: 'Approve only if this is the device in front of you.',
    approve: 'Approve', deny: 'Deny',
    qrHeading: 'Add a device with a QR code',
    qrBody: 'Scan it with the phone\'s camera: the Doorprints app opens and connects. It works once, within 10 minutes, so show it only to your own device.',
    makeQr: 'Make a QR code', validUntil: 'Valid until',
    openWeb: 'Open the website connected to this server', copyWeb: 'Copy the website link',
    devicesHeading: 'Devices',
    devicesBody: 'Each device has its own key. AI requests use your Gemini key, so AI is off for a new device until you turn it on here.',
    noDevices: 'No devices yet.',
    added: 'Added', lastUsed: 'Last used', never: 'not yet', revokedOn: 'Revoked',
    viaCode: 'by code', viaInvite: 'by QR code or link',
    ai: 'AI', revoke: 'Revoke',
    confirmRevoke: 'Revoke {name}? Its key stops working at once; the device has to connect again.',
    browsersHeading: 'Browsers signed in to this page',
    thisBrowser: 'This browser', signOutThis: 'Sign out',
    addBrowser: 'Add another browser', othersOut: 'Sign out everywhere else', signOut: 'Sign out',
    browserLinkBody: 'Open this link once, within one hour, in the other browser:', copyLink: 'Copy the link',
    footer: 'Doorprints is free software under the GNU AGPL version 3, with no warranty.',
    signedIn: 'Signed in.', linkUsed: 'This link was already used or has expired. Restart the server for a new one.',
    approved: 'Approved. The device connects within a few seconds.', denied: 'Denied.',
    wrongCode: 'No device is waiting with this code. Check it, or start again on the device.',
    tooMany: 'Too many wrong codes. Wait a minute and try again.',
    revoked: 'Revoked.', aiOn: 'AI is on for {name}.', aiOff: 'AI is off for {name}.',
    copied: 'Copied.', copyFailed: 'Could not copy; select the link and copy it.',
    othersClosed: 'Signed out everywhere else.', signedOut: 'Signed out.',
    failed: 'That did not work. Check the connection and try again.',
    sessionEnded: 'Your sign-in on this page has ended. Open a new link from the server\'s log.',
    aiHeading: "AI (Google Gemini)",
    aiNotSetUp: "AI is not set up on this server. To use it, set APP_AI_ENABLED=true in the server's settings file and restart the server.",
    aiVertex: "This server uses Google Cloud Vertex AI, which needs no Gemini key.",
    keyLabel: "Gemini key",
    saveKey: "Save key",
    keyHelp: "Make a separate key just for Doorprints in Google AI Studio, so you can delete it there at any time. The key is stored encrypted and cannot be shown again.",
    keyLink: "Open Google AI Studio",
    removeKey: "Remove key",
    aiOnServer: "AI on this server",
    keyFromPage: "Gemini key: set on this page, ends in {last4}.",
    keyFromFile: "Gemini key: from the server's settings file, ends in {last4}. A key saved here replaces it.",
    keyNone: "No Gemini key yet: AI stays off until you add one.",
    keySaved: "Key saved.",
    keyRemoved: "Key removed.",
    keyInvalid: "A Gemini key is one line of letters, digits and symbols, with no spaces.",
    confirmRemoveKey: "Remove the Gemini key? AI stops until you add a key again.",
    aiResumed: "AI is on for this server.",
    aiPausedMsg: "AI is paused for the whole server.",
  },
  hi: {
    title: 'Doorprints मालिक पेज', language: 'भाषा',
    signInHeading: 'साइन इन करें',
    signInBody: 'वह एक-बार का लिंक खोलें जो आपके सर्वर ने शुरू होते समय अपने लॉग में लिखा। यह एक बार, एक घंटे तक काम करता है।',
    signInHow: 'Docker के साथ: docker compose logs api चलाएँ और "Doorprints owner page" ढूँढें। नए लिंक के लिए सर्वर फिर से शुरू करें।',
    connectHeading: 'डिवाइस जोड़ें',
    connectBody: 'डिवाइस पर कनेक्शन (फ़ोन पर: सेटिंग्स) खोलें और ‘कोड लें’ चुनें। वह जो कोड दिखाए, उसे यहाँ लिखें।',
    codeLabel: 'डिवाइस पर दिखा कोड', find: 'ढूँढें',
    deviceAsking: 'जुड़ने के लिए पूछ रहा डिवाइस:',
    checkName: 'मंज़ूरी तभी दें जब यह आपके सामने वाला डिवाइस हो।',
    approve: 'मंज़ूर करें', deny: 'मना करें',
    qrHeading: 'QR कोड से डिवाइस जोड़ें',
    qrBody: 'इसे फ़ोन के कैमरे से स्कैन करें: Doorprints ऐप खुलकर जुड़ जाता है। यह 10 मिनट के अंदर एक बार काम करता है, इसलिए इसे सिर्फ़ अपने डिवाइस को दिखाएँ।',
    makeQr: 'QR कोड बनाएँ', validUntil: 'इस समय तक मान्य',
    openWeb: 'इस सर्वर से जुड़ी वेबसाइट खोलें', copyWeb: 'वेबसाइट का लिंक कॉपी करें',
    devicesHeading: 'डिवाइस',
    devicesBody: 'हर डिवाइस की अपनी कुंजी है। AI अनुरोध आपकी Gemini कुंजी इस्तेमाल करते हैं, इसलिए नए डिवाइस के लिए AI तब तक बंद रहता है जब तक आप इसे यहाँ चालू न करें।',
    noDevices: 'अभी कोई डिवाइस नहीं।',
    added: 'जोड़ा गया', lastUsed: 'आख़िरी इस्तेमाल', never: 'अभी नहीं', revokedOn: 'रद्द किया गया',
    viaCode: 'कोड से', viaInvite: 'QR कोड या लिंक से',
    ai: 'AI', revoke: 'रद्द करें',
    confirmRevoke: '{name} रद्द करें? इसकी कुंजी तुरंत काम करना बंद कर देगी; डिवाइस को फिर से जुड़ना होगा।',
    browsersHeading: 'इस पेज में साइन इन ब्राउज़र',
    thisBrowser: 'यह ब्राउज़र', signOutThis: 'साइन आउट',
    addBrowser: 'दूसरा ब्राउज़र जोड़ें', othersOut: 'बाकी सब जगह से साइन आउट करें', signOut: 'साइन आउट',
    browserLinkBody: 'यह लिंक दूसरे ब्राउज़र में एक घंटे के अंदर एक बार खोलें:', copyLink: 'लिंक कॉपी करें',
    footer: 'Doorprints GNU AGPL संस्करण 3 के तहत मुक्त सॉफ़्टवेयर है, बिना किसी वारंटी के।',
    signedIn: 'साइन इन हो गया।', linkUsed: 'यह लिंक पहले ही इस्तेमाल हो चुका है या इसकी अवधि ख़त्म हो गई है। नए लिंक के लिए सर्वर फिर से शुरू करें।',
    approved: 'मंज़ूर। डिवाइस कुछ सेकंड में जुड़ जाएगा।', denied: 'मना कर दिया।',
    wrongCode: 'इस कोड से कोई डिवाइस इंतज़ार नहीं कर रहा। कोड जाँचें, या डिवाइस पर फिर से शुरू करें।',
    tooMany: 'बहुत सारे ग़लत कोड। एक मिनट रुककर फिर कोशिश करें।',
    revoked: 'रद्द किया गया।', aiOn: '{name} के लिए AI चालू है।', aiOff: '{name} के लिए AI बंद है।',
    copied: 'कॉपी हो गया।', copyFailed: 'कॉपी नहीं हो सका; लिंक चुनकर कॉपी करें।',
    othersClosed: 'बाकी सब जगह से साइन आउट हो गया।', signedOut: 'साइन आउट हो गया।',
    failed: 'यह नहीं हुआ। कनेक्शन जाँचकर फिर कोशिश करें।',
    sessionEnded: 'इस पेज पर आपका साइन इन ख़त्म हो गया है। सर्वर के लॉग से नया लिंक खोलें।',
    aiHeading: "AI (Google Gemini)",
    aiNotSetUp: "इस सर्वर पर AI सेट नहीं है। इसे इस्तेमाल करने के लिए सर्वर की सेटिंग्स फ़ाइल में APP_AI_ENABLED=true लिखें और सर्वर फिर से शुरू करें।",
    aiVertex: "यह सर्वर Google Cloud Vertex AI इस्तेमाल करता है, जिसे Gemini कुंजी की ज़रूरत नहीं है।",
    keyLabel: "Gemini कुंजी",
    saveKey: "कुंजी सहेजें",
    keyHelp: "Google AI Studio में सिर्फ़ Doorprints के लिए एक अलग कुंजी बनाएँ, ताकि आप उसे वहाँ कभी भी मिटा सकें। कुंजी एन्क्रिप्ट करके रखी जाती है और दोबारा दिखाई नहीं जा सकती।",
    keyLink: "Google AI Studio खोलें",
    removeKey: "कुंजी हटाएँ",
    aiOnServer: "इस सर्वर पर AI",
    keyFromPage: "Gemini कुंजी: इस पेज पर सेट की गई, आख़िरी अक्षर {last4}।",
    keyFromFile: "Gemini कुंजी: सर्वर की सेटिंग्स फ़ाइल से, आख़िरी अक्षर {last4}। यहाँ सहेजी गई कुंजी उसकी जगह ले लेगी।",
    keyNone: "अभी कोई Gemini कुंजी नहीं: जब तक आप कुंजी नहीं जोड़ते, AI बंद रहेगा।",
    keySaved: "कुंजी सहेजी गई।",
    keyRemoved: "कुंजी हटा दी गई।",
    keyInvalid: "Gemini कुंजी अक्षरों, अंकों और चिह्नों की एक पंक्ति होती है, बिना खाली जगह के।",
    confirmRemoveKey: "Gemini कुंजी हटाएँ? जब तक आप फिर से कुंजी नहीं जोड़ते, AI बंद रहेगा।",
    aiResumed: "इस सर्वर पर AI चालू है।",
    aiPausedMsg: "पूरे सर्वर के लिए AI रोका गया है।",
  },
  ta: {
    title: 'Doorprints உரிமையாளர் பக்கம்', language: 'மொழி',
    signInHeading: 'உள்நுழை',
    signInBody: 'உங்கள் சேவையகம் தொடங்கும்போது தன் பதிவில் எழுதிய ஒருமுறை இணைப்பைத் திறக்கவும். அது ஒரு மணி நேரத்துக்குள் ஒருமுறை மட்டும் வேலை செய்யும்.',
    signInHow: 'Docker உடன்: docker compose logs api இயக்கி "Doorprints owner page" என்பதைத் தேடவும். புதிய இணைப்புக்கு சேவையகத்தை மீண்டும் தொடங்கவும்.',
    connectHeading: 'சாதனத்தை இணை',
    connectBody: 'சாதனத்தில் இணைப்பு (தொலைபேசியில்: அமைப்புகள்) என்பதைத் திறந்து ‘குறியீட்டைப் பெறவும்’ என்பதைத் தேர்ந்தெடுக்கவும். அது காட்டும் குறியீட்டை இங்கே தட்டச்சு செய்யவும்.',
    codeLabel: 'சாதனத்தில் உள்ள குறியீடு', find: 'கண்டுபிடி',
    deviceAsking: 'இணைய அனுமதி கேட்கும் சாதனம்:',
    checkName: 'இது உங்கள் முன் உள்ள சாதனம் என்றால் மட்டும் அனுமதிக்கவும்.',
    approve: 'அனுமதி', deny: 'மறு',
    qrHeading: 'QR குறியீட்டால் சாதனத்தைச் சேர்',
    qrBody: 'தொலைபேசியின் கேமராவால் ஸ்கேன் செய்யவும்: Doorprints செயலி திறந்து இணையும். இது 10 நிமிடங்களுக்குள் ஒருமுறை மட்டும் வேலை செய்யும், எனவே உங்கள் சாதனத்துக்கு மட்டும் காட்டவும்.',
    makeQr: 'QR குறியீடு உருவாக்கு', validUntil: 'செல்லுபடியாகும் வரை',
    openWeb: 'இந்தச் சேவையகத்துடன் இணைந்த இணையதளத்தைத் திற', copyWeb: 'இணையதள இணைப்பை நகலெடு',
    devicesHeading: 'சாதனங்கள்',
    devicesBody: 'ஒவ்வொரு சாதனத்துக்கும் தனிச் சாவி உள்ளது. AI கோரிக்கைகள் உங்கள் Gemini சாவியைப் பயன்படுத்தும், எனவே புதிய சாதனத்துக்கு நீங்கள் இங்கே இயக்கும் வரை AI அணைந்திருக்கும்.',
    noDevices: 'இன்னும் சாதனங்கள் இல்லை.',
    added: 'சேர்க்கப்பட்டது', lastUsed: 'கடைசியாகப் பயன்படுத்தியது', never: 'இன்னும் இல்லை', revokedOn: 'ரத்து செய்யப்பட்டது',
    viaCode: 'குறியீட்டால்', viaInvite: 'QR குறியீடு அல்லது இணைப்பால்',
    ai: 'AI', revoke: 'ரத்து செய்',
    confirmRevoke: '{name} ஐ ரத்து செய்யவா? அதன் சாவி உடனே வேலை செய்வதை நிறுத்தும்; சாதனம் மீண்டும் இணைய வேண்டும்.',
    browsersHeading: 'இந்தப் பக்கத்தில் உள்நுழைந்த உலாவிகள்',
    thisBrowser: 'இந்த உலாவி', signOutThis: 'வெளியேறு',
    addBrowser: 'மற்றொரு உலாவியைச் சேர்', othersOut: 'மற்ற எல்லா இடங்களிலும் வெளியேறு', signOut: 'வெளியேறு',
    browserLinkBody: 'இந்த இணைப்பை மற்ற உலாவியில் ஒரு மணி நேரத்துக்குள் ஒருமுறை திறக்கவும்:', copyLink: 'இணைப்பை நகலெடு',
    footer: 'Doorprints என்பது GNU AGPL பதிப்பு 3-இன் கீழ் உள்ள கட்டற்ற மென்பொருள், எந்த உத்தரவாதமும் இல்லை.',
    signedIn: 'உள்நுழைந்தீர்கள்.', linkUsed: 'இந்த இணைப்பு ஏற்கனவே பயன்படுத்தப்பட்டது அல்லது காலாவதியானது. புதிய இணைப்புக்கு சேவையகத்தை மீண்டும் தொடங்கவும்.',
    approved: 'அனுமதிக்கப்பட்டது. சாதனம் சில நொடிகளில் இணையும்.', denied: 'மறுக்கப்பட்டது.',
    wrongCode: 'இந்தக் குறியீட்டுடன் எந்தச் சாதனமும் காத்திருக்கவில்லை. சரிபார்க்கவும், அல்லது சாதனத்தில் மீண்டும் தொடங்கவும்.',
    tooMany: 'அதிகமான தவறான குறியீடுகள். ஒரு நிமிடம் காத்திருந்து மீண்டும் முயற்சிக்கவும்.',
    revoked: 'ரத்து செய்யப்பட்டது.', aiOn: '{name} க்கு AI இயங்குகிறது.', aiOff: '{name} க்கு AI அணைக்கப்பட்டது.',
    copied: 'நகலெடுக்கப்பட்டது.', copyFailed: 'நகலெடுக்க முடியவில்லை; இணைப்பைத் தேர்ந்தெடுத்து நகலெடுக்கவும்.',
    othersClosed: 'மற்ற எல்லா இடங்களிலும் வெளியேறினீர்கள்.', signedOut: 'வெளியேறினீர்கள்.',
    failed: 'அது நடக்கவில்லை. இணைப்பைச் சரிபார்த்து மீண்டும் முயற்சிக்கவும்.',
    sessionEnded: 'இந்தப் பக்கத்தில் உங்கள் உள்நுழைவு முடிந்தது. சேவையகப் பதிவிலிருந்து புதிய இணைப்பைத் திறக்கவும்.',
    aiHeading: "AI (Google Gemini)",
    aiNotSetUp: "இந்தச் சேவையகத்தில் AI அமைக்கப்படவில்லை. அதைப் பயன்படுத்த, சேவையகத்தின் அமைப்புக் கோப்பில் APP_AI_ENABLED=true என அமைத்து சேவையகத்தை மீண்டும் தொடங்கவும்.",
    aiVertex: "இந்தச் சேவையகம் Google Cloud Vertex AI ஐப் பயன்படுத்துகிறது; அதற்கு Gemini சாவி தேவையில்லை.",
    keyLabel: "Gemini சாவி",
    saveKey: "சாவியைச் சேமி",
    keyHelp: "Google AI Studio இல் Doorprints க்கு மட்டும் தனிச் சாவியை உருவாக்கவும், அப்போது அதை எப்போது வேண்டுமானாலும் அங்கே நீக்கலாம். சாவி குறியாக்கம் செய்து சேமிக்கப்படும், மீண்டும் காட்ட முடியாது.",
    keyLink: "Google AI Studio ஐத் திற",
    removeKey: "சாவியை நீக்கு",
    aiOnServer: "இந்தச் சேவையகத்தில் AI",
    keyFromPage: "Gemini சாவி: இந்தப் பக்கத்தில் அமைக்கப்பட்டது, கடைசி எழுத்துகள் {last4}.",
    keyFromFile: "Gemini சாவி: சேவையக அமைப்புக் கோப்பிலிருந்து, கடைசி எழுத்துகள் {last4}. இங்கே சேமிக்கும் சாவி அதற்குப் பதிலாக இருக்கும்.",
    keyNone: "இன்னும் Gemini சாவி இல்லை: நீங்கள் சேர்க்கும் வரை AI அணைந்திருக்கும்.",
    keySaved: "சாவி சேமிக்கப்பட்டது.",
    keyRemoved: "சாவி நீக்கப்பட்டது.",
    keyInvalid: "Gemini சாவி என்பது இடைவெளி இல்லாத எழுத்துகள், எண்கள், குறியீடுகள் கொண்ட ஒரு வரி.",
    confirmRemoveKey: "Gemini சாவியை நீக்கவா? மீண்டும் சாவி சேர்க்கும் வரை AI நிற்கும்.",
    aiResumed: "இந்தச் சேவையகத்தில் AI இயங்குகிறது.",
    aiPausedMsg: "முழுச் சேவையகத்துக்கும் AI நிறுத்தப்பட்டுள்ளது.",
  },
  te: {
    title: 'Doorprints యజమాని పేజీ', language: 'భాష',
    signInHeading: 'సైన్ ఇన్ చేయండి',
    signInBody: 'మీ సర్వర్ ప్రారంభమైనప్పుడు తన లాగ్‌లో రాసిన ఒక్కసారి లింక్‌ను తెరవండి. అది గంటలోపు ఒక్కసారి మాత్రమే పనిచేస్తుంది.',
    signInHow: 'Docker తో: docker compose logs api నడిపి "Doorprints owner page" కోసం చూడండి. కొత్త లింక్ కోసం సర్వర్‌ను మళ్లీ ప్రారంభించండి.',
    connectHeading: 'పరికరాన్ని కనెక్ట్ చేయండి',
    connectBody: 'పరికరంలో కనెక్షన్ (ఫోన్‌లో: సెట్టింగ్‌లు) తెరిచి ‘కోడ్ తీసుకోండి’ ఎంచుకోండి. అది చూపే కోడ్‌ను ఇక్కడ టైప్ చేయండి.',
    codeLabel: 'పరికరంలోని కోడ్', find: 'వెతకండి',
    deviceAsking: 'కనెక్ట్ అవ్వడానికి అడుగుతున్న పరికరం:',
    checkName: 'ఇది మీ ముందున్న పరికరం అయితేనే ఆమోదించండి.',
    approve: 'ఆమోదించండి', deny: 'నిరాకరించండి',
    qrHeading: 'QR కోడ్‌తో పరికరాన్ని జోడించండి',
    qrBody: 'ఫోన్ కెమెరాతో స్కాన్ చేయండి: Doorprints యాప్ తెరుచుకుని కనెక్ట్ అవుతుంది. ఇది 10 నిమిషాల్లో ఒక్కసారి మాత్రమే పనిచేస్తుంది, కాబట్టి మీ పరికరానికి మాత్రమే చూపండి.',
    makeQr: 'QR కోడ్ తయారు చేయండి', validUntil: 'ఈ సమయం వరకు చెల్లుతుంది',
    openWeb: 'ఈ సర్వర్‌తో కనెక్ట్ అయిన వెబ్‌సైట్‌ను తెరవండి', copyWeb: 'వెబ్‌సైట్ లింక్‌ను కాపీ చేయండి',
    devicesHeading: 'పరికరాలు',
    devicesBody: 'ప్రతి పరికరానికి సొంత కీ ఉంది. AI అభ్యర్థనలు మీ Gemini కీని వాడతాయి, కాబట్టి కొత్త పరికరానికి మీరు ఇక్కడ ఆన్ చేసేవరకు AI ఆఫ్‌లో ఉంటుంది.',
    noDevices: 'ఇంకా పరికరాలు లేవు.',
    added: 'జోడించినది', lastUsed: 'చివరిగా వాడినది', never: 'ఇంకా లేదు', revokedOn: 'రద్దు చేయబడింది',
    viaCode: 'కోడ్‌తో', viaInvite: 'QR కోడ్ లేదా లింక్‌తో',
    ai: 'AI', revoke: 'రద్దు చేయండి',
    confirmRevoke: '{name} ను రద్దు చేయాలా? దాని కీ వెంటనే పనిచేయడం ఆగిపోతుంది; పరికరం మళ్లీ కనెక్ట్ అవ్వాలి.',
    browsersHeading: 'ఈ పేజీలో సైన్ ఇన్ అయిన బ్రౌజర్లు',
    thisBrowser: 'ఈ బ్రౌజర్', signOutThis: 'సైన్ అవుట్',
    addBrowser: 'మరో బ్రౌజర్‌ను జోడించండి', othersOut: 'మిగతా అన్నిచోట్ల సైన్ అవుట్ చేయండి', signOut: 'సైన్ అవుట్',
    browserLinkBody: 'ఈ లింక్‌ను మరో బ్రౌజర్‌లో గంటలోపు ఒక్కసారి తెరవండి:', copyLink: 'లింక్‌ను కాపీ చేయండి',
    footer: 'Doorprints GNU AGPL వెర్షన్ 3 కింద స్వేచ్ఛా సాఫ్ట్‌వేర్, ఎలాంటి వారంటీ లేదు.',
    signedIn: 'సైన్ ఇన్ అయ్యారు.', linkUsed: 'ఈ లింక్ ఇప్పటికే వాడబడింది లేదా గడువు ముగిసింది. కొత్త లింక్ కోసం సర్వర్‌ను మళ్లీ ప్రారంభించండి.',
    approved: 'ఆమోదించబడింది. పరికరం కొన్ని సెకన్లలో కనెక్ట్ అవుతుంది.', denied: 'నిరాకరించబడింది.',
    wrongCode: 'ఈ కోడ్‌తో ఏ పరికరమూ ఎదురుచూడటం లేదు. కోడ్‌ను తనిఖీ చేయండి, లేదా పరికరంలో మళ్లీ ప్రారంభించండి.',
    tooMany: 'చాలా తప్పు కోడ్‌లు. ఒక నిమిషం ఆగి మళ్లీ ప్రయత్నించండి.',
    revoked: 'రద్దు చేయబడింది.', aiOn: '{name} కి AI ఆన్‌లో ఉంది.', aiOff: '{name} కి AI ఆఫ్‌లో ఉంది.',
    copied: 'కాపీ అయింది.', copyFailed: 'కాపీ చేయలేకపోయాం; లింక్‌ను ఎంచుకుని కాపీ చేయండి.',
    othersClosed: 'మిగతా అన్నిచోట్ల సైన్ అవుట్ అయ్యారు.', signedOut: 'సైన్ అవుట్ అయ్యారు.',
    failed: 'అది పనిచేయలేదు. కనెక్షన్‌ను తనిఖీ చేసి మళ్లీ ప్రయత్నించండి.',
    sessionEnded: 'ఈ పేజీలో మీ సైన్ ఇన్ ముగిసింది. సర్వర్ లాగ్ నుండి కొత్త లింక్‌ను తెరవండి.',
    aiHeading: "AI (Google Gemini)",
    aiNotSetUp: "ఈ సర్వర్‌లో AI సెటప్ కాలేదు. దాన్ని వాడాలంటే, సర్వర్ సెట్టింగ్స్ ఫైల్‌లో APP_AI_ENABLED=true పెట్టి సర్వర్‌ను మళ్లీ ప్రారంభించండి.",
    aiVertex: "ఈ సర్వర్ Google Cloud Vertex AI ని వాడుతుంది, దానికి Gemini కీ అవసరం లేదు.",
    keyLabel: "Gemini కీ",
    saveKey: "కీని సేవ్ చేయండి",
    keyHelp: "Google AI Studio లో Doorprints కోసం మాత్రమే వేరే కీ తయారు చేయండి, అప్పుడు దాన్ని ఎప్పుడైనా అక్కడ తొలగించవచ్చు. కీ ఎన్‌క్రిప్ట్ చేసి దాచబడుతుంది, మళ్లీ చూపించలేం.",
    keyLink: "Google AI Studio తెరవండి",
    removeKey: "కీని తొలగించండి",
    aiOnServer: "ఈ సర్వర్‌లో AI",
    keyFromPage: "Gemini కీ: ఈ పేజీలో సెట్ చేసింది, చివరి అక్షరాలు {last4}.",
    keyFromFile: "Gemini కీ: సర్వర్ సెట్టింగ్స్ ఫైల్ నుండి, చివరి అక్షరాలు {last4}. ఇక్కడ సేవ్ చేసే కీ దాని స్థానంలో వస్తుంది.",
    keyNone: "ఇంకా Gemini కీ లేదు: మీరు జోడించేవరకు AI ఆఫ్‌లో ఉంటుంది.",
    keySaved: "కీ సేవ్ అయింది.",
    keyRemoved: "కీ తొలగించబడింది.",
    keyInvalid: "Gemini కీ అంటే ఖాళీలు లేని అక్షరాలు, అంకెలు, గుర్తుల ఒక లైన్.",
    confirmRemoveKey: "Gemini కీని తొలగించాలా? మళ్లీ కీ జోడించేవరకు AI ఆగిపోతుంది.",
    aiResumed: "ఈ సర్వర్‌లో AI ఆన్‌లో ఉంది.",
    aiPausedMsg: "మొత్తం సర్వర్‌కి AI నిలిపివేయబడింది.",
  },
};

const LANG_KEY = 'doorprints.owner.lang';
let lang = pickLang();
let overview = null;

function pickLang() {
  try {
    const saved = localStorage.getItem(LANG_KEY);
    if (saved && TEXT[saved]) return saved;
  } catch (e) { /* storage blocked: fall through */ }
  for (const l of navigator.languages || [navigator.language || 'en']) {
    const base = String(l).slice(0, 2).toLowerCase();
    if (TEXT[base]) return base;
  }
  return 'en';
}

function t(key, vars) {
  let s = (TEXT[lang] && TEXT[lang][key]) || TEXT.en[key] || key;
  for (const [k, v] of Object.entries(vars || {})) s = s.replace('{' + k + '}', v);
  return s;
}

function applyText() {
  document.documentElement.lang = lang;
  document.title = t('title');
  for (const el of document.querySelectorAll('[data-t]')) el.textContent = t(el.dataset.t);
  document.getElementById('lang').value = lang;
  if (overview) render(overview);
  renderAi();
}

function say(message) {
  const status = document.getElementById('status');
  status.textContent = '';
  // A new node each time, so the same message twice is announced twice.
  requestAnimationFrame(() => { status.textContent = message; });
}

async function call(method, path, body) {
  const response = await fetch('/owner/api' + path, {
    method,
    credentials: 'same-origin',
    headers: Object.assign({ 'X-Doorprints-Owner': '1' }, body ? { 'Content-Type': 'application/json' } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 401 && path !== '/session') {
    showSignedOut();
    say(t('sessionEnded'));
    throw new Error('signed out');
  }
  return response;
}

function date(iso) {
  if (!iso) return t('never');
  return new Intl.DateTimeFormat(lang, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

function el(tag, attrs, text) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

function showSignedOut() {
  document.getElementById('signed-in').hidden = true;
  document.getElementById('signed-out').hidden = false;
}

let ai = null;

function renderAi() {
  if (!ai) return;
  document.getElementById('ai-not-set-up').hidden = ai.enabledOnServer;
  const vertex = ai.provider === 'vertex';
  document.getElementById('ai-vertex').hidden = !(ai.enabledOnServer && vertex);
  document.getElementById('ai-panel').hidden = !ai.enabledOnServer || vertex;
  document.getElementById('ai-switch-row').hidden = !ai.enabledOnServer;
  document.getElementById('ai-on').checked = !ai.paused;
  const status = ai.keySource === 'owner_page' ? t('keyFromPage', { last4: ai.keyLast4 })
    : ai.keySource === 'settings_file' ? t('keyFromFile', { last4: ai.keyLast4 }) : t('keyNone');
  document.getElementById('ai-key-status').textContent = status;
  document.getElementById('remove-key').hidden = ai.keySource !== 'owner_page';
}

async function loadAi() {
  const r = await call('GET', '/ai');
  if (r.ok) { ai = await r.json(); renderAi(); }
}

async function load() {
  const response = await call('GET', '/overview');
  if (!response.ok) { say(t('failed')); return; }
  loadAi();
  overview = await response.json();
  document.getElementById('signed-out').hidden = true;
  document.getElementById('signed-in').hidden = false;
  render(overview);
}

function render(data) {
  const list = document.getElementById('devices');
  list.replaceChildren();
  document.getElementById('no-devices').hidden = data.devices.length > 0;
  for (const d of data.devices) {
    const li = el('li');
    const info = el('div');
    info.append(el('div', { class: 'name' + (d.revokedAt ? ' revoked' : '') }, d.name));
    const meta = [t('added') + ' ' + date(d.createdAt) + ' (' + t(d.via === 'code' ? 'viaCode' : 'viaInvite') + ')',
      t('lastUsed') + ': ' + date(d.lastUsedAt), '…' + d.keyLast4];
    if (d.revokedAt) meta.push(t('revokedOn') + ' ' + date(d.revokedAt));
    info.append(el('div', { class: 'muted' }, meta.join(' · ')));
    li.append(info);
    if (!d.revokedAt) {
      const controls = el('div', { class: 'row' });
      const label = el('label');
      const box = el('input', { type: 'checkbox', role: 'switch' });
      box.checked = d.aiAllowed;
      box.addEventListener('change', () => setAi(d, box));
      label.append(box, document.createTextNode(' ' + t('ai')));
      label.setAttribute('aria-label', t('ai') + ': ' + d.name);
      const revoke = el('button', { class: 'danger' }, t('revoke'));
      revoke.setAttribute('aria-label', t('revoke') + ': ' + d.name);
      revoke.addEventListener('click', () => revokeDevice(d));
      controls.append(label, revoke);
      li.append(controls);
    }
    list.append(li);
  }
  const sessions = document.getElementById('sessions');
  sessions.replaceChildren();
  for (const s of data.sessions) {
    const li = el('li');
    const current = s.id === data.currentSession;
    li.append(el('div', {}, (s.label || '?') + (current ? ' — ' + t('thisBrowser') : '') + ' · '
      + t('lastUsed') + ': ' + date(s.lastUsedAt)));
    if (!current) {
      const out = el('button', {}, t('signOutThis'));
      out.addEventListener('click', async () => {
        const r = await call('POST', '/sessions/' + s.id + '/revoke');
        say(r.ok ? t('signedOut') : t('failed'));
        load();
      });
      li.append(out);
    }
    sessions.append(li);
  }
}

async function setAi(device, box) {
  const r = await call('POST', '/devices/' + device.id + '/ai', { allowed: box.checked });
  if (!r.ok) { box.checked = !box.checked; say(t('failed')); return; }
  say(t(box.checked ? 'aiOn' : 'aiOff', { name: device.name }));
  load();
}

async function revokeDevice(device) {
  if (!confirm(t('confirmRevoke', { name: device.name }))) return;
  const r = await call('POST', '/devices/' + device.id + '/revoke');
  say(r.ok ? t('revoked') : t('failed'));
  load();
}

async function codeCall(path) {
  const code = document.getElementById('code').value;
  const r = await call('POST', path, { code });
  if (r.status === 429) { say(t('tooMany')); return null; }
  if (r.status === 404) { say(t('wrongCode')); return null; }
  if (!r.ok) { say(t('failed')); return null; }
  return r;
}

async function copy(text) {
  try { await navigator.clipboard.writeText(text); say(t('copied')); } catch (e) { say(t('copyFailed')); }
}

/** Signs in with a setup link's token, if the address has one. Returns false only when a token was refused. */
async function signInFromFragment() {
  const match = /[#&]setup=([A-Za-z0-9_-]+)/.exec(location.hash);
  if (!match) return true;
  // The token leaves the address bar at once, so it is not in the history, a bookmark or a screenshot.
  history.replaceState(null, '', location.pathname);
  const r = await call('POST', '/session', { setup: match[1] });
  if (r.ok) { say(t('signedIn')); return true; }
  showSignedOut();
  say(t('linkUsed'));
  return false;
}

function wire() {
  document.getElementById('lang').addEventListener('change', (e) => {
    lang = e.target.value;
    try { localStorage.setItem(LANG_KEY, lang); } catch (err) { /* not kept */ }
    applyText();
  });
  document.getElementById('code-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const r = await codeCall('/pairings/find');
    if (!r) { document.getElementById('found').hidden = true; return; }
    const found = await r.json();
    document.getElementById('found-name').textContent = found.deviceName;
    document.getElementById('found').hidden = false;
    document.getElementById('approve').focus();
  });
  document.getElementById('approve').addEventListener('click', async () => {
    if (await codeCall('/pairings/approve')) {
      say(t('approved'));
      document.getElementById('found').hidden = true;
      document.getElementById('code').value = '';
      setTimeout(load, 4000);
    }
  });
  document.getElementById('deny').addEventListener('click', async () => {
    if (await codeCall('/pairings/deny')) {
      say(t('denied'));
      document.getElementById('found').hidden = true;
      document.getElementById('code').value = '';
    }
  });
  document.getElementById('key-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = document.getElementById('gemini-key');
    const key = input.value.trim();
    if (!/^[\x21-\x7e]{20,200}$/.test(key)) { say(t('keyInvalid')); return; }
    const r = await call('POST', '/ai/key', { key });
    input.value = '';
    say(r.ok ? t('keySaved') : r.status === 400 ? t('keyInvalid') : t('failed'));
    loadAi();
  });
  document.getElementById('remove-key').addEventListener('click', async () => {
    if (!confirm(t('confirmRemoveKey'))) return;
    const r = await call('POST', '/ai/key/remove');
    say(r.ok ? t('keyRemoved') : t('failed'));
    loadAi();
  });
  document.getElementById('ai-on').addEventListener('change', async (e) => {
    const on = e.target.checked;
    const r = await call('POST', '/ai/paused', { paused: !on });
    if (!r.ok) { e.target.checked = !on; say(t('failed')); return; }
    say(t(on ? 'aiResumed' : 'aiPausedMsg'));
    loadAi();
  });
  document.getElementById('make-qr').addEventListener('click', async () => {
    const r = await call('POST', '/invites');
    if (!r.ok) { say(t('failed')); return; }
    const invite = await r.json();
    const img = document.getElementById('qr');
    img.src = invite.qr;
    img.alt = t('qrHeading');
    document.getElementById('invite-expiry').textContent = date(invite.expiresAt);
    const web = document.getElementById('web-link');
    web.href = invite.webLink;
    document.getElementById('copy-web').onclick = () => copy(invite.webLink);
    document.getElementById('invite').hidden = false;
  });
  document.getElementById('add-browser').addEventListener('click', async () => {
    const r = await call('POST', '/browser-links');
    if (!r.ok) { say(t('failed')); return; }
    const link = await r.json();
    document.getElementById('browser-link-text').textContent = link.link;
    document.getElementById('copy-browser-link').onclick = () => copy(link.link);
    document.getElementById('browser-link').hidden = false;
  });
  document.getElementById('others-out').addEventListener('click', async () => {
    const r = await call('POST', '/sessions/revoke-others');
    say(r.ok ? t('othersClosed') : t('failed'));
    load();
  });
  document.getElementById('sign-out').addEventListener('click', async () => {
    await call('POST', '/sign-out');
    overview = null;
    showSignedOut();
    say(t('signedOut'));
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  applyText();
  wire();
  try {
    if (await signInFromFragment()) await load();
  } catch (e) {
    if (e.message !== 'signed out') say(t('failed'));
  }
});
