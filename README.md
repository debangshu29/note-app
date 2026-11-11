# Note — smart notes app for Android

All your notes in one place, with three smart features built in.
Everything is stored **only on your phone** — no account, no server, no database. Your notes are never uploaded.
The only thing the app downloads from the internet is its own updates (from this repo's Releases).

## What it does (Phase 1)

| Feature | How it works |
|---|---|
| **All notes in one place** | Home screen lists every note (pinned ones first). Search box looks through notes and reminders. |
| **Formatting bar** | Above the keyboard: **B** *I* U ~~S~~, **font size in numbers** (8 – 72 or any size; the button shows the current size), **32 font styles** (Android fonts + bundled Google Fonts: Poppins, Nunito, Comfortaa, Oswald, Bebas Neue, Lora, Playfair Display, Roboto Slab, Courier Prime, Dancing Script, Great Vibes, Pacifico, Lobster, Caveat, Indie Flower, Patrick Hand, Kalam, Shadows Into Light, Permanent Marker), **font colour and highlighter with a Word-style colour picker** (theme colours + shades, standard, recent, *More colours…* with any colour / hex code — the button's bar shows the colour you picked), align left / center / right, bullet & numbered lists, checklist, date/time, clear formatting, undo / redo. Long-press any icon to see its name. |
| **Title section** | Big title at the top, edit time and the note's reminder chips right under it. |
| **＋ Insert** (top bar, next to Share) | 📷 take photo · 🖼 gallery (several at once) · 🧾 scan documents (Google ML Kit: edges, crop, clean-up, many pages; optional text recognition) · 📄 PDF pages as pictures and/or editable text · ✏️ drawing (pen, marker, highlighter, eraser, colours, sizes, undo — editable later) · 🗒 sticky notes (any colour from the colour picker) · ▦ tables (add/delete rows & columns, header row) · ⤵ new page. |
| **Pictures** (photos, gallery, scans, PDF pages, drawings) | **Tap** a picture to select it. **Corner handles** resize it and keep its shape; **side handles** stretch it freely. **Drag** the selected picture to move it left/right on the line or up/down through the note (between blocks or between two lines of text; the page scrolls at the edges). **Long-press** for options. All pictures: full screen (pinch / double-tap to zoom, swipe down to close), get text from picture (added under it), full width, original shape, centre, delete. Extra options: **photo** rotate, retake, save to gallery, share · **gallery** rotate, replace, share · **scan** rotate, black & white / back to colour, rescan, share as PDF · **PDF page** rotate, black & white, share · **drawing** edit, duplicate, save to gallery, share. |
| **⋮ menu** | 🔍 **Find & replace** (highlights every match, ↑ ↓ with "3 of 12", replace one / all — in text, sticky notes and tables) · 📑 **Page sorter** (reorder / delete pages) · 🎨 **Note style** (note colour — also on the home screen like a sticky note — and plain / lined / grid / dotted paper) · 📐 **Page settings** (Endless / A4 / Letter / A5, portrait / landscape, narrow / normal / wide margins) |
| **Auto math** | Type `1+1=` and `2` appears by itself. Type `450+120+80` (no `=`) and ` = 650` appears in faded grey right after it, like Gmail suggestions — tap it or swipe it right to keep it. The grey is a light shade of your text colour. Works with `+ - * / x × ÷ ^ % ( ) √`, decimals and `1,000`. |
| **Price lists add up by themselves** | Write items with prices, one per line (`apple 50 rs`, `banana is 20 rs`, `milk 2 litre ₹60`, `mango 3 x 40`). The total shows in grey under the list — tap or swipe to keep it. Or type `total =` / `total:` and it fills in. Any `Total = …` line updates itself when you change, add or delete an item. With checkboxes it shows `₹165 · bought ₹70 · left ₹95`. A blank line starts a new list. Amounts use Indian grouping (₹1,25,000). |
| **Reminders that fit the note** | ⏰ `wake me up at 10 am tomorrow` → a real alarm (Stop / Snooze, full screen), **not** added to the calendar. 📅 `call mom at 5 pm on 12 Nov`, `exam on 5 Jan` → phone calendar entry + **one** notification (the calendar entry itself stays silent). 🔔 `drink water in 2 hours`, `buy milk at 8 pm` → just one notification. Tap the chip under the note to change the type or turn it off. A reminder keeps the time it was written for ("tomorrow" stays that day). |
| **Reminders tab** | Everything coming up, grouped by Today / Tomorrow / date, plus Missed and Done. Tick to finish. |
| **Checklists and lists** | Type `[]` + space for a ☐ checkbox, tap the box to tick it ☑. `- `, `• ` and `1. ` lists continue when you press Enter. Toolbar buttons for checklist, bullet, and a date/time picker. |
| **Sharing** | Share a note (🔗 in the note, or long-press on the home screen) as **text, image or PDF** — image and PDF keep formatting, pictures, sticky notes, tables and pages (PDF uses the note's page settings). For shopping lists choose **all items, only left to buy ☐, or only bought ☑** — the total is recalculated for what you share. |
| **Select several notes** | Long-press a note, tap more, then **Share / Pin / Delete / Select all**. Several notes share as one message, several images, or one PDF. |
| **Anything → note** | Share text, a **photo, screenshot, PDF or text file** to Note (or tap the 📄+ button on the home screen) and its text becomes a new note. Photos/PDFs are read **on the phone** by Google's ML Kit — nothing is uploaded. A photo of a bill becomes a price list that adds itself up. |
| **Your data** | Settings → **Delete all data** erases every note, reminder and setting (and, if you tick the box, the calendar events the app added). **Export backup** / **Restore** saves your notes to a file and brings them back. |
| **Dark mode** | Follows your phone's light / dark setting. |

Dates are read Indian-style: `01/12` means **1 December**. A date with no time uses 9:00 am (changeable in Settings).

## Folder contents

```
D:\note
├── Note.apk            <- the app (once built). Copy to phone and tap to install.
├── BUILD_APK.bat       <- double-click to build Note.apk on this PC
├── README.md           <- this file
├── app\src\main\
│   ├── java\com\debangshu\note\   <- all the code (Java)
│   │   ├── MainActivity.java      home screen + reminders tab
│   │   ├── EditorActivity.java    writing screen (auto math, lists, reminder chips)
│   │   ├── SettingsActivity.java  switches, backup, delete all data
│   │   ├── MathEval.java          the calculator
│   │   ├── DateParser.java        finds dates/times in a line
│   │   ├── ReminderSync.java      keeps reminders in step with the note text
│   │   ├── AlarmScheduler / Notifier / CalendarHelper / *Receiver
│   │   └── Store.java             saves everything to the phone (JSON files)
│   ├── res\                       screens (layouts), colours, icons
│   └── AndroidManifest.xml
└── build.gradle, settings.gradle, gradlew...   <- standard Android Studio project
```

## Building the APK on your PC

You need **Android Studio** installed once (free): https://developer.android.com/studio
(just install it and open it one time so it downloads the Android SDK).

**Easiest:** double-click `BUILD_APK.bat`. The first build downloads build tools (needs internet, a few minutes). When it says *Done*, `Note.apk` is in this folder.

**Or in Android Studio:** File → Open → choose `D:\note` → wait for sync → Build → Build App Bundle(s) / APK(s) → Build APK(s).

## Installing on your phone

1. Send `Note.apk` to your phone (USB cable, WhatsApp to yourself, Google Drive, Bluetooth...).
2. Tap the file. If Android asks, allow **Install unknown apps** for that app (Files / WhatsApp / Chrome).
3. Open **Note**. When you write your first reminder it asks to allow **Notifications** and **Calendar** — allow both.

Works on Android 7.0 and newer.

## Updating the app

**In-app updates:** every time you open Note (at most every 15 minutes) and about twice a day in the
background, it checks for a new version. A popup shows what's new and how to update:
**Update** → Note downloads ONE file (`Download/Note-update.apk`) with a progress bar → **Install** → tap the
file → **Update** (notes stay). Right after the update **Note deletes the file automatically** — Android lets an
app delete files it downloaded itself. **Help** in the popup explains what to do if something fails, and offers
the browser as a backup (files a browser downloads can't be deleted automatically; use
Settings → Delete old update files for those).

Direct download of the newest app: https://raw.githubusercontent.com/debangshu29/note-app/updates/Note.apk

To publish an update: raise `versionCode` / `versionName` in `app/build.gradle`, write the changes in
`whats-new.txt`, and push. GitHub builds it and the phones see it.

From version 1.1 every APK from GitHub is signed with the same key (kept in GitHub Secrets, backup in `D:\note\signing`),
so a new `Note.apk` installs on top of the old one and your notes stay. Version 1.0 has to be uninstalled once.

## Where things are saved

| What | Where |
|---|---|
| Your notes, reminders, settings | Note's private storage (only Note can open it — shown in Settings → Your data) |
| Pictures, scans and drawings inside notes | Note's private storage, `files/media` (deleted with their note) |
| Automatic daily backups with pictures, `.zip` (last 14 days) | `Internal storage/Documents/Note/Backups` |
| Copies of images / PDFs you share | `Internal storage/Documents/Note/Exports` |
| Copies of photos / PDFs / files turned into notes | `Internal storage/Documents/Note/Imports` |
| Downloaded update file | `Internal storage/Download/Note-update.apk` (deleted automatically after updating) |

The `Documents/Note` folder stays even if you uninstall the app, so your backups survive a reinstall
(restore them with Settings → Restore from backup).

## Deleting your data

- One note: open it → 🗑 icon, or long-press it on the home screen → Delete.
- Everything: Settings → **Delete all data**.
- Uninstalling the app also deletes everything it stored.

## Ideas for next phases

- Repeating reminders ("every monday"), and auto-updating math when you edit numbers above
- Note colours / folders / tags, and a calendar month view
- Home-screen widget, voice notes, photos in notes
- App lock (fingerprint / PIN)
- More automatic actions: phone numbers → tap to call, amounts → running total, links → open

## Fonts

The bundled fonts come from [Google Fonts](https://github.com/google/fonts) and are free to include in apps
(SIL Open Font License 1.1 / Apache 2.0). Licence texts: `app/src/main/assets/fonts/LICENSES.txt`.
