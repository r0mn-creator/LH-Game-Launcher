# Theming

Same principle as platforms: **themes are data files, auto-loaded, no recompile.**
Drop a `.json` in the themes folder and it appears in Settings ▸ Appearance.

    assets/themes/*.json              bundled themes, shipped with the app
    <externalFiles>/themes/*.json     user themes; a matching id wins
    <externalFiles>/themes/fonts/     user-droppable .ttf / .otf
    <externalFiles>/themes/images/    user background images

Extracted on first run without overwriting existing files, exactly like the
platform profiles.

## Theme file

```json
{
  "id": "lighthouse-dark",
  "name": "LightHouse Dark",
  "colors": {
    "background":       "#0E1116",
    "surface":          "#161B22",
    "surface_variant":  "#1F262E",
    "primary":          "#4C9AFF",
    "secondary":        "#8B62F5",
    "accent":           "#22C55E",
    "text_primary":     "#E6EDF3",
    "text_secondary":   "#8B949E",
    "text_disabled":    "#484F58",
    "outline":          "#30363D",
    "error":            "#F85149"
  },
  "background_image": {
    "source": null,
    "dim": 0.55,
    "blur": 12,
    "scale": "cover"
  },
  "typography": {
    "family":        "Inter",
    "family_display": "Inter",
    "scale": 1.0,
    "weights": { "title": 700, "body": 400, "label": 500 }
  },
  "shape": { "corner_radius": 14, "card_elevation": 2 },
  "grid":  { "cover_aspect_default": "3:4", "spacing": 12 }
}
```

Every field is optional and falls back to the built-in default, so a two-line
theme that only changes the background is valid. Unknown keys are reported in
Settings, not silently dropped — a typo'd colour name that quietly does nothing
is the same class of bug as a silently-ignored launch intent.

## Background: colour or image

`background_image.source` is `null` (use `colors.background`), a bundled name,
or a `content://` / file URI the user picked. `dim` and `blur` exist because
box art is unreadable over a busy wallpaper; both apply at render time so the
original image is never modified.

Per-platform override is supported — an imported schema may already carry
`background_image_path` per platform, so imported platforms can keep theirs.

## Fonts

Bundled: **Inter** (Regular / Medium / Bold), SIL Open Font License 1.1, taken
from the upstream release. OFL permits redistribution and commercial use; the
licence text ships in the app's About ▸ Licences.

Users can drop any `.ttf`/`.otf` into `themes/fonts/` and name it in
`typography.family`. The picker lists bundled faces plus whatever is in that
folder. A named font that cannot be loaded falls back to Inter **and says so** —
silently substituting a font makes a broken theme look like a design choice.

`scale` multiplies every size at once, which is the control that actually
matters on a 7-inch handheld.

⚠️ No third-party drawables or artwork are reused. Everything shipped here is
either our own or under a licence that explicitly allows it.

## Settings ▸ Appearance

    Theme            [ LightHouse Dark ▾ ]     built-in + user themes
    Background       [ Colour | Image ]
      image            pick…            dim ▁▃▅  blur ▁▃▅
    Font             [ Inter ▾ ]               bundled + themes/fonts/
    Text size        ▁▃▅                       typography.scale
    Colours          background · surface · primary · secondary · accent
                     text primary · text secondary · outline
    ─────────────────────────────────────────────
    Save as new theme…
    Reset to defaults

**Reset to defaults** restores the built-in theme and clears every override.
It asks first, and it does **not** delete user theme files — it only stops using
them, so an hour of colour-picking is never one mis-tap from gone. A separate
explicit Delete exists per user theme.

Edits in Settings write to the selected theme file (or prompt to "Save as new
theme" when a bundled one is selected), so the UI and the file never disagree.

## Import / export

A theme is a JSON file, but a *good* theme usually references a background image
and maybe a font — so exporting only the JSON would hand someone a broken theme.
Export therefore produces a self-contained bundle.

### Format: `.lhtheme` (a zip)

    theme.json          the theme, with asset paths rewritten to be relative
    images/…            only the images this theme actually references
    fonts/…             only the fonts this theme actually references
    preview.png         optional screenshot, shown in the import dialog

A bare `.json` is also accepted on import — that is the simple case (colours
only) and it should not require packaging.

### Export

    Settings ▸ Appearance ▸ [theme] ▸ Export…

Writes the bundle, then offers `ACTION_SEND` so it can go straight to Drive,
Telegram, a file manager, wherever. Only referenced assets are packed, so a
colours-only theme exports as a few KB rather than dragging in every wallpaper
in the folder.

Absolute paths (`content://…`, `/storage/…`) are rewritten to `images/foo.jpg`
on the way out and back to real paths on the way in. A theme exported from one
device must work on another, where those URIs mean nothing.

### Import

Two routes:
- **Settings ▸ Appearance ▸ Import theme…** → `ACTION_OPEN_DOCUMENT`
- **Tapping a `.lhtheme` in a file manager** → LightHouse registers an
  intent-filter for the extension and MIME type

Import shows what it is about to do — name, author, a preview if present, and
whether it will collide with an existing theme — and then:

- **Never silently overwrites.** A clashing `id` prompts: Replace / Keep both
  (imports under a new id) / Cancel.
- **Applies nothing automatically.** The theme lands in the list; switching to
  it is a separate tap. An import that instantly repaints the whole launcher is
  hostile.

### ⚠️ Importing a zip means writing files someone else authored

This is the one genuinely risky path in the app, so it is constrained:

- **Path traversal is rejected.** Entry names are sanitised and resolved; any
  entry escaping the destination (`../`, absolute paths, symlinks) aborts the
  whole import. Zip-slip is the classic way a "theme" becomes arbitrary file
  write.
- **Only known extensions are extracted** — `.json`, `.png`, `.jpg`, `.webp`,
  `.ttf`, `.otf`. Anything else is skipped and listed in the report.
- **Size and count caps** (e.g. 64 MB total, 200 entries) so a zip bomb fails
  cleanly instead of filling storage.
- **Extract to a temp dir, validate, then move.** A malformed bundle never
  leaves a half-written theme behind.
- The theme JSON is validated before anything is committed; a bad field
  disables the theme with a stated reason rather than crashing the launcher on
  next start.

None of this is hypothetical caution — themes are exactly the kind of file
people will pass around, so the import path has to assume the file is untrusted.

## Live preview

Changes apply immediately behind the settings sheet — a colour picker that needs
a restart to evaluate is unusable. Compose makes this a `CompositionLocal`
carrying the active theme; nothing is baked into a static object.
