# Schools as well as houses {#more-than-houses-experimental}

Doorprints began as a notebook for house hunting, and on the website the same app now keeps track of **schools** too.
Schools are **off** until you show them, so the website opens on your houses as it always did. On the phones, schools are
still a **preview**: they are off unless you turn on **Experimental: more than houses**, and the preview may change.

A school you make on the website shows as a house on a phone until **Experimental: more than houses** is on there. The
phone keeps it, and draws it as a school once the switch is on.

## Turn it on

- **Website:** open **Your data** and find **What you track**. A row for **Houses** and one for **Schools** are there. Turn on **Show Schools**. There is no preview switch on the website.
- **Android and iPhone (preview):** open **Settings** and find **What you track**; **Experimental: more than houses** is there, off, and the app is the house app until you turn it on. Then a row for **Houses** and one for **Schools** appear, and you turn on **Show Schools**.

**Default** marks the one Doorprints assumes when it cannot ask: a listing you share into Doorprints, or a link that
adds a new entry. Houses are the default until you choose another.

- One always stays on.
- What you choose stays on this browser or phone. It is not synced, shared or put in a copy.

## What are you looking for?

With more than one shown, Doorprints opens with **What are you looking for?**: one card for **Houses** and one for
**Schools**, each saying how many are saved and when one last changed. Tap a card to open it. Everything there is that
card's: the list and the map, the numbers above the list, the search, **Add school** or **Add house**, and the empty
list's words. The first time, a short note says how it works; **Got it** puts it away.

To switch, use the button at the top with the name of what you are looking at (**Schools ▾**): it takes you back to
the cards. **Back** from the list does too.

While only one is shown, there is no choice to make: Doorprints opens straight into it, as it always did.

On the phone the button sits at the top of the Map, the list, Compare and Settings. **Hunt mode** works in each space: the Map's Hunt card is there while you look at houses and while you look at schools, and its alerts, the notification that shows while it runs and its lines say *house* or *school* as the space you started it in does (a house alerts you from 30 m, or the distance you set in Settings; a school from 100 m). Tapping an *Are you at a school?* alert makes a school. In **Settings**, **Brokers** and **Criteria** are for houses and are not shown while you look at schools; **Questions** and **Viewings** are shown for schools too, in the school's own words. A walk belongs to the space you started it in: if **Hunt mode** is running when you switch to another space, it stops, and Doorprints says so in one line (*Hunt mode stopped. It runs in the Houses space only.*, or *…Schools space only.*); turn it on again from the Map in the space you want. On the website, **Your data** does the same for the **Criteria** card, and its **Questions** and **Viewings** cards speak of the school you are looking at.

## What a school has

A school's page has its name, the place, the area notes, the distances to your places, your saved walks, your visits
and photos, the same as a house. Instead of the rent or price, rooms and moving in,
it has:

- **Board** (CBSE, ICSE, State board, IB, Cambridge (IGCSE) or Other) and **Medium of instruction**;
- **Grades offered**, **Commute (minutes)** and **School bus**;
- **Fees** per year and the **Admission fee**;
- a **Checklist** of eight things to look at, each scored from 0 to 5: safe drop-off and pick-up, classrooms and playground, drinking water and toilets, teachers who are approachable, class size, homework and daily schedule, sports and activities, and safety and CCTV. This list is a first draft and its words may change.
- **School visits**: plan a visit or an open day, with a reminder if you want one, and see the ones coming up and the ones you have done; on Android the reminder is on its own notification channel, **School visit reminders**;
- **Questions for a visit**: six questions to bring (the fees and what they cover, how admission works, class size, safety, water and toilets, homework, hours and holidays), which you can change, add to or reset; a school's questions are its own and never mix with the questions you ask about houses, and they are added to your list only when you first open them. This list is a first draft too.

The search finds a school by these too (the board, "school bus", the fees). The readable copies list them under the school's own headings (the fees under **Fees**), say *school* where a house copy says *house*, and in a copy of schools leave out the price, score, BHK and rooms that a school does not have; a backup
keeps them. **Compare**, **Ask** and **Plan** work on the list you are looking at: while you look at schools they compare and ask about schools only. **Ask** and **Plan** need your own AI key for schools (the server's assistant reads houses only), and **Save a copy** holds only the schools you are looking at, with *schools* in the file name; the Full backup holds everything. A listing you share to Doorprints has **Save it in**, to choose Houses or Schools before it is read. The filter rows above (**Board**, **Medium of instruction**, **School bus**) are the school's own list of what to filter by, and a copy of schools names a school's visits and questions **School visits** and **Questions for a visit**.

## Filter, compare and copy {#filter-compare-copy}

On the website, the list of schools has a row of buttons for each of **Board**, **Medium of instruction** and **School bus**, under the status buttons. Press **CBSE** to see only those schools; **Any** puts the row back. The choices stay in the page address, so a bookmark or **Back** brings them back. The list of houses has no such rows. The phone has the same rows under its status chips; it has no page address, so they start on **Any** again when you open the app.

**Compare** marks the best value in a row; for schools, the lower commute and the lower fees are the best, and a higher checklist score is better. A school's **Compare** counts the eight checklist items, the fees and the commute, each as much as another; the admission fee is shown and not marked. **Criteria** (your own weights) are for houses. **Your data** has **Save a copy of**, with the lists you show, so you can save a copy of the other list without going back to the cards. While more than houses are shown, the **Full backup** and **Import a backup** say *entries* instead of *houses*, because they hold every list.

## Turn it off again

Turn off **Show Schools** (on a phone, you can also turn off **Experimental: more than houses**). Doorprints opens on your houses again. Nothing is
deleted: the schools you saved stay, a backup keeps them, and they come back when you show them again.

You can turn off **Show Houses** instead and keep only Schools. Your houses are not hidden or deleted: with one list shown
Doorprints opens straight into it, and it still holds your houses, their **Recently deleted** and any backup, and **What you
track** still lists **Houses** with how many are saved. A new entry is a school. *Start Hunt mode* from a viewing reminder or an area wake-up starts Hunt mode in Schools, because Schools is the list that is shown. Viewing reminders you already set still come. Turn **Show Houses** on again and
everything is as it was.

## Backups

A backup made while more than houses are shown lists them (`"search": {"kinds": ["house", "school"], "defaultKind":
"house"}` in its `data.json`), and each school in it says `"kind": "school"`. **Import a backup** never refuses a file
because of it and never changes what is in it. On a browser or phone that never chose what it tracks, the backup's list is used;
otherwise your own choice stays. Older backups import as before.

## With your own server

Through your own server, the AI reads a school's listing text and fields as a school's only when the server allows it.
On your server, add this line to the `.env` file (see [Set up your own server](set-up-a-server.md)), then run
`docker compose up -d`:

```ini
KINDS_AVAILABLE=house,school
```

Without it, the server reads every saved entry as a house, and **Fill in from listing text** on a new school shows an
error. Your own AI key on this device needs no server setting.
