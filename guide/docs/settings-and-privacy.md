# Language, theme and privacy

## Language and theme

Doorprints speaks English, हिन्दी, தமிழ் and తెలుగు. The Hindi, Tamil and Telugu wording is still being reviewed.

- **Website:** pick the language in the menu at the top right.
- **Android:** open **Settings**, then **Language**. **System default** follows your phone's language.
- **iPhone:** Doorprints uses your iPhone's language. **Open iPhone Settings** lets you pick one just for Doorprints.

Light and dark themes follow your device's setting on all three.

## Your privacy and the map

- Your houses, visits and photos stay in your browser or on your phone, and on your own server if you connect one.
  There is no Doorprints account and no advertising.
- The map pictures come from OpenFreeMap. On the website, **Fill address from map** asks OpenStreetMap's address
  service. On Android, street names come from the phone's built-in address finder (Google's geocoder).
- The AI features send your question and the matching house notes to Google Gemini: through your server, or, with
  your own Gemini key on a phone, straight from the phone. The contact names and phone numbers saved with a house are
  left out.
- Hunt mode's location stays on your phone.
- India's boundaries on the map are shown as the Government of India depicts them.

## AI without a server (Android and iPhone)

You can use **Ask**, **Plan** and **Fill in from listing text** without running a server. Your phone then asks Google
Gemini itself, with a key of your own. It is free on Google's free tier.

1. Make a key: open [Google AI Studio](https://aistudio.google.com/apikey), sign in with a Google account and choose
   **Create API key**. Make one just for Doorprints, so you can delete it there at any time.
2. In Doorprints, open **Settings** and find **AI features**.
3. Turn on **Use AI features on this phone**. Read what it says is sent to Google.
4. Choose **Use my own Gemini key on this phone**, paste the key and tap **Save key**. Doorprints checks the key with
   Google first, then says **Google accepted this key**.

The key stays on your phone, locked (encrypted), and goes only to Google. **Test key** checks it again; **Remove key**
forgets it. If you also connect a server, you choose which one answers: **Use my server** or your own key.

!!! warning "Google's free tier"
    On the free tier, Google may use what you send to improve its products, and people may read it. Keep personal
    details out of your notes, or turn on billing for the key in Google AI Studio (see
    [the paid tier](set-up-a-server.md#the-paid-tier-a-small-cost-more-privacy)).
