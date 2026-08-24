#!/usr/bin/env python3
"""
Generate every launcher-icon asset from one square source image.

    python3 tools/make_icon.py path/to/icon.png

Android needs the same artwork in several shapes, and getting one of them wrong
shows up as a white plate behind the icon or as clipped artwork:

  legacy PNGs      mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher.png  48..192 px
                   plus ic_launcher_round.png for round-icon launchers
  adaptive (v26)   a background layer and a foreground layer, each 108dp

The adaptive part is where full-bleed artwork usually gets ruined. A launcher
only ever shows the CENTRAL 72dp of the 108dp canvas - the outer 18dp on each
side exists so the icon can shift under parallax. Artwork drawn to the full
canvas is therefore cropped to about two thirds of itself. So the source is
scaled into that safe zone on the foreground layer, and the background layer is
a solid colour sampled from the source's own edge, which keeps the whole design
visible under any mask shape while still filling the tile.
"""
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("needs Pillow:  pip install --user Pillow")

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"

# Legacy launcher icon sizes, in px, per density bucket.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# The adaptive canvas is 108dp; the guaranteed-visible circle is 72dp.
CANVAS = 432          # 108dp at xxxhdpi
SAFE = 0.74           # fraction of the canvas the art may occupy


def edge_colour(im: Image.Image) -> tuple:
    """Most common colour around the border - the design's own backdrop."""
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    counts = {}
    step = max(1, w // 64)
    for x in range(0, w, step):
        for y in (0, h - 1):
            counts[px[x, y]] = counts.get(px[x, y], 0) + 1
    for y in range(0, h, step):
        for x in (0, w - 1):
            counts[px[x, y]] = counts.get(px[x, y], 0) + 1
    return max(counts.items(), key=lambda kv: kv[1])[0]


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(f"usage: {sys.argv[0]} <square-source.png>")
    src_path = Path(sys.argv[1]).expanduser()
    if not src_path.is_file():
        sys.exit(f"no such file: {src_path}")

    src = Image.open(src_path).convert("RGBA")
    if abs(src.width - src.height) > 2:
        print(f"  warning: source is {src.width}x{src.height}, not square; "
              f"it will be letterboxed")

    bg = edge_colour(src)
    print(f"  source      {src.width}x{src.height}")
    print(f"  backdrop    #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}  (sampled from the edge)")

    # --- legacy square + round PNGs -------------------------------------
    for bucket, size in DENSITIES.items():
        out_dir = RES / f"mipmap-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)
        img = src.resize((size, size), Image.LANCZOS)
        img.save(out_dir / "ic_launcher.png")
        img.save(out_dir / "ic_launcher_round.png")
    print(f"  wrote       legacy PNGs for {len(DENSITIES)} densities")

    # --- adaptive foreground: art inside the 72dp safe zone --------------
    fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    inner = int(CANVAS * SAFE)
    art = src.resize((inner, inner), Image.LANCZOS)
    off = (CANVAS - inner) // 2
    fg.paste(art, (off, off), art)
    (RES / "mipmap-xxxhdpi").mkdir(parents=True, exist_ok=True)
    fg.save(RES / "mipmap-xxxhdpi/ic_launcher_foreground.png")

    # --- adaptive background: flat, so no mask can clip anything ---------
    Image.new("RGBA", (CANVAS, CANVAS), bg + (255,)).save(
        RES / "mipmap-xxxhdpi/ic_launcher_background.png")
    print("  wrote       adaptive foreground + background (108dp, 74% safe zone)")

    # --- the v26 descriptors --------------------------------------------
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    (anydpi / "ic_launcher.xml").write_text(xml)
    (anydpi / "ic_launcher_round.xml").write_text(xml)
    print("  wrote       mipmap-anydpi-v26 descriptors")

    # The old vector placeholder would win over these on API 26+.
    stale = RES / "drawable/ic_launcher_foreground.xml"
    if stale.exists():
        stale.unlink()
        print(f"  removed     stale placeholder {stale.relative_to(ROOT)}")

    print("\nDone. Rebuild with:  ./gradlew :app:assembleDebug")


if __name__ == "__main__":
    main()
