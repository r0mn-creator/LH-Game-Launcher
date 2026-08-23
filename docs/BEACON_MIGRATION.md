# Importing the existing Beacon setup

The user's Beacon library is **already fully exported** — nothing needs to be
set up again. LightHouse imports it rather than rescraping.

Export location: `research/beacon_export/` (gitignored — 608 MB of media).

    app.db  app.db-shm  app.db-wal    raw Room DB, copied WITH the WAL
    beacon_full_dump.sql              287 KB, complete .dump
    beacon_library.json               449 KB, portable structured export
    beacon_media.tar                  608 MB, 1618 files, all cover/screenshot art

⚠️ The `-wal` was 416 KB against a 483 KB `app.db` — copying `app.db` alone
would have silently lost the most recent chunk of the library. Always take all
three files, or checkpoint first.

## What is in it

12 platforms, **557 games**, 574 files, 9 favourites, 50 with play history.

| id | platform | short | player package | AR | games |
|----|----------|-------|----------------|-----|-------|
| 4 | Nintendo - GameCube | GC | org.dolphinemu.dolphinemu | 3:4 | 72 |
| 6 | Nintendo - Wii | WII | org.dolphinemu.dolphinemu | 3:4 | 10 |
| 7 | Nintendo - Wii U | WIIU | info.cemu.cemu | 3:4 | 17 |
| 5 | Nintendo - Switch | NS | dev.eden.eden_emulator | 3:5 | 27 |
| 9 | Sony - PlayStation | PSX | com.github.stenzek.duckstation | 1:1 | 22 |
| 10 | Sony - PlayStation 2 | PS2 | xyz.aethersx2.android | 3:4 | 20 |
| 11 | Sony - PlayStation Portable | PSP | org.ppsspp.ppsspp | 3:5 | 27 |
| 3 | Nintendo - Game Boy Advance | GBA | com.fastemulator.gba | 1:1 | 272 |
| 2 | Nintendo - DS | DS | com.dsemu.drastic | 8:7 | 1 |
| 1 | Nintendo - 3DS | 3DS | io.github.lime3ds.android | 8:7 | 24 |
| 8 | Sega Dreamcast | DC | io.recompiled.redream | 1:1 | 45 |
| 12 | Android | ANDROID | Android | 2:3 | 20 |

**No Xbox, Xbox 360 or Windows platform exists** — the Xbox 360 platform from
the earlier intent experiment was removed once it turned out it could not
launch. Those three sections are new work, not a migration.

## Schema (Room)

    game_platform       id, name (UNIQUE), short_name, player_package, order,
                        launch_command, core, file_handle_type,
                        background_image_path, aspect_ratio_width/height
    game_platform_path  id, platform_id -> game_platform, path
    game                id, name, game_platform_id, cover_image_path,
                        screenshot_image_path, release_date, publisher,
                        developer, genres, is_favourite, last_played,
                        player_package, core
    game_file           id, game_id, file (UNIQUE), is_primary

### How things are referenced

- **Scan folders** (`game_platform_path.path`) are SAF *tree* URIs:
  `content://com.android.externalstorage.documents/tree/75D7-DC5F%3AGames%2FESDE%2FROMs%2Fgba`
- **Game files** (`game_file.file`) are SAF *document* URIs under that tree.
  All the user's ROMs live on the SD card (`75D7-DC5F`) under
  `Games/ESDE/ROMs/<system>` — i.e. shared with ES-DE.
- **Art** is an absolute path into Beacon's private storage:
  `/data/user/0/com.radikal.gamelauncher/files/platform_<pid>/game_<gid>/cover.jpg`
  (and `screenshot.jpg`). The `platform_`/`game_` numbers are the DB ids, so the
  archive re-links to the DB with no guessing.
- `release_date` and `last_played` are epoch **milliseconds**.

### Archive integrity — verified, not assumed

    covers      referenced 489   missing 0
    screenshots referenced 464   missing 0
    68 of 557 games never had a cover in Beacon at all

## Import plan for LightHouse

1. Read `beacon_library.json` (no Room dependency, no SQLite version risk).
2. Create a LightHouse platform per Beacon platform, carrying name, short name,
   aspect ratio and order. **The player package becomes an editable launch
   profile, not a hard-coded lookup** — the entire point of this project.
3. Copy `platform_<pid>/game_<gid>/*` into LightHouse's own media dir and
   rewrite the paths; keep the id-derived layout, it is already clean.
4. Preserve `is_favourite` and `last_played` — 50 games have history worth
   keeping.
5. Re-grant SAF permissions. ⚠️ **URI permission grants do not transfer between
   apps.** The stored tree URIs are still correct, but LightHouse must ask for
   each tree once via `ACTION_OPEN_DOCUMENT_TREE` and call
   `takePersistableUriPermission`. Until then, stored document URIs will not be
   readable. This is unavoidable and the UI should walk the user through it
   once per folder (11 folders), not fail silently.
6. Xbox / Xbox 360 / Windows are added fresh — see `LAUNCH_CONTRACTS.md`.

## Safety

The export is a **copy**; Beacon was never modified and is still the device's
default launcher. If LightHouse is abandoned, nothing has been lost.
