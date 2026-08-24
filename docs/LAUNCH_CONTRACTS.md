# LightHouse — verified launch contracts

How LightHouse starts a game in each target app. Everything marked **VERIFIED**
was tested on the Odin 2 and observed to reach gameplay; anything
else is read from source or a manifest and must not be trusted until tested.

The whole reason this project exists: **Beacon recognises emulators from a
hard-coded package registry.** An unrecognised package gets a bare
`ACTION_MAIN`/`LAUNCHER` intent with no data URI, so the ROM is never passed and
the emulator opens to its own menu. LightHouse must therefore treat the launch
intent as **user-editable data, never a compiled-in table.**

---

## 1. Xbox (original) — X1-BOX / XemuCAE ✅ VERIFIED

    component  com.izzy2lost.x1box/.LauncherActivity
               (or org.xemucae/.LauncherActivity — same app, rebuilt)
    action     android.intent.action.VIEW
    data       content://…            (a MediaStore or SAF document URI)
    flags      FLAG_GRANT_READ_URI_PERMISSION      <-- REQUIRED

### ⚠️ The grant flag is the whole ball game

Without `FLAG_GRANT_READ_URI_PERMISSION` the emulator **starts, and then sits at
"Please insert an Xbox disc…"**. The intent is accepted, the activity launches,
the process spawns — and the game silently never loads. With `-f 0x1` the same
intent boots Halo 2 to its intro at 59.9 FPS.

This app holds **no storage permissions whatsoever** (only INTERNET), so:
- `file://` cannot work — it has no way to read the path, and would throw
  `FileUriExposedException` on the sender side anyway.
- A content URI **without** the grant cannot work either.

There is exactly one correct way to launch it, and "the app opened" is not
evidence that it worked. Always confirm gameplay, never process liveness.

Content lives at `/storage/XXXX-XXXX/Games/OG Xbox/*.xiso.iso`.

---

## 2. Xbox 360 — Xenia AE / Canary AEX ✅ VERIFIED (earlier work)

    component  org.xeniaae.aex/org.xeniaae.EmulatorActivity
    action     android.intent.action.VIEW
    data       content://…
    type       application/octet-stream
    flags      FLAG_GRANT_READ_URI_PERMISSION

Optional extras: `game_title` (recovers a stale MediaStore id),
`game_title_id` (unlocks per-game driver/config). Works from **0.4.0-aex**;
stable 0.2.0 and canary 0.3.0 declare the filter but do not implement it.
Full spec in `Xenia-AE/docs/INTENT_API.md`.

Content at `/storage/emulated/0/Download/360/*.iso`.

Also installed and worth supporting as alternates: `aenu.ax360e.free`,
`xendroid.compose`, `jp.xenia.emulator.github.debug`.

---

## 3. Windows — GameNative ⚠️ READ FROM SOURCE, NOT YET TESTED

`app.gamenative` 0.9.2. Source: `reference/GameNative`, GPL-3.0. It **vendors
Winlator** (292 files under `com/winlator/`), so it is Wine + Box64 underneath.

From `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt`:

    component  app.gamenative/.MainActivity        (exported=true)
    action     app.gamenative.LAUNCH_GAME
    extras     app_id            int     REQUIRED, must be > 0
               game_source       String  STEAM | EPIC | GOG | AMAZON
                                         (invalid/absent silently ⇒ STEAM)
               container_config  String  optional JSON, ≤ 50 KB, Wine container
                                         overrides for this launch only
    flags      FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP

Internally it composes `"${game_source}_${app_id}"` as the app key. The same
intent is what its own `ShortcutUtils.createPinnedShortcut()` builds, so this is
the canonical form rather than a guess.

**`app_id` is a Steam/Epic/GOG numeric id, not a file path.** Windows titles are
therefore identified completely differently from the two Xbox platforms — see
the section design note below.

---

## 4. The shortcut fallback (user's suggestion — and it generalises)

Where an app has no documented intent, let *it* create the shortcut and have
LightHouse host it. As the **default launcher**, LightHouse can enumerate and
launch other apps' shortcuts through `LauncherApps`:

    LauncherApps.getShortcuts(query, user)   // FLAG_MATCH_DYNAMIC |
                                             // FLAG_MATCH_MANIFEST |
                                             // FLAG_MATCH_PINNED
    LauncherApps.startShortcut(...)

Evidence this works in practice: `dumpsys shortcut` on the Odin already lists
dynamic shortcuts from the Switch emulators whose **shortcut id IS the
`content://` URI of the ROM** — the same "content URI = game" model.

### ⚠️ Two real constraints

1. `FLAG_MATCH_PINNED` only returns shortcuts pinned **to the calling launcher**.
   Seeing every launcher's pins needs `FLAG_MATCH_PINNED_BY_ANY_LAUNCHER`, which
   requires the system `ACCESS_SHORTCUTS` permission a normal app cannot hold.
   **So a shortcut must be pinned while LightHouse is the default launcher.**
   Anything pinned today (Beacon is currently default) will not be visible.
2. Querying shortcuts at all requires being the active home app.

This makes the fallback sound but order-dependent, and the UI has to say so
rather than silently showing an empty Windows section.

---

## Design consequences for LightHouse

- **No hard-coded emulator registry.** A platform's launch method is stored
  data: component, action, mime, flags, and how the payload is passed
  (data URI / extra / shortcut). This is the single thing Beacon got wrong.
- **Three payload shapes must be first-class**, because the platforms genuinely
  differ:
  a. content URI  → Xbox, Xbox 360 (file-backed, scanned from folders)
  b. numeric id   → Windows via GameNative (account-library-backed)
  c. shortcut     → anything else (opaque, launched via LauncherApps)
- **Always send `FLAG_GRANT_READ_URI_PERMISSION`** on URI launches. It costs
  nothing when unnecessary and is the difference between working and silently
  not working.
- **Verify by gameplay, not by process.** X1-BOX spawned its `:xemu` process and
  looked healthy while sitting on "Please insert an Xbox disc".

## Device inventory (Odin 2, 2026-08-23)

    default launcher   com.radikal.gamelauncher/.MainActivity   (Beacon 1.8.22)
    Xbox               com.izzy2lost.x1box 1.2.5 · org.xemucae 1.2.5
    Xbox 360           org.xeniaae.aex 0.4.0-aex · org.xeniaae 0.2.0
                       org.xeniaae.canary 0.3.0 · aenu.ax360e.free 1.19
    Windows            app.gamenative 0.9.2      (no GameHub installed)
    other frontends    org.es_de.frontend, com.teslacoilsw.launcher (Nova)
