# Adding tasks by voice

What Vicu offers for adding a task by voice on each platform, how it works, and the options left
for later. The text is read like quick add in every case (`TaskParser`, the same rules as the
desktop): "Call mom tomorrow 3pm #Family @phone" is a task "Call mom" due tomorrow at 15:00 in the
Family project with the label phone.

## How it works

`TextTaskCreator` (`shared/.../util/`) turns one sentence into a task without a screen: the parser
with the user's quick-add settings, the same field rules as the quick add sheet with nothing picked
by hand (`resolveEntryFields`), labels matched by name or created, the default reminder for a due
date, the project the text names or else the Inbox. The task is stored on the device and queued
(`TaskRepository.createQueued`), so the answer comes at once and nothing is lost offline; the sync
sends it. It answers with a confirmation such as `Added “Call mom”, tomorrow, 15:00`.

A place phrase for a saved place sets the task's location reminder (`PlacePhrases`, English): "when
I get home", "when I'm at work", "when I arrive at Mom's" (arrive) or "when I leave work", "when I'm
leaving home" (leave), anywhere in the sentence ("When I get home, call mom", "Call mom when I get
home tomorrow"); the bare "leaving home" and "arriving at work" only at its start or end, where they
cannot be part of the task. "the" or "my" before the place is fine. The quick add on both apps reads the same
phrases as you type (the parser's `TokenType.PLACE`, given the saved places): section 5.3 of the
cross-app contract, with the place cases of `nlp-corpus-v1.json`. The phrase is taken out of the title, and the reply says it:
`Added “Call mom”, when you arrive at Home`, plus a note when location access is not set to Always.
A place that is not saved ("when I get to the supermarket") stays in the title and sets nothing.

Siri itself sends "remind me ..." without an app name to Apple Reminders; iOS has no default app
setting for reminders, so the user names Vicu ("Remind me in Vicu", then "call mom when I get
home").

## In the app: hold +

Holding the + button (`AppFab`) for 400 ms listens in the app, on both platforms. A card over the
screen shows the words as they are recognized, with the parsed parts in the quick add colours and
the fields as pills under them (`voicePreview`, the same reading as `TextTaskCreator`). Letting go
adds the task through `TextTaskCreator` and says what was added on the app snackbar, with Undo
(which deletes the task). The task goes to the list the + was held in, as that list's quick add
would put it (`TaskDefaults`, handed over by the screen's `ScreenFab`): a project screen's project,
the Inbox, a custom list's "add to" project, today's date on Today; Upcoming, Anytime and a tag add
to the Inbox, undated. A project or date in the words wins. The list's project and date show as
pills before anything is said. Routines' + adds a routine, so holding it does not listen.

Sliding the finger down by more than 56 dp arms the cancel ("Let go to cancel"); letting go there
adds nothing. A pause while still holding does not end the task:
Android's recognizer ends an utterance at a pause, and the next one is joined to it. After the
finger lifts the app waits up to 1.5 s for the recognizer's last words, then adds what it has.

A quick tap still opens quick add. Moving the + before the hold time drags it instead, on screens
whose headers take it (`fabDropTarget`): Upcoming's days and their project groups, Today's header
and project groups (an Overdue group takes the project only), Anytime's projects and a project's
subprojects. The headers outline while the + is dragged and fill under it; letting go on one opens
quick add with that day and project, anywhere else does nothing. On other screens a move is left
to the button, as before.

After the fifth task added through the + button's quick add, a hint above the button says "Hold +
to say a task", once (`FabHint`, `FabHintStore`); not at all once a task was added by voice.

A screen reader keeps the tap and gets "Say a task" as a custom action: the card listens without a
hold and has Done (or Add) and Cancel; back closes it. Dragging only saves taps, so it has no
action. With reduced motion (Android animations off, iOS Reduce Motion) the card and hint only
fade, and the microphone's ring brightens with the voice instead of growing.

| | Android | iOS |
|---|---|---|
| Recognizer | `SpeechRecognizer` (the device's, usually the Google app), `AndroidSpeechInput` | `SFSpeechRecognizer` and `AVAudioEngine`, `SpeechRecognizerBridge.swift` behind `AppleSpeechBridge` |
| Permission | `RECORD_AUDIO`, asked on the first hold, never at start | Speech recognition, then the microphone, on the first hold |
| Refused | The system speech screen (`VoiceAddActivity`, no permission needed) | A message with Settings |
| No recognizer | The system speech screen, else quick add | Quick add |

The pieces: `SpeechInput` (the platform seam), `HoldToTalk` (the session: listening, joining
utterances, release, cancel, the message), `FabGesture` (tap, hold and drag told apart by time and
distance, without Compose), `FabController` and `fabGestures` (the + button's pointer handling, in
the initial pass so a tap reaches the button untouched), `FabDropTargets` (the headers and the hit
test), `FabOverlays` (the card, the dragged +, the hint). Tests: `FabGestureTest`,
`FabDropTargetsTest`, `FabHintTest`, `HoldToTalkTest`, `VoicePreviewTest`.

A place phrase works here too: "call mom when I get home" sets the location reminder, as above.

## iOS

- **Siri**: "Add a task in Vicu" (also "New Vicu task", "Add to Vicu", "Remind me in Vicu").
  Siri asks "What's the task?", the user says it, and Siri reads back what was added. Two turns:
  App Shortcut phrases cannot carry free text, so the task cannot be part of the first sentence.
  `AddTaskIntent` (`iosApp/Vicu/`) runs in the app's process without opening it
  (`openAppWhenRun = false`) and is allowed on a locked phone (`.alwaysAllowed`: it adds and
  reads nothing back), so it works hands-free and in CarPlay.
- **Shortcuts and the Action Button**: "Dictate Text" followed by Vicu's "Add a task" with the
  dictated text as its Task adds in one step.
- **In the app**: hold + (above), or the keyboard's microphone in quick add.

## Android

- **Quick add widget** (2x1): the mic opens the system speech screen ("What's the task?") over the
  home screen, adds the task without opening the app and shows the confirmation as a toast; + opens
  quick add. The Tasks widget has the same mic next to its +.
- **App icon shortcuts** (long-press): "Add task" (quick add) and "Add by voice".
- **In the app**: hold + (above), or the keyboard's microphone in quick add.
- `VoiceAddActivity` uses `RecognizerIntent`, the system's speech screen, so it needs no
  microphone permission (only holding + does). Without a speech service it opens quick add instead.

## With the assistant

With smart dictation on (Settings, Assistant; `docs/ai-assistant-v1.md`), letting go of + sends
the words to the user's own model instead of adding one task: it turns a ramble into several
draft tasks (`DictationSheet`), checked against the same parser and fields as quick add, to
change by hand or by holding the microphone again ("make the first one Friday") before Add. When
the model cannot be reached the words wait on the card with Retry and Add as one task, which is
the path above. With a speech model chosen there (Whisper or Parakeet through whisper.cpp), the
words are recognised on the phone (`SelectingSpeechInput`, `LocalSpeechSession`) instead of by
the system recognizer, and the card waits for the last phrase after the finger lifts.

## For later

- **One sentence on iOS (Siri AI)**: iOS 27 has an App Schema domain for reminders
  (`.reminders`, `createReminder` with a title, due date, list and tags). Adopting it lets
  "Add call mom tomorrow to Vicu" work in one sentence where Siri AI is available: iPhone 15 Pro
  or newer, English first, not in the EU on iOS at launch (September 2026). It needs Xcode 27 and
  an `@available(iOS 27, *)` intent beside `AddTaskIntent`, which stays for everyone else.
- **Gemini on Android**: Google Assistant's App Actions are gone from phones and Gemini does not
  run them. Gemini reaches third-party apps through AppFunctions (`androidx.appfunctions`,
  Android 16 and later), still a private preview for trusted testers in mid 2026. An `addTask`
  AppFunction would call `TextTaskCreator`; it is worth building once Google opens the program.
  Android Auto has no category for task apps, so there is no in-car voice path on Android today.
- **Parsing spoken text**: dictation writes "3 p.m.", "3 PM" or "hashtag errands" where typing
  gives "3pm" and "#errands". The parser and the NLP corpus are shared with the desktop
  (`test-fixtures/nlp-corpus-v1.json`), so spoken forms go in the corpus first, in both repos.
