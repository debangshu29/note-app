# Note — smart notes app for Android

All your notes in one place, with three smart features built in.
Everything is stored **only on your phone** — no account, no server, no database. Your notes are never uploaded.
The only thing the app downloads from the internet is its own updates (from this repo's Releases).

## What it does (Phase 1)

| Feature | How it works |
|---|---|
| **All notes in one place** | Home screen lists every note (pinned ones first). Search box looks through notes and reminders. |
| **Auto math** | Type `1+1=` and `2` appears by itself. Type `450+120+80` (no `=`) and ` = 650` appears in faded grey right after it, like Gmail suggestions — tap it or swipe it right to keep it. The grey is a light shade of your text colour. Works with `+ - * / x × ÷ ^ % ( ) √`, decimals and `1,000`. |
| **Reminders that fit the note** | ⏰ `wake me up at 10 am tomorrow` → a real alarm (Stop / Snooze, full screen), **not** added to the calendar. 📅 `call mom at 5 pm on 12 Nov`, `exam on 5 Jan` → phone calendar entry + **one** notification (the calendar entry itself stays silent). 🔔 `drink water in 2 hours`, `buy milk at 8 pm` → just one notification. Tap the chip under the note to change the type or turn it off. A reminder keeps the time it was written for ("tomorrow" stays that day). |
| **Reminders tab** | Everything coming up, grouped by Today / Tomorrow / date, plus Missed and Done. Tick to finish. |
| **Checklists and lists** | Type `[]` + space for a ☐ checkbox, tap the box to tick it ☑. `- `, `• ` and `1. ` lists continue when you press Enter. Toolbar buttons for checklist, bullet, and a date/time picker. |
| **Share into Note** | Share text from any app (WhatsApp, Chrome...) and choose Note: it becomes a new note. |
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

**In-app updates (from version 1.2):** the app checks this repo's latest release about twice a day.
When a newer version is ready you get a notification / popup → **Update** → Android asks "Update this app?" → done.
Notes, reminders and settings stay. The first time, Android asks you to allow "Install unknown apps" for Note.
Turn it off or check manually in Settings → App updates.

To publish an update: raise `versionCode` / `versionName` in `app/build.gradle`, write the changes in
`whats-new.txt`, and push. GitHub builds it and the phones see it.

From version 1.1 every APK from GitHub is signed with the same key (kept in GitHub Secrets, backup in `D:\note\signing`),
so a new `Note.apk` installs on top of the old one and your notes stay. Version 1.0 has to be uninstalled once.

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
