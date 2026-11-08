# Note — smart notes app for Android

All your notes in one place, with three smart features built in.
Everything is stored **only on your phone** — no account, no internet, no server, no database.
The app doesn't even ask for internet permission, so it has no way to send your notes anywhere.

## What it does (Phase 1)

| Feature | How it works |
|---|---|
| **All notes in one place** | Home screen lists every note (pinned ones first). Search box looks through notes and reminders. |
| **Auto math** | Type `1+1=` and `2` appears by itself. Type `450+120+80` (no `=`) and a **"= 650 · tap to add"** bar shows above the keyboard. Works with `+ - * / x × ÷ ^ % ( ) √`, decimals and `1,000`. |
| **Automatic reminders** | Write a line with a day or time in it, for example `call mom tomorrow at 5pm`, `submit form on 15 Nov`, `gym on monday 6:30 am`, `pay rent 01/12`, `drink water in 2 hours`. The app sets an alarm notification (with **Done** / **Snooze 10 min**) and adds an event to your phone's calendar. A 🔔 chip under the note shows what it found; tap it to turn that one off. |
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
