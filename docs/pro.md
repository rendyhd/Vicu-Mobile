# Vicu Pro on the phone

Pro is an optional set of tools for people who lead work with others. It is free and built into
Vicu, and everything it stores lives on your own Vikunja server, next to your tasks. The phone has
two of Pro's four parts: knowing what you can do now, and promises and people. The portfolio,
meetings, import and the power tools are on the desktop app; the phone keeps their data safe and
follows it (a parked initiative, a list's Pro conditions).

Pro only adds. With Pro off, Vicu looks and behaves exactly as it did, and turning Pro off writes
nothing.

## Turning it on

Settings, General, Pro. Turn on **Turn on Pro on this device**, then choose the parts:

- **Know what you can do now.** Lists can show Available, Remaining or All, and a row says why a
  task is not available: it starts later, is on hold, waits on another task ("After: ..."), is later
  in a sequential project, or is someone else's promise. Below the list, a line says how many are
  hidden, with Show remaining. Flagged is in the drawer.
- **Promises and people.** Follow-ups, Waiting on and People are in the drawer, with the person and
  group pages; promises due come first on Today.

**Assistant help in Pro** (off by default) lets the assistant see Pro's data. The first-run notice
shows how many tasks already have a start date from the Vikunja web app, with Show them. **Work
area** limits promises to projects in the categories you tick (all projects when none is ticked).

## On a task

The task editor has Pro's chips: Promise, Start (not while the task has a time block, which is its
own start), Estimate, After and Flag.

Quick add reads Pro's phrases: `Jan: send the deck friday` is Jan's promise to you, `for Jan: ...`
yours to Jan, `starts monday` a start date, `~30m` an estimate, `!flag` a flag. A promise also asks
"In their words" (or "What you said") and "We'll see it's done when".

Who a promise was made to: `$Jan site list for $Priya friday` is Jan's, for Priya (Priya is not
added as a label; a name nobody has yet becomes a person when the task is saved, or is kept in words
without a connection). A promise of theirs on a task with a group's label is made to that group.
`check on wednesday` sets the day to look at it again. Rows and the editor say it: "From Jan, for
Leadership team", "For Priya", "Yours, for Leadership team".

## Projects and labels

In a project's menu: Type (Parallel, Sequential, Single actions) and Status (Active, On hold). On a
label's screen, the menu has Put on hold and This is a person, or Open group for a group's label. A
label that is neither can be marked as private (Mark as private): a 1:1 topic that carries it stays
out of the agenda you share. A project that is a 90-day plan has Plan in its menu.

## Promises

- **Today** starts with Promises due. The person's mark takes the place of the checkbox; tap it for
  the verdict: Delivered, Late, Not yet (with a date to ask again and "What is in the way?"), New
  date or Dropped, with Undo, or Ask me tomorrow. A promise someone completed elsewhere asks once
  whether they delivered it or you did ("I did it").
- **Waiting on** lists what others owe you, by person.
- **People** lists everyone marked as a person, with counts. A person's page has what they owe you,
  what you owe them, what is still to confirm, how the last 90 days went in plain words, and the next
  1:1 agenda to share. Topics lists what you want to talk about with them: open tasks with their
  label and no promise, those dated more than a week ahead under Later. Add a topic opens the task
  sheet with the person's label picked. The shared agenda names the topics, without the private ones.
- **Recap** (on the person page) shares what was agreed today, in the last 7 or 30 days; the promises
  in it are marked as sent.
- **Follow-ups** is one list of what needs a move from you: verdicts due (the same verdict sheet),
  checks before an action of a group is due (one line per person; Share message sends a short check
  through the share sheet and marks those actions as checked, with Undo), promises to look at on
  their `check on` day (Checked, Open), session recaps not copied yet and the weekly note (both done
  in Vicu on a computer or the web; the phone says so). Every line has Dismiss for today.

## Groups

A group is set up on the desktop or the web: a label, its members, its sessions. The phone shows it
read-only: from People (Groups) or a group label's menu, a group's page has how its actions went over
the last sessions (in counts, never a score), its actions by part (overdue since the last session,
due, new, open, yours, done) and owner, with verdicts on their rows, and its open issues, recent
decisions and open questions, and, when there are any, its open blockers, commitments and what it
stopped doing, and its 90-day plans. Sessions, their agenda and recap, and the weekly note are not on
the phone.

## 90-day plans

A 90-day plan is a project with a first and a last day, made on the desktop or the web (contract
21.20). The phone shows it read-only, from Plan in the project's menu or Plans on a group's page: the
days, which week it is, how many milestones were delivered, and a card per item with its owner,
"2 of 4", the next open milestone and a row of the plan's weeks with what happened in each (due,
delivered, late, slipped, dropped) in words. No status is shown or worked out. Items are ordered by
position, and a plan with many old done milestones may count fewer than the desktop, because the
phone loads done tasks in pages.

Text written for other people (the check message) uses the `pro_text_` strings. On the phone it is
written in the language the app is shown in, not the group's chosen language: the resource library
reads only the app's own language.
