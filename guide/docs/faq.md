# Troubleshooting and FAQ

**"Your data may not be kept."** The website keeps your houses inside the browser itself (browser storage). Some
browsers may delete a website's data when space runs low. To protect it:

1. Choose **Save a backup**, or on **Your data** choose **Ask the browser to keep my data**.
2. Install the website as an app. This also helps.
3. On iPhone and iPad, Safari can delete a site's data if you have not used it for a while. Add Doorprints to the
   Home Screen, and save a backup regularly.

![The storage warning under the website's menu: Your data may not be kept, with Save a backup and Not now](images/web-storage-warning.png)

**The map is blank or grey.** The map needs an internet connection. Your houses are still in the list. On the
website you can still add a house offline with **Add at my location** or **Type latitude and longitude**.

**I don't see Ask, Plan or Assistant.** They appear once AI is turned on: on a phone, with
[your own Gemini key](settings-and-privacy.md#ai-without-a-server-android-and-iphone), no server needed; or through
[your own server](set-up-a-server.md). With a server, if AI is on but they still don't appear, see its
[troubleshooting table](set-up-a-server.md#if-something-goes-wrong).

**Where do I get an API key?** An API key is a long password. You make the Doorprints API key yourself when you set
up your server. The Gemini key for AI is free and comes from Google.
[Two different keys](set-up-a-server.md#two-different-keys) explains which goes where.

**Do I need both keys at once?** No. You need the Doorprints API key from the start: the server won't run without it.
The Gemini key is only for AI. You can add it any time later
([step 5](set-up-a-server.md#step-5-write-the-servers-settings-file)).

**Can I move my houses from the website to the Android app?** Yes. On the website, **Save a copy** as a **Full
backup (ZIP)**. Then on Android use **Import a backup**. Or connect both to the same server.

**I added the same house twice.** Open the extra one and choose **Delete house**.

**Does Doorprints work offline?** Yes. The website works offline after your first visit. The Android and iPhone apps
always work offline. Changes reach your server the next time you are online.

**Can I remove everything from this browser?** Yes. On **Your data**, **Remove all data** deletes every house, visit
and photo kept in this browser. Save a backup first if you want to keep them.
