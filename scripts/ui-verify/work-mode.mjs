// Work and Personal against the local Vikunja (docs/cross-app-semantics-v1.md section 22): Settings
// turns it on, picks the work label and marks a project, the drawer shows the All / Work / Personal
// control, Work and Personal each show their own tasks on Today and in the Inbox and their own
// projects in the drawer, a task added in Work mode from the Inbox gets the work label on the
// server, search finds both sides and names the other one, and the feature off shows everything.
//
//   node work-mode.mjs [tokenPath] [--out DIR] [--server http://127.0.0.1:3456] [--keep-on]
//
// Writes captures to out/work-mode/ (or DIR) and prints one line per check. Exit code 1 when a check
// failed. Run `node login.mjs` first. It makes the projects "WM Client" (with "WM Client Sub") and
// "WM Home", the label "wm-work" and tasks titled `WMV ...`, and removes them at the end (also after
// a failure, and anything an earlier run left behind). Settings go back to the feature off.
// The app's language is put back to the system default at the end of the Dutch captures.

import * as d from './droid.mjs'
import * as v from './nav.mjs'
import { DEFAULT_TOKEN_PATH, resolveToken } from './login.mjs'

const arg = (name) => {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : null
}
d.setOutDir(arg('--out') ?? 'work-mode')
const SERVER = arg('--server') ?? 'http://127.0.0.1:3456'
const tokenArg = process.argv.slice(2).find((a, i, all) => !a.startsWith('--') && !all[i - 1]?.startsWith('--'))
const TOKEN = resolveToken(tokenArg ?? DEFAULT_TOKEN_PATH)
if (!TOKEN) {
  console.error('no API token found')
  process.exit(2)
}

const PREFIX = 'WMV '
const LABEL = 'wm-work'
const CLIENT = 'WM Client'
const SUB = 'WM Client Sub'
const HOME = 'WM Home'
const T = {
  client: `${PREFIX}client today`,
  sub: `${PREFIX}sub today`,
  home: `${PREFIX}home today`,
  homeLabelled: `${PREFIX}home labelled today`,
  inboxPlain: `${PREFIX}inbox plain`,
  inboxWork: `${PREFIX}inbox work`,
  added: `${PREFIX}added in work`,
}

let failures = 0
function check(ok, what) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`)
  if (!ok) failures++
}

async function step(name, fn) {
  try {
    await fn()
  } catch (e) {
    failures++
    console.log(`FAIL ${name}: ${e.message}`)
    try { d.shot(`error-${name}`) } catch { /* ignore */ }
  }
}

// ---- the server ---------------------------------------------------------------------------------

async function api(method, path, body) {
  const res = await fetch(`${SERVER}/api/v2${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  if (!res.ok) throw new Error(`${method} ${path} answered ${res.status}`)
  return res.status === 204 ? null : res.json()
}
const list = (json) => (Array.isArray(json) ? json : json?.items ?? [])

const created = { projects: [], labels: [], tasks: [] }

async function removeLeftovers() {
  for (const task of list(await api('GET', `/tasks?per_page=100&q=${encodeURIComponent(PREFIX.trim())}`))) {
    if (task.title?.startsWith(PREFIX)) await api('DELETE', `/tasks/${task.id}`).catch(() => {})
  }
  // Child before parent, so the parent's delete does not take a project that is already gone.
  const projects = list(await api('GET', '/projects?per_page=200')).filter((p) => [CLIENT, SUB, HOME].includes(p.title))
  for (const p of projects.sort((a, b) => (b.parent_project_id ? 1 : 0) - (a.parent_project_id ? 1 : 0))) await api('DELETE', `/projects/${p.id}`).catch(() => {})
  for (const l of list(await api('GET', '/labels?per_page=200')).filter((x) => x.title === LABEL)) await api('DELETE', `/labels/${l.id}`).catch(() => {})
}

async function addTask(projectId, title, extra = {}) {
  const task = await api('POST', `/projects/${projectId}/tasks`, { title, ...extra })
  created.tasks.push(task.id)
  return task
}

const midnightUtc = (() => {
  const today = d.sh('date +%Y-%m-%d').trim()
  const offset = d.sh('date +%z').trim()
  const minutes = (offset[0] === '-' ? -1 : 1) * (+offset.slice(1, 3) * 60 + +offset.slice(3, 5))
  return Date.parse(`${today}T00:00:00Z`) - minutes * 60000
})()
const dueToday = () => new Date(midnightUtc + (24 * 3600 - 1) * 1000).toISOString().replace('.000Z', 'Z')

// ---- the screen ---------------------------------------------------------------------------------

const byText = (t) => (nodes) => nodes.find((n) => n.text === t)
const byDesc = (t) => (nodes) => nodes.find((n) => n['content-desc'] === t)
const tapNode = (n, wait = 1200) => d.tap(n.cx, n.cy, wait)

async function waitFor(pred, what, ms = 30000) {
  const end = Date.now() + ms
  for (;;) {
    const nodes = d.dump()
    const hit = pred(nodes)
    if (hit) return hit
    const wait = nodes.find((n) => n.text === 'Wait')
    if (wait && nodes.some((n) => /isn.t responding/.test(n.text ?? ''))) await d.tap(wait.cx, wait.cy, 1500)
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await d.sleep(700)
  }
}

/** Scrolls the screen (up, then down) until the node shows. */
async function scrollTo(pred, label, x = 0.4) {
  const s = v.sc
  for (const [from, to] of [[0.8, 0.3], [0.3, 0.8]]) {
    for (let i = 0; i < 12; i++) {
      const n = pred(d.dump())
      if (n) return n
      await d.swipe(Math.round(s.width * x), Math.round(s.height * from), Math.round(s.width * x), Math.round(s.height * to), 350, 700)
    }
  }
  throw new Error(`not found after scrolling: ${label}`)
}

/** A pull to refresh on the list on screen, then the titles with the prefix found scrolling down it. */
async function shownTitles(refresh = true) {
  const s = v.sc
  if (refresh) await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
  const found = new Set()
  for (let i = 0; i < 9; i++) {
    const before = found.size
    for (const n of d.dump()) if (n.text?.startsWith(PREFIX) && n.class !== 'android.widget.EditText') found.add(n.text)
    await d.swipe(Math.round(s.width * 0.5), Math.round(s.height * 0.8), Math.round(s.width * 0.5), Math.round(s.height * 0.3), 350, 700)
    if (i > 2 && found.size === before && !d.dump().some((n) => n.text?.startsWith(PREFIX))) break
  }
  await v.listToTop()
  return found
}

const has = (set, ...titles) => titles.every((t) => set.has(t))
const none = (set, ...titles) => titles.every((t) => !set.has(t))

/** The All / Work / Personal control: a button of the control at the top of the open drawer. */
const modeButton = (nodes, name) => nodes.find((n) => n.text === name && n.cy < v.sc.height * 0.25)

async function chooseMode(name) {
  await v.openDrawer()
  const button = await waitFor((nodes) => modeButton(nodes, name), `the ${name} button in the drawer`)
  await tapNode(button, 1500)
  await v.closeDrawer()
}

/** The project names the drawer lists, scrolled through (the prefix-less names of this run's projects). */
async function drawerProjects() {
  await v.openDrawer()
  const found = new Set()
  const s = v.sc
  for (let i = 0; i < 8; i++) {
    for (const n of d.dump()) if ([CLIENT, SUB, HOME].includes(n.text)) found.add(n.text)
    await d.swipe(Math.round(s.width * 0.4), Math.round(s.height * 0.8), Math.round(s.width * 0.4), Math.round(s.height * 0.3), 350, 600)
  }
  return found
}

async function settingsGeneral() {
  await v.drawer('Settings')
  const general = d.dump().find((n) => n.text === 'General')
  if (general) await tapNode(general)
}

async function featureSwitch(on) {
  await settingsGeneral()
  const row = await scrollTo(byText('Work and Personal on this device'), 'the Work and Personal switch')
  // High on the screen, so the rows under it are in the dump when it is on.
  const x = Math.round(v.sc.width * 0.4)
  await d.swipe(x, row.cy, x, Math.round(v.sc.height * 0.3), 600, 1000)
  const now = byText('Work and Personal on this device')(d.dump()) ?? row
  const isOn = d.dump().some((n) => n.text === 'Work label')
  if (isOn !== on) await tapNode(now, 1800)
}

async function toggleWorkProject(title) {
  await settingsGeneral()
  const tab = d.dump().filter((n) => n.text === 'Projects').sort((a, b) => a.cy - b.cy)[0]
  await tapNode(tab, 1500)
  const more = await scrollTo(byDesc(`More options for ${title}`), `the menu of ${title}`)
  await tapNode(more, 1000)
  await tapNode(await waitFor(byText('Work project'), 'Work project in the row menu'), 1500)
}

// ---- the run -------------------------------------------------------------------------------------

try {
  await removeLeftovers()

  const inbox = list(await api('GET', '/projects?per_page=200')).find((p) => p.title === 'Inbox' && !p.is_archived)
  const client = await api('POST', '/projects', { title: CLIENT })
  created.projects.push(client.id)
  const sub = await api('POST', '/projects', { title: SUB, parent_project_id: client.id })
  created.projects.push(sub.id)
  const home = await api('POST', '/projects', { title: HOME })
  created.projects.push(home.id)
  const label = await api('POST', '/labels', { title: LABEL })
  created.labels.push(label.id)

  await addTask(client.id, T.client, { due_date: dueToday() })
  await addTask(sub.id, T.sub, { due_date: dueToday() })
  await addTask(home.id, T.home, { due_date: dueToday() })
  const labelled = await addTask(home.id, T.homeLabelled, { due_date: dueToday() })
  await api('POST', `/tasks/${labelled.id}/labels`, { label_id: label.id })
  await addTask(inbox.id, T.inboxPlain)
  const inboxWork = await addTask(inbox.id, T.inboxWork)
  await api('POST', `/tasks/${inboxWork.id}/labels`, { label_id: label.id })

  d.stop()
  await v.launch(5000)
  await waitFor(byDesc('Open menu'), 'the app', 60000)

  await step('off-by-default', async () => {
    await featureSwitch(false)
    await v.openDrawer()
    check(!d.dump().some((n) => n['content-desc'] === 'Show all tasks, work or personal'), 'the feature off: no control in the drawer')
    await v.closeDrawer()
    await v.nav('Today')
    const shown = await shownTitles()
    check(has(shown, T.client, T.home, T.homeLabelled), 'the feature off: Today lists the tasks of both sides')
    d.shot('off-today')
  })

  await step('settings', async () => {
    await featureSwitch(true)
    d.shot('settings-on')
    check(d.dump().some((n) => n.text === 'Work label'), 'Settings shows the work label once the feature is on')
    await tapNode(await waitFor(byText('Work label'), 'the Work label row'), 1200)
    await tapNode(await waitFor(byText(LABEL), `${LABEL} in the label menu`), 1500)
    check(d.dump().some((n) => n.text === LABEL), 'the work label row shows the chosen label')
    d.shot('settings-label')
    await toggleWorkProject(CLIENT)
    const nodes = d.dump()
    check(nodes.some((n) => n.text === 'Work' && Math.abs(n.cy - (byText(CLIENT)(nodes)?.cy ?? -1)) < v.px(24)), 'the marked project shows the Work mark in Settings, Projects')
    d.shot('settings-projects')
  })

  await step('drawer-control', async () => {
    await v.openDrawer()
    const nodes = d.dump()
    check(nodes.some((n) => n['content-desc'] === 'Show all tasks, work or personal'), 'the drawer shows the All / Work / Personal control')
    check(['All', 'Work', 'Personal'].every((name) => !!modeButton(nodes, name)), 'the control has All, Work and Personal')
    d.shot('drawer-all')
    await v.closeDrawer()
  })

  await step('work', async () => {
    await chooseMode('Work')
    await v.nav('Today')
    const today = await shownTitles()
    check(has(today, T.client, T.sub, T.homeLabelled) && none(today, T.home), `Work: Today shows ${CLIENT}, its subproject and the labelled task, not ${HOME}'s plain one (${[...today].join(' | ')})`)
    d.shot('work-today')
    await v.nav('Inbox')
    const inboxTitles = await shownTitles()
    check(has(inboxTitles, T.inboxWork) && none(inboxTitles, T.inboxPlain), `Work: the Inbox shows the labelled task only (${[...inboxTitles].join(' | ')})`)
    d.shot('work-inbox')
    const projects = await drawerProjects()
    check(projects.has(CLIENT) && !projects.has(HOME), `Work: the drawer lists ${CLIENT} and not ${HOME} (${[...projects].join(' | ')})`)
    d.shot('drawer-work')
    await v.closeDrawer()
  })

  await step('new-task-in-work', async () => {
    await v.nav('Inbox')
    await tapNode(await waitFor(byDesc('New task'), 'the + button'), 1500)
    await waitFor(byText('New task'), 'the sheet')
    await d.clearField()
    await d.typeSlow(T.added)
    await d.sleep(800)
    await tapNode(await waitFor(byDesc('Save task'), 'Save task'), 2500)
    if (d.dump().some((n) => n['content-desc'] === 'Save task')) await d.back()
    const end = Date.now() + 30000
    let task = null
    while (Date.now() < end && !task) {
      task = list(await api('GET', `/tasks?per_page=100&q=${encodeURIComponent(T.added)}`)).find((t) => t.title === T.added) ?? null
      if (!task) await d.sleep(1000)
    }
    check(!!task, 'the task made in Work mode reached the server')
    if (task) {
      created.tasks.push(task.id)
      const full = await api('GET', `/tasks/${task.id}`)
      check((full.labels ?? []).some((l) => l.id === label.id), 'the task made in Work mode from the Inbox carries the work label on the server')
      check(full.project_id === inbox.id, 'it was made in the Inbox')
    }
  })

  await step('personal', async () => {
    await chooseMode('Personal')
    await v.nav('Today')
    const today = await shownTitles()
    check(has(today, T.home) && none(today, T.client, T.sub, T.homeLabelled), `Personal: Today shows ${HOME}'s plain task only (${[...today].join(' | ')})`)
    d.shot('personal-today')
    await v.nav('Inbox')
    const inboxTitles = await shownTitles()
    check(has(inboxTitles, T.inboxPlain) && none(inboxTitles, T.inboxWork, T.added), `Personal: the Inbox shows the plain task only (${[...inboxTitles].join(' | ')})`)
    const projects = await drawerProjects()
    check(projects.has(HOME) && !projects.has(CLIENT), `Personal: the drawer lists ${HOME} and not ${CLIENT} (${[...projects].join(' | ')})`)
    d.shot('drawer-personal')
    await v.closeDrawer()
  })

  await step('search', async () => {
    await chooseMode('Work')
    await v.nav('Today')
    await d.tapDesc('Search', { wait: 1500 })
    await d.type(T.home)
    await d.sleep(2500)
    const nodes = d.dump()
    check(nodes.some((n) => n.text === T.home && n.class !== 'android.widget.EditText'), 'Work: search finds the task of the other side')
    const row = nodes.find((n) => n.text === T.home && n.class !== 'android.widget.EditText')
    check(!!row && nodes.some((n) => n.text === 'Personal' && Math.abs(n.cy - row.cy) < v.px(60)), 'search says the other side with the word Personal')
    d.shot('search-work')
    await d.back()
    await v.home()
  })

  await step('all', async () => {
    await chooseMode('All')
    await v.nav('Today')
    const today = await shownTitles()
    check(has(today, T.client, T.sub, T.home, T.homeLabelled), 'All: Today shows every side')
    await v.nav('Inbox')
    const inboxTitles = await shownTitles()
    check(has(inboxTitles, T.inboxPlain, T.inboxWork), 'All: the Inbox shows every side')
    const projects = await drawerProjects()
    check(projects.has(CLIENT) && projects.has(HOME), 'All: the drawer lists every project')
    await v.closeDrawer()
  })

  await step('dutch', async () => {
    d.sh(`cmd locale set-app-locales ${d.APP_ID} --user 0 --locales "nl"`)
    d.stop()
    await v.launch(5000)
    await waitFor(byDesc('Open menu'), 'the app', 30000)
    await v.openDrawer()
    d.shot('drawer-nl')
    const nodes = d.dump()
    check(['Alles', 'Werk', 'Persoonlijk'].every((name) => !!modeButton(nodes, name)), 'the control reads Alles, Werk and Persoonlijk in Dutch')
    await v.closeDrawer()
    await v.drawer('Settings') // the drawer's and the tabs' names are not in the catalog yet
    const general = d.dump().find((n) => n.text === 'General')
    if (general) await tapNode(general)
    await scrollTo(byText('Werk en persoonlijk op dit apparaat'), 'the Dutch switch')
    await d.swipe(Math.round(v.sc.width * 0.4), Math.round(v.sc.height * 0.7), Math.round(v.sc.width * 0.4), Math.round(v.sc.height * 0.4), 500, 800)
    d.shot('settings-nl')
  })
} catch (e) {
  failures++
  console.log(`FAIL ${e.message}`)
  try { d.shot('error') } catch { /* ignore */ }
} finally {
  try { d.sh(`cmd locale set-app-locales ${d.APP_ID} --user 0 --locales ""`) } catch { /* ignore */ }
  // The feature off again, and the project's mark taken off before the project goes.
  try {
    d.stop()
    await v.launch(5000)
    await waitFor(byDesc('Open menu'), 'the app', 30000)
    if (!process.argv.includes('--keep-on')) {
      await toggleWorkProject(CLIENT).catch(() => {})
      await featureSwitch(false)
    }
  } catch (e) {
    console.log(`could not turn Work and Personal off again: ${e.message}`)
  }
  for (const id of created.tasks) await api('DELETE', `/tasks/${id}`).catch(() => {})
  await removeLeftovers().catch(() => {})
}

console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed')
process.exit(failures ? 1 : 0)
