// Take photo (issue 93) on emulator-5554: a throwaway task in the seeded "Trip to Lisbon" project,
// "Add image" under the notes opens Take photo / Choose photo, the emulator's camera app takes a
// photo with a location, and the photo reaches the server as IMG_<date>_<time>.jpg without its
// location and without a copy in the photo library. Then the comments' paperclip menu, and quick
// add keeping its draft when the system ends Vicu while the camera is open.
//
//   node camera.mjs [--out DIR] [--only editor,comments,quickadd]
//
// Writes captures to out/camera/ (or DIR) and prints one line per check. Exit code 1 when a check
// failed. Run `node login.mjs` first. The task is made through the API and deleted at the end, with
// any task quick add made under the CAMQ title.

import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as d from './droid.mjs'
import { resolveToken } from './login.mjs'
import * as v from './nav.mjs'
import { audit, printFindings } from './a11y.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'camera')
const onlyArg = process.argv.indexOf('--only')
const only = onlyArg > 0 ? new Set(process.argv[onlyArg + 1].split(',')) : null
const runs = (part) => !only || only.has(part)

const API = 'http://localhost:3456/api/v2'
const TOKEN = resolveToken()
if (!TOKEN) throw new Error('no API token: run the seed script in the vicu repo first')

const PROJECT = 'Trip to Lisbon'
const TITLE = 'CAMT Camera check'
const QUICK_TITLE = 'CAMQ Receipt from the camera'
const CAMERA = 'com.android.camera2'

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

async function api(method, path, body) {
  const response = await fetch(`${API}/${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status}`)
  return response.status === 204 ? null : response
}
const json = async (method, path, body) => (await api(method, path, body))?.json()
const items = (answer) => (Array.isArray(answer) ? answer : answer?.items ?? [])

async function waitUntil(pred, what, ms = 15000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const nodes = d.dump()
    if (pred(nodes)) return nodes
    await d.sleep(400)
  }
  throw new Error(`timed out waiting for ${what}`)
}

const topPackage = () => (d.sh('dumpsys activity activities').match(/topResumedActivity=ActivityRecord\{\S+ \S+ ([^/\s]+)/) ?? [])[1]
const photoLibraryCount = () => Number((d.sh('content query --uri content://media/external/images/media --projection _id').match(/^Row:/gm) ?? []).length)

/** Opens Take photo from the menu, takes a photo and accepts it with Done. */
async function takePhotoInCamera() {
  await waitUntil(() => topPackage() === CAMERA, 'the camera app', 10000)
  // The camera app asks once whether to keep photo locations; say yes so the photo carries one.
  const nodes = await waitUntil((ns) => ns.some((n) => n['content-desc'] === 'Shutter' || /photo locations|location/i.test(n.text ?? '')), 'the camera screen')
  const yes = nodes.find((n) => /^(Yes|YES|Next|NEXT|OK)$/.test(n.text ?? ''))
  if (yes) await d.tap(yes.cx, yes.cy, 1500)
  await d.sleep(1500)
  d.shot('camera')
  await d.tapDesc('Shutter', { wait: 3000 })
  await d.tapDesc('Done', { wait: 2500 })
}

/** The EXIF block of a JPEG holds GPS data when its GPS IFD pointer (tag 0x8825) is there. */
function hasGps(bytes) {
  const b = Buffer.from(bytes)
  const app1 = b.indexOf(Buffer.from('Exif\0\0'))
  if (app1 < 0) return false
  const tiff = app1 + 6
  const le = b.toString('ascii', tiff, tiff + 2) === 'II'
  const u16 = (o) => (le ? b.readUInt16LE(o) : b.readUInt16BE(o))
  const u32 = (o) => (le ? b.readUInt32LE(o) : b.readUInt32BE(o))
  const ifd0 = tiff + u32(tiff + 4)
  const count = u16(ifd0)
  for (let i = 0; i < count; i++) if (u16(ifd0 + 2 + i * 12) === 0x8825) {
    const gps = tiff + u32(ifd0 + 2 + i * 12 + 8)
    return u16(gps) > 1 // more than the version tag
  }
  return false
}

const project = items(await json('GET', 'projects')).find((p) => p.title === PROJECT)
if (!project) throw new Error(`no project ${PROJECT}: run the seed script in the vicu repo first`)
const task = await json('POST', `projects/${project.id}/tasks`, { title: TITLE })
console.log(`task ${task.id}`)

try {
  d.adb('emu', 'geo', 'fix', '-9.1393', '38.7223') // Lisbon, so the camera has a location to write
  await v.launch(5000)
  await v.denyPermissionPrompt()
  await v.home()
  const libraryBefore = photoLibraryCount()

  if (runs('editor')) await step('editor', async () => {
    await v.openDrawer()
    const { n: row } = await v.scrollFind((n) => n.text === PROJECT, `project ${PROJECT}`)
    await d.tap(row.cx, row.cy, 2500)
    const s = v.sc
    await d.swipe(Math.round(s.width / 2), Math.round(s.height * 0.3), Math.round(s.width / 2), Math.round(s.height * 0.75), 400, 3500)
    await d.tapText(TITLE, { wait: 2500 })
    check(d.hasText(TITLE), 'the editor opens on the task')

    await d.tapText('Add image', { wait: 1200 })
    const menu = d.dump()
    d.shot('editor-menu')
    check(menu.some((n) => n.text === 'Take photo') && menu.some((n) => n.text === 'Choose photo'), 'Add image offers Take photo and Choose photo')
    const findings = audit(menu).filter((f) => /photo/i.test(f.label))
    printFindings('camera-menu', findings)
    check(findings.length === 0, 'no accessibility finding on the menu')

    await d.tapText('Take photo', { wait: 1500 })
    await takePhotoInCamera()
    await waitUntil(() => topPackage() === d.APP_ID, 'Vicu again', 10000)
    d.shot('editor-back')

    const end = Date.now() + 30000
    let files = []
    while (Date.now() < end) {
      files = items(await json('GET', `tasks/${task.id}/attachments`).catch(() => []))
      if (files.length) break
      await d.sleep(1500)
    }
    const name = files[0]?.file?.name ?? ''
    check(files.length === 1 && /^IMG_\d{8}_\d{6}\.jpg$/.test(name), `the photo reached the server as IMG_<date>_<time>.jpg (${name || 'none'})`)
    const after = await json('GET', `tasks/${task.id}`)
    check(after.description?.includes(`${files[0]?.id}`) ?? false, 'the notes show the photo')
    if (files[0]) {
      const bytes = await (await api('GET', `tasks/${task.id}/attachments/${files[0].id}`)).arrayBuffer()
      const dir = mkdtempSync(join(tmpdir(), 'vicu-camera-'))
      writeFileSync(join(dir, name), Buffer.from(bytes))
      check(bytes.byteLength > 10000, `the photo has content (${bytes.byteLength} bytes)`)
      check(!hasGps(readFileSync(join(dir, name))), 'the uploaded photo carries no location')
    }
    await d.sleep(1500)
    d.shot('editor-uploaded')
    check(photoLibraryCount() === libraryBefore, 'no copy of the photo in the photo library')
    await d.key('BACK', 1500)
  })

  if (runs('comments')) await step('comments', async () => {
    await v.home()
    await v.openDrawer()
    const { n: row } = await v.scrollFind((n) => n.text === PROJECT, `project ${PROJECT}`)
    await d.tap(row.cx, row.cy, 2500)
    await d.tapText(TITLE, { wait: 2500 })
    await d.tapText('Comments', { wait: 2500 })
    await d.tapDesc('Attach a photo or file', { wait: 1200 })
    const menu = d.dump()
    d.shot('comments-menu')
    const labels = menu.filter((n) => ['Take photo', 'Choose photo', 'File'].includes(n.text)).sort((a, b) => a.cy - b.cy).map((n) => n.text)
    check(labels.join(',') === 'Take photo,Choose photo,File', `the paperclip lists Take photo, Choose photo, File (${labels.join(', ')})`)
    await d.key('BACK', 800)
    await d.key('BACK', 1200)
    await d.key('BACK', 1200)
  })

  if (runs('quickadd')) await step('quickadd', async () => {
    await v.home()
    await d.tapDesc('New task', { wait: 1800 })
    // A draft left by an earlier run would come back: clear it first.
    if (d.hasDesc('Clear the draft')) await d.tapDesc('Clear the draft', { wait: 800 })
    await d.typeSlow(QUICK_TITLE)
    await d.sleep(800)
    const notesChip = d.dump().find((n) => n.text === 'Notes' || n.text === '+ Notes')
    if (!notesChip) throw new Error('no Notes chip')
    await d.tap(notesChip.cx, notesChip.cy, 1200)
    await d.tapText('Add image', { wait: 1200 })
    await d.tapText('Take photo', { wait: 1500 })
    await waitUntil(() => topPackage() === CAMERA, 'the camera app', 10000)
    // The system ends Vicu while the camera is open, as it may when memory runs short.
    d.sh(`am kill ${d.APP_ID}`)
    await d.sleep(1500)
    const alive = d.sh(`pidof ${d.APP_ID} || true`).trim()
    check(alive === '', `Vicu was ended while the camera was open${alive ? ` (pid ${alive})` : ''}`)
    await takePhotoInCamera()
    const nodes = await waitUntil((ns) => ns.some((n) => n.text === QUICK_TITLE), 'quick add with its draft', 20000)
    d.shot('quickadd-restored')
    check(true, 'quick add came back with its title')
    const thumbs = nodes.filter((n) => n['content-desc'] === 'Remove image' || /image/i.test(n['content-desc'] ?? ''))
    check(thumbs.length >= 1, `the new photo waits in the notes (${thumbs.map((n) => n['content-desc']).join(', ')})`)
    await d.tapDesc('Save task', { wait: 3000 })
    const end = Date.now() + 30000
    let made
    while (Date.now() < end) {
      made = items(await json('GET', `tasks?filter=${encodeURIComponent(`title like '%CAMQ%'`)}`).catch(() => [])).find((t) => t.title === QUICK_TITLE)
      if (made && items(await json('GET', `tasks/${made.id}/attachments`).catch(() => [])).length) break
      await d.sleep(1500)
    }
    const files = made ? items(await json('GET', `tasks/${made.id}/attachments`).catch(() => [])) : []
    check(!!made && files.length === 1, `the task was made with the photo (${files.map((f) => f.file?.name).join(', ') || 'none'})`)
  })
} finally {
  await api('DELETE', `tasks/${task.id}`).catch((e) => console.log(`could not delete task ${task.id}: ${e.message}`))
  const leftovers = items(await json('GET', `tasks?filter=${encodeURIComponent(`title like '%CAMQ%'`)}`).catch(() => []))
  for (const t of leftovers) await api('DELETE', `tasks/${t.id}`).catch(() => {})
  try { await v.home() } catch { /* ignore */ }
}

console.log(failures ? `${failures} failed` : 'all passed')
process.exit(failures ? 1 : 0)
