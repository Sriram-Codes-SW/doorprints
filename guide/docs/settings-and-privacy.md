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
- The map pictures come from OpenFreeMap. On the website, **Fill address from map** and **Find "<area>" on the map**
  ask OpenStreetMap's address service, only when you tap them. On the phones, street and place names come from the
  phone's built-in address finder (Google's on Android, Apple's on iPhone).
- The AI features send your question and the matching house notes to Google Gemini: through your server, or, with
  your own Gemini key, straight from your phone or browser. The contact names and phone numbers saved with a house are
  left out.
- Hunt mode's location stays on your phone.
- India's boundaries on the map are shown as the Government of India depicts them. The northern and north-eastern
  boundary is the Survey of India's own line: the map credits **Boundary: Survey of India**, and **About** gives the
  full acknowledgement.

## Save an area of the map for offline

On the **Map**, zoom to the neighbourhood you are hunting in and tap the **download** button (the arrow into a tray,
next to the zoom buttons). Doorprints tells you how much it will download (a neighbourhood is a few MB, a whole
city a few tens of MB), warns you if you are on mobile data, and suggests a name. Tap **Save**. From then on that
area draws down to street level with no network, your houses on it. The saved areas are in **Settings** under
**Offline maps**, with their size, where you can delete one. An area that is too large is refused: zoom in and try
again. Map data © OpenStreetMap contributors, tiles from OpenFreeMap.

**On the website** it works the same way: on the **Map**, choose **Save this area for offline**, check the size and
the room left in the browser, and choose **Save area**. The saved areas are on **Your data** under **Offline maps**.
They stay in this browser only; **Remove all data** removes them too.

## Lock Doorprints on your phone

If you share your phone, or hand it to someone to show a house, you can make Doorprints ask for your phone's own
screen lock (PIN, pattern, password, fingerprint or face) before it opens.

1. Open **Settings** and find **Privacy**.
2. Turn on **Lock Doorprints**. Your phone asks you to confirm it is you.
3. Choose when it locks again after you leave it: **Right away**, **1 minute**, **5 minutes** or **15 minutes**.

While the lock is on, Doorprints is hidden in the phone's list of recent apps. Doorprints keeps no PIN of its own, so
there is nothing new to remember. Your phone needs a screen lock first: set one in the phone's settings. On Android,
while the lock is on, Hunt mode's alerts show nothing on the phone's locked screen. The website has no lock; on a
shared computer, use **Remove all data** on **Your data** when you are done.

## AI without a server

You can use **Ask**, **Plan** and **Fill in from listing text** without running a server. Your phone or browser then
asks Google Gemini itself, with a key of your own. It is free on Google's free tier.

### On Android and iPhone

1. Make a key: open [Google AI Studio](https://aistudio.google.com/apikey), sign in with a Google account and choose
   **Create API key**. Make one just for Doorprints, so you can delete it there at any time.
2. In Doorprints, open **Settings** and find **AI features**.
3. Turn on **Use AI features on this phone**. Read what it says is sent to Google.
4. Choose **Use my own Gemini key on this phone**, paste the key and tap **Save key**. Doorprints checks the key with
   Google first, then says **Google accepted this key**.

The key stays on your phone, locked (encrypted), and goes only to Google. **Test key** checks it again; **Remove key**
forgets it. If you also connect a server, you choose which one answers: **Use my server** or your own key.

### On the website

1. Make a key in [Google AI Studio](https://aistudio.google.com/apikey), as in step 1 above.
2. Open **Your data**, then **Connect** (on a computer, **Connect** is in the menu at the top).
3. Under **AI features**, turn on **Use AI features in this browser**.
4. Choose **Use my own Gemini key in this browser** (with no server, it is already chosen), paste the key and select
   **Save key**. Doorprints checks the key with Google first.

The key stays in this browser and goes only to Google. It is forgotten when you close the tab, unless you tick
**Remember on this device**; leave that off on a shared computer. **Remove all data** on **Your data** also removes it.

!!! warning "Google's free tier"
    On the free tier, Google may use what you send to improve its products, and people may read it. Keep personal
    details out of your notes, or turn on billing for the key in Google AI Studio (see
    [the paid tier](set-up-a-server.md#the-paid-tier-a-small-cost-more-privacy)).
