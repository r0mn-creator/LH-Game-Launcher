# Custom platform + Launch Intent editor

The feature that makes a custom platform actually launchable. It is common for
a launcher to store a launch command and then never expose it, or to ignore it
for custom platforms — at which point an emulator outside the built-in list can
never be launched, no matter what the user does. This is the section that fixes
that.

**In LightHouse the launch intent is a first-class, editable part of every
platform.** No registry, no allow-list, no "app not recognised".

## Create Platform — three steps

    1  Emulator      pick an installed app, then one of its exported activities
    2  ROMs folder   SAF tree picker; takePersistableUriPermission immediately
    3  Launch intent how a game is handed to that app   <-- the new section

Steps 1 and 2 are the easy part. Step 3 is the whole point.

## Step 1 — choosing the emulator

List installed packages with a launchable activity. For the chosen package,
enumerate **exported** activities from its manifest, because that is what can
actually be started from outside:

    PackageManager.getPackageInfo(pkg, GET_ACTIVITIES)  ->  filter exported

Show the default launcher activity first, but let the user pick another —
X1-BOX is a live example where the right target is `.LauncherActivity` while
the emulator core lives in a separate non-exported `.MainActivity` (`:xemu`).

## Step 3 — the Launch Intent editor

Every field editable, with a preset dropdown that fills them in.

| Field | Notes |
|---|---|
| **Action** | free text. `android.intent.action.VIEW` for most emulators; a custom string like `app.gamenative.LAUNCH_GAME` for others |
| **Component** | package / activity from step 1; may be left blank for an implicit intent |
| **How the ROM is passed** | ⚠️ the critical choice — see below |
| **MIME type** | optional, e.g. `application/octet-stream` |
| **Flags** | checkboxes, `GRANT_READ_URI_PERMISSION` **on by default** |
| **Extras** | repeatable key / type / value rows; `string`, `int`, `bool`, `long` |
| **Test launch** | run it now against a real game |

### "How the ROM is passed" — three modes

This is the field that makes the difference, because the three target platforms
genuinely differ:

- **Data URI** — the game's `content://` URI becomes `intent.setData()`.
  Xbox, Xbox 360, and essentially every file-based emulator.
- **Extra** — the URI or path goes into a named extra instead of the data slot.
  Some emulators want `rom_path` or similar.
- **None (id only)** — nothing file-based is sent; the game is identified purely
  by extras. This is GameNative: `app_id` is a Steam/Epic/GOG number, not a file.

A `{file}` / `{id}` / `{title}` placeholder can be used inside any extra value so
one intent template serves a whole platform.

### ⚠️ GRANT_READ_URI_PERMISSION defaults ON, and here is why

Verified on device: X1-BOX with `ACTION_VIEW` + `content://` and **no grant
flag** launches, spawns its `:xemu` process, and sits forever on *"Please insert
an Xbox disc…"*. Add the flag and the same intent boots Halo 2 at 59.9 FPS.

The app holds **no storage permissions at all**, so there is no fallback path.
Leaving this off produces a launcher that looks like it works and never loads a
game — precisely the failure mode this avoids. It costs nothing when unnecessary.

### ⚠️ The Test button must not lie

"The app started" is **not** evidence the game loaded — X1-BOX proved that.
So Test does not report success on its own:

    1  launch the intent against a real game from this platform
    2  wait, then return to LightHouse
    3  ask the user plainly: "Did the game actually load?"  [Yes] [No]
    4  only Yes marks the profile verified

A self-reported green tick based on process liveness would reproduce the exact
bug this feature exists to fix.

## Presets (verified contracts, pre-filled)

**Xbox — X1-BOX / XemuCAE** ✅ verified

    component  com.izzy2lost.x1box/.LauncherActivity
    action     android.intent.action.VIEW
    rom        data URI
    flags      GRANT_READ_URI_PERMISSION

**Xbox 360 — Xenia AE / Canary AEX** ✅ verified

    component  org.xeniaae.aex/org.xeniaae.EmulatorActivity
    action     android.intent.action.VIEW
    rom        data URI
    type       application/octet-stream
    flags      GRANT_READ_URI_PERMISSION
    extras     game_title={title}   (optional)

**Windows — GameNative** ⚠️ from source, untested

    component  app.gamenative/.MainActivity
    action     app.gamenative.LAUNCH_GAME
    rom        none (id only)
    flags      NEW_TASK | CLEAR_TOP
    extras     app_id={id} : int
               game_source=STEAM : string

**Shortcut passthrough** — for anything with no usable intent. The emulator
creates its own shortcut; LightHouse hosts and launches it via `LauncherApps`.
⚠️ Only works for shortcuts pinned **while LightHouse is the default launcher**
(see `LAUNCH_CONTRACTS.md`), so the UI must say so instead of showing an empty
section.

## Why this is stored data, never code

Presets are seed rows the user can edit, not a compiled table. When an emulator
changes its contract — or a new one appears — the user fixes it in the app in
under a minute. A compiled-in registry needs a new release for the same
change, which is why
Canary AEX still cannot be launched from it.
