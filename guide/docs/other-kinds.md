# More than houses (experimental)

Doorprints is made for house hunting, and that is all most people need. This page is about an early preview that lets
the same app keep track of **schools** too. It is **off** unless you turn it on, it is not in the published app yet, and
it may change or go away.

## Turn it on

- **Website:** open **Your data** and find **What you track**.
- **Android and iPhone:** for now in a preview build only (the published app offers houses alone). Open **Settings** and find **What you track**; it appears only in a build that ships more than houses.

Turn on **Experimental: more than houses**. A row for **Houses** and one for **Schools** appear. Turn on **Show Schools**.
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

On the phone the button sits at the top of the Map, the list, Compare and Settings. **Hunt mode** is for houses: the Map's Hunt card is only there while you look at houses. In **Settings**, **Brokers**, **Criteria**, **Questions** and **Viewings** are for houses too, and are not shown while you look at schools.

## What a school has

A school's page has its name, the place, the area notes, the distances to your places, your saved walks, your visits
and photos, the same as a house. Instead of the rent or price, rooms, questions, viewings, moving in and the checklist,
it has:

- **Board** (CBSE, ICSE, State board, IB, Cambridge (IGCSE) or Other) and **Medium of instruction**;
- **Grades offered**, **Commute (minutes)** and **School bus**;
- **Fees** per year and the **Admission fee**.

The search finds a school by these too (the board, "school bus", the fees). The readable copies list them, and a backup
keeps them. **Compare**, **Ask** and **Plan** work on the list you are looking at: while you look at schools they compare and ask about schools only. **Ask** and **Plan** need your own AI key for schools (the server's assistant reads houses only), and **Save a copy** holds only the schools you are looking at, with *schools* in the file name; the Full backup holds everything. A listing you share to Doorprints has **Save it in**, to choose Houses or Schools before it is read.

## Turn it off again

Turn off **Show Schools**, or **Experimental: more than houses**. Doorprints opens on your houses again. Nothing is
deleted: the schools you saved stay, a backup keeps them, and they come back when you show them again.

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
