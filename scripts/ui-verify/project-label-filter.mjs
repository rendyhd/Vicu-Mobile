// project-label-filter.mjs (contract 17.2): the label filter of a project screen on the seeded
// server. Opens "Work" (its sections are "Website redesign" and "Q4 planning"), opens the bar,
// picks "waiting", checks that only tasks with it stay and that "N more tasks" says how many went,
// then Show all. Reads only; changes nothing on the server.
//
//   node project-label-filter.mjs [--theme light|dark]
import * as d from './droid.mjs'
import { drawer, launch, taskRows } from './nav.mjs'

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : fallback
}
const theme = arg('theme', 'light')
d.setOutDir('project-label-filter')
d.sh(`cmd uimode night ${theme === 'dark' ? 'yes' : 'no'}`)

let failed = 0
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? `  (${detail})` : ''}`)
  if (!ok) failed++
}
const titles = (nodes) => taskRows(nodes).map((row) => row.title?.text).filter(Boolean)
const byDescStart = (nodes, start) => nodes.find((n) => (n['content-desc'] ?? '').startsWith(start))

await launch()
await drawer('Work')
let nodes = d.dump()
const button = byDescStart(nodes, 'Filter by label')
check('the filter button is in the top bar', !!button)
d.shot(`${theme}-1-closed`)
const before = titles(nodes)

await d.tap(button.cx, button.cy, 1200)
nodes = d.dump()
const chips = ['deep work,', 'quick win,', 'waiting,'].map((start) => byDescStart(nodes, start)?.['content-desc'] ?? null)
check('the bar shows a chip per label with its count', chips.every(Boolean), chips.join(' | '))
d.shot(`${theme}-2-bar`)

const waiting = byDescStart(nodes, 'waiting,')
check('the bar offers "waiting"', !!waiting)
await d.tap(waiting.cx, waiting.cy, 1500)
nodes = d.dump()
const after = titles(nodes)
const moreLine = nodes.find((n) => /more tasks? in this project/.test(n.text ?? ''))
check('picking a label narrows the list', after.length > 0 && after.length < before.length, `${before.length} -> ${after.length}: ${after.join(' | ')}`)
check('one line says how many are left out', !!moreLine, moreLine?.text ?? 'none')
const badgeButton = byDescStart(nodes, 'Filter by label, 1 picked')
check('the button says one label is picked', !!badgeButton)
d.shot(`${theme}-3-picked`)

// Close the bar: the button keeps its count.
await d.tap(badgeButton.cx, badgeButton.cy, 1200)
nodes = d.dump()
check('with the bar closed the filter stays', !!byDescStart(nodes, 'Filter by label, 1 picked') && !byDescStart(nodes, 'waiting,'))
d.shot(`${theme}-4-closed-picked`)

// Show all may be below the fold on a long list: scroll to it.
for (let i = 0; i < 4 && !d.dump().some((n) => n.text === 'Show all'); i++) d.sh(`input swipe ${Math.round(d.screen().width / 2)} ${Math.round(d.screen().height * 0.7)} ${Math.round(d.screen().width / 2)} ${Math.round(d.screen().height * 0.3)} 300`)
await d.sleep(800)
const showAll = d.dump().find((n) => n.text === 'Show all')
check('Show all is offered', !!showAll)
if (showAll) await d.tap(showAll.cx, showAll.cy, 1500)
nodes = d.dump()
check('Show all unpicks every label', !!byDescStart(nodes, 'Filter by label') && !byDescStart(nodes, 'Filter by label, '))

d.sh('cmd uimode night no')
console.log(failed ? `${failed} check(s) failed` : 'all checks passed')
process.exit(failed ? 1 : 0)
