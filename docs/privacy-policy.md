# Vicu privacy policy

Last updated: 26 October 2026

Vicu is a task manager for Android, iPhone and iPad that works with a [Vikunja](https://vikunja.io/) server you choose. It is made by Rendy Jansen as an open-source project ([source code](https://github.com/rendyhd/Vicu-Mobile)).

## The short version

Vicu does not collect your data. The developer runs no server for Vicu and receives nothing from the app: no account, no analytics, no crash reports, no advertising and no tracking. The optional assistant sends your words to an AI provider you choose yourself, with your own key, and only after you agree (see below).

## What the app stores and sends

- **Your tasks, projects, labels, comments, attachments and settings** are sent to and stored on the Vikunja server whose address you enter when you sign in. That server is run by you, your organisation or a hosting provider you chose, and its own privacy policy applies to the data on it.
- **A copy of that data** is kept on your device so the app works offline. Changes you make offline wait on the device until they reach your server.
- **Your sign-in** (a session token, or an API token you paste in) is stored on the device, encrypted (the Android Keystore; the iOS Keychain). Your password is sent only to your Vikunja server when you sign in, and is not stored.
- **Images you open** in the app, such as people's avatars, are loaded from your Vikunja server.
- **Links in task descriptions** open in your browser only when you tap them.
- **Saved places and location reminders**: where your places are (Home, Work, ...) stays on your device. It is not sent to your Vikunja server or anywhere else, and it is excluded from backups. The names of your saved places, and which task reminds you at which place, are stored on your Vikunja server with Vicu's other synced settings, so your other devices can use them. A reminder at a place you did not save stays on the device.
- **Place search**: when you search for a place for a location reminder, the text you type is sent to a search service, together with your approximate position (rounded to about a kilometre on Android) so nearby places come first. On Android that is [Photon](https://photon.komoot.io/) by komoot, which uses OpenStreetMap data; on iPhone and iPad it is Apple Maps. Nothing else is sent with it.
- **Voice input**: when you hold the + button or turn a microphone on in the app, Vicu turns what you say into text. By default the phone's own speech recognition does this (Apple's on iPhone and iPad, the speech service of your Android phone), which may process the audio on the device or at Apple or Google under their own privacy terms. With a speech model downloaded in Settings, Assistant, the speech is recognised on the device and the audio does not leave it. Recordings are not stored.
- **Speech model downloads**: the speech models are downloaded from [Hugging Face](https://huggingface.co/) when you choose one. Nothing about you or your tasks is sent with the download.
- **The assistant (optional)**: smart dictation, brain dump, Ask and Talk are off until you set up a provider in Settings, Assistant, with your own API key or a server of your own (such as Ollama). When you use them, Vicu sends what you said or typed, together with the context the feature needs, straight from your device to that provider: the names of your projects, labels and saved places, the list you are in, the date, time and time zone, and for Ask and Talk the tasks its lookups return (titles, dates, notes and other details, including the names of people in shared projects). The provider's own privacy policy and terms apply. Audio is not sent unless you turn on the provider's transcription. Hidden sync data, comment texts, attachments, your Vikunja sign-in and server address, and the positions of your places are never sent. Before anything goes to a provider that is not on your own network, Vicu shows what each feature sends and asks you to agree. Your API key is stored on the device, encrypted, and is not synced or backed up; your last few Ask conversations are kept on the device.

Apart from place search, voice input, speech model downloads and the assistant you set up, Vicu sends nothing to any other party. Its other connections are to your Vikunja server, and to your identity provider when you sign in with single sign-on.

## Permissions

- **Notifications and exact alarms**: to remind you of tasks and routines at the time you set. Reminders are planned on the device.
- **Location** (optional, "all the time" / "Always"): only for location reminders, to notify you when you arrive at or leave a place you chose, also when the app is closed. The position is used on the device to recognise those places and is not stored or sent anywhere; the place search above is the only time a position leaves the device. Without this permission everything else works.
- **Microphone and speech recognition** (optional): only while you hold the + button or a microphone in the app is on, to turn what you say into tasks or questions. Without them, everything else works.
- **Network**: to reach your Vikunja server, and the AI provider you set up for the assistant.
- **Files and photos** you pick yourself: to attach them to a task. The app only sees the files you choose.

## Backups

The local database, your sign-in, the assistant's settings, key and conversations are excluded from the phone's cloud backups and device transfers. On iPhone and iPad, widget contents and shares waiting to be added are also excluded from iCloud backups.

## Deleting your data

Signing out (Settings, General) removes the local copy from the device; changes that have not reached your server yet are kept until you discard them, and the app asks before discarding them. Uninstalling the app removes everything it stored on the device. Data on your Vikunja server is deleted through that server, for example by deleting tasks or your account there.

## Children

Vicu is not directed at children.

## Changes

Changes to this policy are published on this page, with the date above.

## Contact

Questions about this policy: open an issue at [github.com/rendyhd/Vicu-Mobile/issues](https://github.com/rendyhd/Vicu-Mobile/issues).
