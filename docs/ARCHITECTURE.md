# LightHouse architecture — config over modules

**Decision: emulator support is CONFIG, not compiled modules.**

Adding, editing or removing an emulator must never require a recompile, a
release, or a reinstall. A Gradle module per emulator would mean all three, and
would reproduce Beacon's actual defect — a fixed set of supported apps that only
the developer can extend — with extra ceremony on top.

Having read three real launch contracts (X1-BOX, Xenia AE, GameNative), they
differ **only in data**: component, action, which slot the game goes in, which
flags, which extras. None of them needs bespoke code. So none of them gets any.

## What is data, and what is code

Almost everything is data:

    name, short name, icon, background, aspect ratio, sort order
    ROM folders (SAF tree URIs)
    file extensions to scan, and files/folders to ignore
    the launch intent, in full
    scraper platform id
    per-game overrides

The only thing that is code is **how games are discovered** — and that is a
short, fixed list of generic providers, not one per emulator:

| provider | what it does | serves |
|---|---|---|
| `folder` | scan a SAF tree for matching extensions | Xbox, Xbox 360, and essentially every emulator |
| `installed_apps` | enumerate launchable packages | the existing "Android" section |
| `shortcuts` | `LauncherApps` pinned/dynamic passthrough | anything with no usable intent |
| `library` | read another app's game library | GameNative (`app_id` is a Steam number, not a file) |

**Adding an emulator never needs a new provider.** Adding a genuinely new *kind
of place games come from* does — and that has happened four times total.

That is the seam: providers are mechanisms, platforms are configuration.

## Platform definition

One JSON object per platform. This is the complete Xbox definition — there is no
Xbox-specific code anywhere in the app:

```json
{
  "id": "xbox",
  "name": "Microsoft - Xbox",
  "short_name": "Xbox",
  "order": 20,
  "aspect_ratio": "3:4",
  "source": {
    "provider": "folder",
    "roots": ["content://com.android.externalstorage.documents/tree/XXXX-XXXX%3AGames%2FOG%20Xbox"],
    "extensions": ["iso", "xiso.iso", "xbe"]
  },
  "launch": {
    "component": "com.izzy2lost.x1box/.LauncherActivity",
    "action": "android.intent.action.VIEW",
    "rom_mode": "data_uri",
    "flags": ["GRANT_READ_URI_PERMISSION"]
  },
  "scraper": { "platform": "xbox" },
  "verified": true
}
```

Windows differs only in its values, not its shape:

```json
{
  "id": "windows",
  "name": "Microsoft - Windows",
  "short_name": "Windows",
  "source": { "provider": "library", "app": "app.gamenative" },
  "launch": {
    "component": "app.gamenative/.MainActivity",
    "action": "app.gamenative.LAUNCH_GAME",
    "rom_mode": "none",
    "flags": ["NEW_TASK", "CLEAR_TOP"],
    "extras": [
      { "key": "app_id",      "type": "int",    "value": "{id}" },
      { "key": "game_source", "type": "string", "value": "STEAM" }
    ]
  },
  "verified": false
}
```

`rom_mode` is `data_uri` | `extra` | `none`, and `{file}` `{id}` `{title}`
interpolate into any extra.

## Where the config lives

Same pattern Xenia-AE already uses for its 480 community patches and its LUTs,
which is proven on this device:

    assets/platforms/*.json          bundled presets, shipped with the app
    <externalFiles>/platforms/*.json user-writable, wins over a bundled file
                                     with the same id

On first run (and after an update) bundled presets are extracted to the writable
directory **without overwriting existing files**, so an update can ship a fixed
Xbox profile while never clobbering one the user has edited.

Consequences, all of which are the point:

- Drop a `.json` in a folder → new platform, no recompile.
- Delete it → platform gone.
- The in-app Custom Platform editor reads and writes these same files. The
  editor is not a separate path; it is a JSON editor with a friendly face.
- Profiles are shareable — one file, no build tooling.
- I can send a corrected profile as a file rather than an APK.

## Verified flag

Each profile carries `verified`. It is set by the **Test launch** flow in
`CUSTOM_PLATFORM.md`, which asks the user whether the game actually loaded —
never inferred from the app starting. Bundled presets ship with the honest
value: Xbox and Xbox 360 `true`, GameNative `false` until someone confirms it on
a real Steam title.

## What this costs

Config-driven means errors surface at runtime, not compile time. So:

- Profiles are validated on load; a broken one is disabled with a visible
  reason, never silently ignored.
- Unknown `provider` or `rom_mode` values are reported, not defaulted — a typo
  that silently degrades to "launch the app with no ROM" would be precisely the
  Beacon bug.


## Library sources — self-contained, ES-DE opt-in

LightHouse owns its library. It scans its own configured folders and keeps its
own metadata and art; it never depends on another frontend being installed.

**ES-DE is an optional secondary source, off by default**, in
Settings ▸ Library ▸ Import. It is a manual action, never a background sync.
Worth having because the user's ROMs already live in
`Games/ESDE/ROMs/<system>` on the SD card, so ES-DE's `gamelist.xml` and
`downloaded_media/` cover titles Beacon never scraped.

Rules:
- Import is **additive and non-destructive**: it fills empty fields and never
  overwrites art or metadata LightHouse already has, unless the user ticks
  "replace existing".
- It reports what it did — matched, added, skipped — rather than silently
  merging a few hundred rows.
- ES-DE is not a live dependency. Delete it and LightHouse is unaffected.

Beacon import is the same shape (see `BEACON_MIGRATION.md`): a one-time,
explicit, reported action.
