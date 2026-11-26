# Group tasks by project on Android

Add or reconfigure the **Task List** home-screen widget. In **Appearance**, turn on
**Group by project**, select a view, and save. The setting applies to that widget
instance and defaults to off, including for widgets already on the home screen.
At the shortest supported height the widget still shows its compact header. Make
it taller to see the scrollable task list.

Choose **All tasks** to see every incomplete task from active projects in one
scrollable list, including your Inbox and tasks without a due date.

Tap **+** beside a project heading to start a task in that project. The main
**+** in **All tasks** starts in your configured Inbox. In either case the
folder chip in Quick Add shows the current destination. Tap it to choose
another project before saving.

The selected view supplies the filters. Grouped mode displays open tasks from
active projects, including subprojects. A section disappears when its last
displayed task is completed.

Under **Project order**, tap **Reorder**, use the arrows to arrange projects,
and save. Sections are alphabetical until you save a custom order. Newly synced
projects appear after your saved projects in alphabetical order and can be moved
the next time you edit the widget. Projects with identical names remain separate.

Use **Sort tasks within projects** to choose due date, priority, title,
newest, oldest, or manual task order. **View order** keeps the selected view's
ordering. In views with a preview limit, sorting rearranges the tasks already
in the preview. Each widget has its own ordering settings.

## Task limit

The other widget views take at most 20 tasks globally. Upcoming and Anytime also
limit the original database query to 20. Grouped mode applies the display cap
after filtering out completed tasks and inactive projects, and shows the full
matching count when truncated in the original views. In those views, a project appears once one of its tasks falls
within the first 20 eligible results. The count row opens the app using the
widget's existing title navigation behavior.

**All tasks** does not apply this preview cap. Launcher updates use Android
RemoteViews and share a finite Binder transaction buffer, so very large task
lists should be tested on real launchers.
[Android Binder transaction limits](https://developer.android.com/reference/android/os/TransactionTooLargeException)
