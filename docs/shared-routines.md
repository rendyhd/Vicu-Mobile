# Shared routines: design

**Status:** accepted 2026-10-24 and built in both apps on branch `feat/shared-routines` (not
released). The owner took every decision on 2026-10-24; section 13 records them, section 14 what
building it changed.

This proposes how a routine can be shared with another person, so that when either of them does
it for the day it is ticked off for both, on every device of both. It builds on the routine
format (`docs/cross-app-semantics-v1.md` section 6), the hidden-task rules (section 10, and
`docs/hidden-tasks.md`), collaboration (section 9) and the synced preferences (section 11).

## 1. Why

- Households share chores that repeat: feed the cat at 08:00 and 18:00, water the plants every
  three days, put the bins out on Tuesday. Today a routine belongs to one person. When two people
  each keep their own, both get reminded and each has to tick it off, or one person keeps it and
  the other does not know whether it was done.
- A repeating Vikunja task in a shared project already gets "done by one, done for both", but it
  has no times within a day, no history and no skip. That is what routines are for.

## 2. Goals and limits

Goals:

- A routine can be shared with a person the user already shares a project with. A tick, skip or
  undo by either person shows for both.
- Works with Vikunja 2.4, offline first on mobile, with every sign-in method, and with older app
  versions still running. Both apps, in the same release.
- Nothing changes for a routine that is not shared, or for a user who shares nothing.

Not in scope:

- **Creating or sharing a project for it.** Sharing only uses a project the user already shares
  with that person; setting that up is left to the user, in Vicu or in Vikunja (**Decided**
  2026-10-24, S1).
- Choosing people one by one: a shared routine is visible to everyone who can see its project.
- Whose turn it is, rotations, assigning a routine to one person.
- Telling the other person when a routine was done (Vikunja has no push; contract section 9 lists
  what the API has).
- Comments on routines.

## 3. What exists today

- **A routine is one hidden task**, its carrier: a done task holding the definition and every
  occurrence of the last 400 days in a `vicu-routine:v1` marker. Older history is in archive parts
  in the same project (contract 6.4). Vicu creates the carrier in the Inbox project
  (`RoutineRepositoryImpl.create`), and every list hides it, so the user never sees it as a task.
- **An occurrence has no person in it.** Its key is `<routineId>:<date>:<slotId>`, and the merge
  keeps the newest record per key (contract 6.3). If two people's apps read and write the same
  carrier, a tick by either is the tick for both. Nothing in the format has to change for that.
- **Vikunja shares only whole projects**: with users, with teams and by link. Nothing is shared at
  task level (assignees are per task but give no access). So another person can read a routine
  only when its carrier is in a project they can see.
- **The other person's app already finds it.** The full reconcile searches `vicu-routine` in every
  project the user can see (`TaskRefresher`), and a member of a shared project finds the owner's
  carriers there (`docs/hidden-tasks.md`, Finding them). They are then dropped on purpose by the
  ownership rule (contract 10.3), which 2.2 added so that a shared Inbox stops putting one
  person's routines, with their reminders, on another person's phone.

So sharing a routine is a deliberate, opt-in exception to 10.3, not a new way to sync.

### 3.1 Checked against the server

Against the seeded Vikunja 2.4.0 on 2026-10-24, in the project Lisbon (owner `uiverify`, shared
with `uimaya` for writing and `uisam` read only), with one temporary hidden task that was deleted
afterwards:

| Check | Answer |
|---|---|
| `uimaya` sends a merge patch to a hidden task `uiverify` created | 200; `created_by` stays `uiverify` |
| `uisam` (read only) does the same | 403 |
| A merge patch with `If-Match` and an ETag from before another member's write | 200, written: `If-Match` is ignored on `PATCH` |
| A merge patch with `If-Unmodified-Since` in 2024 | 200, written: ignored as well. ETags only serve reads (304) |
| `application/json-patch+json` with `{"op": "test", "path": "/description"}` that does not match, then `replace` | 422 `Unable to apply patch`; nothing written |
| The same with a `test` that matches | 200; written |

The last two rows give a compare-and-swap for carrier writes (7.1). It holds for writes one after
the other, not for writes that arrive at the same moment: see 7.1, "Read back".

## 4. Recommendations at a glance

| # | Question | Recommendation |
|---|---|---|
| 1 | How does the user share a routine? | A "Share with" dropdown of people in the routine editor, listing only people the user already shares a project with, with an info button (6.1). **Decided** |
| 2 | Where does a shared routine live? | Its carrier and archive parts move into a project shared with that person; the user picks the project when more than one qualifies (6.2). **Decided** |
| 3 | What marks it shared? | `definition.shared: true` (5.1) |
| 4 | Who sees it? | Your own routines, and other people's routines marked shared (5.2) |
| 5 | Who can do what? | Everyone with write access logs and edits; the owner stops sharing, archives and deletes (5.3). **Decided** |
| 6 | Who did it? | `loggedBy` on the occurrence record of a shared routine (5.4) |
| 7 | Reminders and hiding per person | Synced preference keys (5.5) |
| 8 | Two people writing at once | Compare-and-swap with a JSON Patch `test` (7.1). A fix that also helps one person's devices |
| 9 | "Not logged" overwriting a late tick | Placeholder records lose every merge (7.2). A fix as well |
| 10 | Reminders for something the other person did | Re-check when the reminder fires, where code runs then (7.3) |

## 5. Rules

These become contract section 6.8 "Shared routines", with changes to 6.3, 6.7, 10.3 and 11.3,
in both repos.

### 5.1 The flag

- `definition.shared`: `true` when the routine is shared. Missing or `false` means private.
- It is part of the definition, so it merges with it (newest `updatedAt` wins, contract 6.3), and
  versions that keep unknown fields (10.8) keep it.
- A routine counts as shared while the flag is set **and** its project has other members
  (contract 9.1). A flagged routine whose project lost its other members behaves as private and
  shows no shared mark.
- Why a flag, and not simply "in a shared project": routines already sit in shared Inboxes today,
  and they must stay private. Without the flag the leak 2.2 fixed comes back.

### 5.2 Who sees a shared routine

- Contract 10.3 gets one exception: **a routine carrier another user created is read, merged and
  written when its definition has `shared: true`.** Being able to read it proves its project is
  shared with you, so no member lookup is needed.
- The archive parts of such a routine are read whoever created them, matched by `routineId`: any
  member who writes may archive old history (5.3).
- Everything else in 10.3 stays: other users' custom-list and preferences carriers, and their
  routines without the flag, are ignored.
- Everyone who can see the project sees the routine: direct shares, members of a team it is
  shared with, and people a parent project is shared with (contract 9.1).
- A shared routine leaves a member's app when the flag goes off or the carrier can no longer be
  read (403 or 404), at the next full reconcile, as other users' carriers do today.
- A member with Routines turned off sees nothing until they turn it on. A member needs no Inbox
  to see or log a shared routine.

### 5.3 Who can do what

The **owner** is the user who created the main carrier (`created_by`).

| | Owner | Member with write access | Read-only member |
|---|---|---|---|
| See it, its status and its history | yes | yes | yes |
| Complete, skip or undo an occurrence, whoever logged it | yes | yes | no: rows show the status without a checkbox (Vikunja answers 403) |
| Edit name, times, schedule and icon | yes | yes (S4) | no |
| Turn its reminders off for themselves (5.5) | yes | yes | no reminders at all: they cannot tick it |
| Hide it for themselves (5.5) | no | yes | yes |
| Stop sharing, archive, delete | yes | no | no |

- Edits by members are safe for the data: the definition merges by `updatedAt`, and 7.1 stops
  writes from overwriting each other.
- Archiving history happens on write (contract 6.2), so a member's write can create an archive
  part. That part belongs to the routine, not to the member (5.2). Deleting the routine deletes
  every part, whoever created it.

### 5.4 Who logged it

- On a shared routine, completing, skipping or undoing an occurrence writes
  `loggedBy: {"userId": <id>, "deviceId": "<the record's modifiedBy>"}` on the record.
- The name is shown only when `loggedBy.deviceId` equals the record's `modifiedBy`. A version
  that does not know `loggedBy` and logs the same slot again keeps the old record's unknown fields
  (`RoutineScheduleEngine.recordFor`, and the desktop the same), so a stale `loggedBy` would credit
  the wrong person. When the two differ, no name is shown.
- Text: "Done by Maya", "Skipped by Maya", "Done by you", with names by contract 9.3. Placeholder
  records (7.2) have no `loggedBy`.
- Only on shared routines (S12): about 60 bytes a record, which adds up to tens of KiB a year
  against the 384 KiB limit. History from before sharing has no names.

### 5.5 Per person

Two keys in the synced preferences (contract 11.3), so they follow the person to every device and
never reach the other person:

| Key | Value | Default |
|---|---|---|
| `routine_reminders.<routineId>` | `"off"`, or `null` | on: the routine's own reminder settings |
| `routine_hidden.<routineId>` | `true`, or `null` | shown |

- The reminder times themselves belong to the routine and are shared: a member who moves the
  08:00 slot to 07:30 moves it for both.
- A hidden shared routine leaves that person's Routines, Today, widgets and reminders. The
  Routines section of Settings (General) lists the hidden ones so they can be shown again (S6).
- A newly shared routine reminds the other person straight away, by the routine's settings, until
  they turn it off (S5).

### 5.6 Stopping, and when the project changes

- **Stop sharing** (owner): the flag goes off, and the carrier and its archive parts move back to
  the owner's Inbox. Members' apps drop the routine at their next full reconcile; a tick they still
  had queued answers 403 or 404 and goes to the failed log.
- **The other person loses access to the project**: the routine leaves their app. The owner keeps
  it, still flagged, without a shared mark (5.1).
- **The owner loses access** (the project is the other person's, who removes the owner or deletes
  it): the owner loses the routine. The info text says so, and the project choice (6.2) shows
  which projects are the user's own.
- **The project is archived**: Vikunja refuses writes there (412) and leaves it out of searches,
  so the routine becomes read only for everyone until the project is restored. This is today's
  limit for every hidden task (`docs/hidden-tasks.md`).
- **The project is deleted**: its tasks go with it, so the routine and all its history are gone
  for everyone. The info text and the delete confirmation say so (6.3).

## 6. Screens

### 6.1 Share with

In the routine editor (`RoutineEditorDialog` in `RoutinesScreen.kt`; desktop `RoutineEditor` in
`RoutinesView.tsx`), below the times:

- A **"Share with"** dropdown. The first entry is "Only you", the default.
- It lists every person who is a member of at least one **eligible project**: shared (contract
  9.1), not archived, and with your permission write or admin. You are left out. Each entry shows
  the avatar and display name (contract 9.3).
- A person with read-only access in every eligible project is listed but cannot be picked, with
  "Can view only", as in Assign (`Collaboration.memberPermission`).
- Below the field, once someone is picked: "Visible to you and Maya" (contract 9.3, everyone in
  the project, so "Visible to you, Maya and Sam" when Sam is in it too), and "Kept in Home", or
  the "Keep in" choice when more than one project qualifies (6.2).
- An **info button** next to the label explains, roughly:

  > A shared routine is kept in a project you share with that person, so everyone in that project
  > can see it and tick it off. Only people you already share a project with are listed; to share
  > with someone else, share a project with them first, in Vicu or in Vikunja. Deleting that
  > project deletes the routine and its history for everyone, and if you lose access to the
  > project, you lose the routine.

- With nobody eligible, the dropdown shows only "Only you" and the info button.
- Saving with a person picked shares the routine (flag on, carrier and parts moved, 6.2). Saving
  "Only you" on a shared routine stops sharing (5.6).
- A member sees the field read only: "Shared by Maya", with the "Visible to" line.
- Sharing and stopping need a connection, like sharing a project (`ProjectActions`): moving the
  archive parts goes straight to the server (S13).

### 6.2 Which project

- **One** eligible project has the picked person: the routine goes there, shown as "Kept in
  Home".
- **Several** do (S7): a second field, **"Keep in"**, appears below "Share with". It lists those
  projects, each with its "Visible to" line, and marks the ones the user owns ("Yours"), since
  only those cannot be taken away (5.6). It starts on the project the routine is already in when
  that one qualifies (for example a shared Inbox: nothing moves); otherwise nothing is chosen and
  Save waits for a choice.
- Choosing another project later moves the carrier and its archive parts there, like sharing.

### 6.3 Warnings

- The info text (6.1).
- **Deleting a project** that holds a shared routine (mobile `DeleteProjectDialog` in
  `ProjectConfirmDialogs.kt`; desktop `SectionHeader.tsx`) adds: "It also holds the shared routine
  'Feed the cat'. Deleting the project deletes the routine and its history for everyone." Both
  people have the routine in their app, so whoever deletes the project from Vicu sees it. A delete
  in Vikunja's web UI gets no warning.
- **Archiving** such a project (`ArchiveProjectDialog`) adds: "Shared routines in it can't be
  ticked off until it is restored."

### 6.4 Lists

- **Routines**: a shared routine's card has the other people's avatars (as assignee avatars on a
  task row) and "Shared with Maya", or "Shared by Maya" for a member.
- **Rows** (`RoutineOccurrenceRow`, Today's routine section, the widget): a finished occurrence of
  a shared routine says who did it (5.4), in the accessible name too. Open occurrences look as
  today.
- **History**: each record with its name.
- A read-only member's rows have no checkbox, and the widget opens the app instead of ticking.

## 7. Two people writing one routine

These are rare between one person's devices and become common with two people. 7.1 and 7.2 are
worth doing even without sharing.

### 7.1 Compare-and-swap writes (S8, a fix)

- Today every carrier write fetches the server's copy, merges, and sends the whole description
  (`TaskRepositoryImpl.sendUpdate`, the queued routine edit in `SyncEngine`, the desktop's
  `writeRoutineCarrier`). When two writes overlap between fetch and send, the later one replaces
  the earlier. The refresh then stores the server's carrier over the losing device's copy
  (`TaskRefresher.merge` leaves only rows with a queued change alone), so the tick disappears
  there too.
- Fix: send the write as a JSON Patch (`application/json-patch+json`): first
  `{"op": "test", "path": "/description", "value": <the description just fetched>}`, then
  `replace` for the title, description, done, due date, repeat and reminders. A 422 means the
  carrier changed since the fetch: fetch again, merge, send again. After three tries the write
  fails like any other (it stays queued on mobile; the desktop shows the error). Vikunja also
  answers 422 to a body it cannot apply, which is why the tries are limited.
- The same goes for archive part writes (`RoutineArchiveStore`, the desktop's `writeArchive`),
  which two members can now do at once.
- Cost: the request carries the description twice, a few hundred KiB for a routine with a full
  year. Testing `/updated` instead is small, but `updated` has whole seconds, so a write in the
  same second as the previous one would pass the test. S9 takes the description; measure the
  cost on a phone before building.
- Mobile needs a JSON Patch call in `VikunjaApiService` (today it sends merge patches only); one
  helper serves all three writers.
- Older versions keep writing without the test, so their window stays.
- **Read back** (found while building). Vikunja 2.4 checks the `test` and stores the result in two
  steps, so two writes that arrive together can both pass, and the one stored last removes what
  the other wrote; on an SQLite server one of the two fails with a 500 ("database is locked")
  instead. Measured on the seeded server with two people writing at the same moment: 20 of 40
  writes lost (19 as a 500, one silently), and 15 of 20 lost with twenty writers. So a write that
  went through waits half a second, reads the carrier back and writes the merge again when it
  lost something; a 5xx is tried again like a 422; four writes in all (contract 6.8). A refresh
  that brings a carrier without this device's recent records (the last 7 days, its own device id)
  writes them again, as a safety net for an app that stopped before it read back. With that: 0
  of 40 lost, with the app's own writers (`LiveRoutineRaceTest` on mobile,
  `live-routine-race.test.ts` on the desktop, both skipped unless `VICU_LIVE_RACE` is set), and
  0 of 20 with twenty writers.

### 7.2 "Not logged" placeholders (S10, a fix)

- Mobile writes `NOT_LOGGED` for the missed past days of a calendar routine, for older versions
  (contract 6.7; `finalizePastHealthOccurrences`), when the day changes (the Today and Routines
  screens, `RoutineMaintenanceWorker`), stamped with the current time.
- A device that has not seen a late tick yet (Maya ticks at 23:55, my phone fills in yesterday at
  00:05) writes a newer record, and the newest record wins: the tick is lost.
- Fix (contract 6.3 and 6.7): placeholder records carry `modifiedAt`
  `1970-01-01T00:00:00.000Z`, so any record a person wrote beats them. Older versions compare the
  same way and keep the person's record too. The desktop writes no placeholders.

### 7.3 Reminders after the other person did it (S11)

- **Android**: `RoutineAlarmReceiver` reads the occurrence from Room when the alarm fires, which
  can be 30 minutes or more behind the other person. It first fetches the carrier (one `GET`) and
  stores it; offline, or after a few seconds without an answer, it rings as Room says. This
  started with shared routines and now holds for every routine (`RoutineReminderCheck`): a tick
  on the desktop, or one this phone's copy lost, is the same case with one person.
- **Desktop**: the routine timers carry the payload from when they were planned (`notifications.ts`:
  at start, after a change on this computer and at 00:05), with no check when they fire. For a
  shared routine, fetch the carrier when the timer fires and skip a finished occurrence.
- **iOS**: notifications are planned ahead and no code runs when one fires. Another person's tick
  removes the pending ones only once this phone syncs (the app opened, a background refresh).
  Accepted: Vikunja has no push, and one person's iPhone already behaves this way after a tick on
  the desktop.
- **Done from a stale notification**: completing an occurrence the server already has as
  completed keeps the server's record, so "Done by Maya" stays.

### 7.4 How fresh the lists are

- Opening Today or Routines runs the screen refresh (`updated >= cursor`), which brings in the
  other person's ticks; so does the periodic sync every 30 minutes on Android and the background
  refresh on iOS. The desktop reads the carriers again whenever Routines or Today shows them and
  its copy is older than 30 seconds.
- Every tick rewrites the whole carrier, so every device of both people downloads it again. That
  is today's cost for one person's devices, now for two people.

## 8. Older versions

| Whose app | Version | What happens |
|---|---|---|
| The member's | Before the synced-data release (mobile 2.2.0 and the desktop release beside it) | No ownership rule: sees every routine in the shared project, shared or not, as today |
| The member's | From the synced-data release up to the release before this one | Ownership rule: does not see shared routines until it updates |
| The owner's other devices | From the synced-data release | Treat it as their own routine (it is), keep `shared` and `loggedBy`, write without the test (7.1), show no shared mark |
| The owner's other devices | Before the synced-data release | Drop unknown routine fields (mobile all of them, the desktop the top-level ones and a re-logged record's), so a write from mobile removes `shared` and the routine turns private for the member. The owner shares it again after updating |

The info text cannot tell whether the other person's app is new enough. The release notes say
both people need the new version.

## 9. Code shape

Mobile (`commonMain` unless noted):

| Piece | Change |
|---|---|
| `RoutineDefinition`, `RoutineOccurrenceRecord` | `shared` and `loggedBy` |
| Routine visibility (in `HiddenTasks` or a small `RoutineVisibility`) | Own, or another user's with `shared`. Used by `RoutineRepositoryImpl` (the parsed carriers), `TaskRefresher` (`merge` and the sweep of other users' carriers in the full reconcile) and `RoutineArchiveStore.isMine` |
| `VikunjaApiService` | A JSON Patch update for tasks |
| Carrier writes | One compare-and-swap helper, used by `TaskRepositoryImpl.sendUpdate`, the queued routine edit in `SyncEngine` and `RoutineArchiveStore` |
| `RoutineRepository` | `share(routineId, projectId)` and `stopSharing(routineId)`: move the carrier and the parts, set the flag; `loggedBy` on logs; the placeholder timestamp |
| `SyncedPreferences` | `routine_reminders.<id>` and `routine_hidden.<id>`; `RoutineAlarmPlanner`'s input leaves out what they turn off |
| `RoutinesViewModel` | Eligible people and projects from `CollaborationRepository.state`; the share field's state. A Koin edit: run `KoinGraphTest` and `IosKoinGraphTest` |
| Screens | The share field and info text in `RoutineEditorDialog`, the shared mark on `RoutineDefinitionCard`, "Done by" on `RoutineOccurrenceRow` and in the history, the notes in `DeleteProjectDialog` and `ArchiveProjectDialog` |
| `RoutineAlarmReceiver` (`app`) | Fetch a shared routine's carrier before deciding |

Desktop: the exception to `isOwnTask` for routine carriers (`carrier-discovery.ts`) and archive
parts (`routine-store.ts`), the compare-and-swap in `writeRoutineCarrier` and `writeArchive`,
the check in `fireRoutineReminder`, the same screens in `RoutinesView.tsx` and
`RoutineTodaySection.tsx`, the note in the project delete confirmation, and the two keys in
`synced-preferences.ts`.

## 10. Alternatives considered

| Alternative | Why not |
|---|---|
| A project picker instead of people | People are what the user has in mind; the project is asked for only when more than one qualifies (S7) |
| Vicu picks the project when several qualify (current one, then fewest members, then one you own) | Considered for S7; the user knows which project fits and sees who else gets the routine |
| Vicu creates a "Shared routines" project when none exists | A visible project in Vikunja and the drawer, and Vicu would need sharing rights it may not have. Decided against (S1) |
| Every routine in a shared project is shared, no flag | Brings back the leak 2.2 fixed for everyone whose Inbox is shared |
| One log carrier per person: each person writes ticks into their own hidden task and the apps combine them | Keeps "write only your own hidden tasks" and avoids writes between people colliding. But a routine gets one carrier per person, every sync searches and fetches more, each log needs its own archive, and the owner's older devices never see the other person's ticks. The compare-and-swap (7.1) solves the collision for much less |
| Assignees on the carrier to say who shares it | Assigning gives no access, subscribes the person to notifications, and hidden tasks have no assignees (contract 10.2) |
| `If-Match` with the task's ETag | Ignored on `PATCH` (3.1) |
| A repeating Vikunja task in a shared project | Works today for one chore a day, without times, history or skip. Still the advice for chores that need none of those |

## 11. Order of work

Two releases (S14), each in both apps; Claude does the desktop half in `rendyhd/Vicu` (W1).

1. **First release, the fixes** (7.1, 7.2), with contract 6.3 and 6.7 and their vectors. They
   help every routine user.
2. **Second release**: the contract (section 6.8, the 10.3 exception, the two 11.3 keys, with
   vectors, in both repos), then both apps: visibility, `loggedBy`, share and stop sharing, the
   screens and warnings, the reminder checks, the two keys.

## 12. Testing

- Contract vectors: which carriers count as visible (`me`, carriers with `createdBy` and the flag,
  the ids used), when a `loggedBy` name is shown, and placeholder records against late ticks in
  the routine merge vectors. `SharedContractIdentityTest` keeps the files identical.
- Mobile against `FakeTaskServer`: two users writing one carrier at once (both ticks kept after a
  422), a member's tick while the owner's is queued offline, three 422s in a row, a placeholder
  against a late tick, stop sharing while a member has a tick queued, a read-only member, a
  member's write that archives history.
- Server checks before building: one JSON Patch that replaces done, due date, repeat and
  reminders together; a `test` on a description of about 500 KiB; moving a carrier and its
  archive parts to another project as its owner; what a member's app gets after the owner moves
  the routine out.
- By hand: `uiverify` and `uimaya` on the seeded server share Lisbon (`collab.mjs` has the
  setup). Share a routine, tick it on each side, tick it on both at the same moment, delete
  Lisbon from Vicu (the warning), remove `uimaya` from Lisbon.
- Live races against the seeded server: `LiveRoutineRaceTest` (mobile, `VICU_LIVE_RACE` = the
  ui-verify `.local` folder, `VICU_LIVE_RACE_TASK` = a shared carrier's id) and
  `live-routine-race.test.ts` (desktop, the same variables). The desktop harness has
  `scenarios/shared-routines.mjs`.

## 13. Decision record

Status per decision: **Decided** (by the owner) or **Fix** (a bug in today's apps; not optional,
only the timing is).

| # | Decision | Status | Choice | Why | If we don't |
|---|---|---|---|---|---|
| S1 | How a routine gets shared | **Decided** 2026-10-24 | Only through a project the user already shares with that person; Vicu creates no project | Keeps it small and adds no visible project | Not applicable |
| S2 | The control | **Decided** 2026-10-24 | A "Share with" dropdown of people who share an eligible project with you, an info button, and a warning that deleting the project deletes the routine | Users think of people, not projects | Not applicable |
| S3 | What marks a routine shared | **Decided** 2026-10-24 | `definition.shared: true`, set by the owner (not a separate marker kind, which the owner's older devices would not read as a routine) | Private routines in a shared Inbox stay private | The 2.2 leak returns |
| S4 | Who may edit a shared routine's name, times and schedule | **Decided** 2026-10-24 | Every member with write access. Stop sharing, archive and delete stay with the owner | A household changes the feeding time without asking; the merge and 7.1 keep it safe | Members ask the owner for every change |
| S5 | Does a newly shared routine remind the other person straight away | **Decided** 2026-10-24 | Yes, by the routine's settings, with their own off switch | Being reminded is half the point | Each member has to find and turn on reminders |
| S6 | Hiding a shared routine for yourself | **Decided** 2026-10-24 | Yes, as `routine_hidden.<id>`, listed in Settings to show again | A member cannot stop sharing | A member who does not want it asks the owner |
| S7 | Which project when several qualify | **Decided** 2026-10-24 | The user picks, in a "Keep in" field that appears only then; it starts on the current project when that one qualifies, and marks the user's own projects | The user knows which project fits and sees who else gets the routine and whose project it is | Not applicable |
| S8 | Compare-and-swap carrier writes | **Fix** (first release, S14) | JSON Patch with a `test`, three tries | Two people ticking at once must both count | Ticks get lost, now and then |
| S9 | Test the description or `updated` | **Decided** 2026-10-24 | The description; measure the cost on a phone before building | Exact | `updated` misses writes within one second |
| S10 | Placeholder "not logged" records | **Fix** (first release, S14) | Stamped `1970-01-01T00:00:00.000Z` | A late tick must beat a filled-in gap | Late ticks get lost |
| S11 | Reminders after the other person did it | **Decided** 2026-10-24 | Android and the desktop fetch the carrier when a reminder fires; iOS is accepted as is | Code runs at that moment on two of three platforms | Reminders for things already done: on Android until the next sync, on the desktop whenever the tick came after the timers were planned |
| S12 | `loggedBy` on every routine or shared ones only | **Decided** 2026-10-24 | Shared ones only | Keeps private carriers small | Not applicable |
| S13 | Sharing and stopping offline | **Decided** 2026-10-24 | Online only; ticking stays available offline | Moving the archive parts goes straight to the server | Not applicable |
| S14 | Releases | **Decided** 2026-10-24 | Two releases: the fixes (7.1, 7.2) first, then the contract and the feature, each in both apps | The fixes help everyone and carry no format change beyond 6.3 and 6.7 | Not applicable |
| W1 | Who does the desktop half | **Decided** 2026-10-24 | Claude, in `rendyhd/Vicu`, on its own branch and pull request | | |

## 14. Changed while building

- **Read back after a write** (7.1): the JSON Patch `test` does not stop two writes made at the
  same moment, so writes read the carrier back and write again, and a refresh writes back this
  device's recent records the server lost. Contract 6.8.
- **A member's archive part stays with the routine** after the owner stops sharing: a part counts
  when the app reads its routine, whoever created it (5.2 said so; the first contract text
  counted another user's part only while the routine was shared, which lost a member's archived
  history from the owner's view, and left it behind when the routine was deleted). Contract 6.9,
  vector `sharedVisibility` part 14.
- **Deleting a project that holds a shared routine always asks**, even when "Confirm before
  deleting" is off in either app, because the routine goes for everyone. Contract 6.9, Texts.
- **iOS cannot look at the carrier again when a reminder fires** (S11): no app code runs then. It
  plans its notifications from the routine after every refresh, as for any routine, so a tick by
  the other person removes the notification only once this phone has refreshed.
- **Quotes**: the delete question names the routine in each app's own quotation marks (straight
  on mobile, curly on the desktop), as their other questions do.

