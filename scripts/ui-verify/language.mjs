// The app in another language, on emulator-5554 (docs/translating.md, CLAUDE.md "Translations").
// The app has no language setting of its own: it follows the language the system keeps for it,
// which this check sets the way Settings, Apps, Vicu, Language does (`cmd locale set-app-locales`).
//
//   A. With no app language the drawer's built-in lists read English (the drawer holds the lists
//      that are not in the bottom bar, so Logbook is the one this looks at).
//   B. The system offers Dutch for Vicu (locales_config.xml reached the APK).
//   C. With Dutch the drawer reads Dutch, from the catalog in the APK.
//   D. Text that is not in the catalog yet still shows, in English.
//   E. Settings has the Language row, named in Dutch, with the catalog's own name for the language.
//   F. Without the app language everything reads English again.
//
//   node language.mjs [--out DIR]
//
// Prints one line per check, read from the screen's text. Exit code 1 when a check failed. The app must be signed in (`node login.mjs`). The app's language is always put back to
// "system default" at the end, because every other check here finds things by their English text.

import * as d from './droid.mjs'
import * as n from './nav.mjs'

const outArg = process.argv.indexOf('--out')
d.setOutDir(outArg > 0 ? process.argv[outArg + 1] : 'language')

let failed = 0
function check(label, ok, detail = '') {
  if (!ok) failed++
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? `  (${detail})` : ''}`)
}

const setAppLanguage = (tags) => d.sh(`cmd locale set-app-locales ${d.APP_ID} --user 0 --locales "${tags}"`)
const appLanguage = () => d.sh(`cmd locale get-app-locales ${d.APP_ID} --user 0`).trim()

/** The texts on screen. */
const texts = () => d.dump().map((node) => node.text).filter(Boolean)

async function restart() {
  d.stop()
  await n.launch()
}

async function drawerTexts() {
  await n.openDrawer()
  return texts()
}

try {
  // ---- A. English ---------------------------------------------------------------------------------
  setAppLanguage('')
  await restart()
  const english = await drawerTexts()
  check('with no app language the drawer reads English', english.includes('Logbook') && !english.includes('Logboek'), english.slice(0, 12).join(' | '))
  await n.closeDrawer()

  // ---- B. Dutch is offered --------------------------------------------------------------------------
  const config = d.sh(`dumpsys package ${d.APP_ID} | grep -i -m1 locale || true`).trim()
  setAppLanguage('nl')
  const now = appLanguage()
  check('the system keeps Dutch as the language for Vicu', /nl/.test(now), now || config)

  // ---- C. The drawer in Dutch ---------------------------------------------------------------------
  await restart()
  const dutch = await drawerTexts()
  check(
    'with Dutch the drawer reads Dutch',
    dutch.includes('Logboek') && !dutch.includes('Logbook'),
    dutch.slice(0, 12).join(' | '),
  )

  // ---- D. The rest stays, in English --------------------------------------------------------------
  check('text outside the catalog still shows in English', dutch.includes('PROJECTS'), dutch.filter((t) => /PROJECT/.test(t)).join(' | '))

  // ---- E. The Language row ------------------------------------------------------------------------
  await n.drawer('Settings')
  const row = await n.scrollFind((node) => node.text === 'Taal', 'the Language row').catch(() => null)
  const onScreen = texts()
  check('Settings has the Language row, named in Dutch', row !== null && onScreen.includes('Taal'))
  check('it names the language in use by the catalog\'s own name', onScreen.includes('Nederlands'), onScreen.filter((t) => /Nederlands|English/.test(t)).join(' | '))
} catch (err) {
  failed++
  console.log(`FAIL the check stopped: ${err instanceof Error ? err.message : err}`)
} finally {
  // ---- F. Back to the system's language -----------------------------------------------------------
  setAppLanguage('')
  try {
    await restart()
    const back = await drawerTexts()
    check('without the app language the drawer reads English again', back.includes('Logbook') && !back.includes('Logboek'), back.slice(0, 12).join(' | '))
    await n.closeDrawer()
  } catch (err) {
    failed++
    console.log(`FAIL English did not come back: ${err instanceof Error ? err.message : err}`)
  }
}

process.exit(failed > 0 ? 1 : 0)
