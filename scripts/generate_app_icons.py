#!/usr/bin/env python3
"""Generate official iOS 1024x1024 RGB AppIcon variants matching Android adaptive icons."""

from __future__ import annotations

import math
import pathlib
from dataclasses import dataclass
from typing import Callable

from PIL import Image, ImageChops, ImageDraw


ROOT = pathlib.Path(__file__).resolve().parents[1]
APPICONSET_DIR = ROOT / "ios/UPCPocket/Resources/Assets.xcassets/AppIcon.appiconset"

RENDER_SIZE = 2048
OUTPUT_SIZE = 1024

# Map Android's central 76x76 unit square (around optical center 54, 53.5) to full iOS canvas.
SRC_CENTER_X = 54.0
SRC_CENTER_Y = 53.5
SRC_SPAN = 76.0


@dataclass(frozen=True)
class IconPalette:
    filename: str
    bg_start: tuple[int, int, int]
    bg_mid: tuple[int, int, int]
    bg_end: tuple[int, int, int]
    wave_rgba: tuple[int, int, int, int]
    card_rgba: tuple[int, int, int, int]
    tab_rgba: tuple[int, int, int, int]
    shadow_rgba: tuple[int, int, int, int]
    pocket_rgba: tuple[int, int, int, int]
    bar_primary_rgba: tuple[int, int, int, int]
    bar_secondary_rgba: tuple[int, int, int, int]
    dot_rgba: tuple[int, int, int, int]


PALETTES: tuple[IconPalette, ...] = (
    IconPalette(
        filename="AppIcon.png",
        bg_start=(9, 61, 97),      # #093D61
        bg_mid=(14, 90, 138),      # #0E5A8A
        bg_end=(22, 116, 173),     # #1674AD
        wave_rgba=(255, 255, 255, 22),
        card_rgba=(211, 233, 255, 255),   # #D3E9FF
        tab_rgba=(110, 224, 174, 255),    # #6EE0AE
        shadow_rgba=(6, 40, 66, 36),
        pocket_rgba=(255, 255, 255, 255), # #FFFFFF
        bar_primary_rgba=(14, 90, 138, 255),   # #0E5A8A
        bar_secondary_rgba=(92, 161, 206, 255),# #5CA1CE
        dot_rgba=(46, 157, 109, 255),          # #2E9D6D
    ),
    IconPalette(
        filename="AppIcon-Dark.png",
        bg_start=(10, 22, 34),     # #0A1622
        bg_mid=(14, 32, 49),       # #0E2031
        bg_end=(19, 41, 61),       # #13293D
        wave_rgba=(124, 203, 255, 16),
        card_rgba=(30, 77, 114, 255),     # #1E4D72
        tab_rgba=(140, 215, 178, 255),    # #8CD7B2
        shadow_rgba=(4, 10, 18, 84),
        pocket_rgba=(124, 203, 255, 255), # #7CCBFF
        bar_primary_rgba=(10, 31, 49, 255),    # #0A1F31
        bar_secondary_rgba=(25, 68, 102, 255), # #194466
        dot_rgba=(19, 105, 71, 255),           # #136947
        ),
    IconPalette(
        filename="AppIcon-Tinted.png",
        bg_start=(20, 20, 20),     # #141414
        bg_mid=(25, 25, 25),       # #191919
        bg_end=(31, 31, 31),       # #1F1F1F
        wave_rgba=(255, 255, 255, 10),
        card_rgba=(168, 168, 168, 255),
        tab_rgba=(216, 216, 216, 255),
        shadow_rgba=(0, 0, 0, 64),
        pocket_rgba=(255, 255, 255, 255),
        bar_primary_rgba=(42, 42, 42, 255),
        bar_secondary_rgba=(102, 102, 102, 255),
        dot_rgba=(72, 72, 72, 255),
    ),
)


def map_pt(x: float, y: float, size: int = RENDER_SIZE) -> tuple[float, float]:
    scale = size / SRC_SPAN
    return (
        (x - SRC_CENTER_X) * scale + size / 2.0,
        (y - SRC_CENTER_Y) * scale + size / 2.0,
    )


def map_len(val: float, size: int = RENDER_SIZE) -> float:
    return val * (size / SRC_SPAN)


def cubic_bezier(
    p0: tuple[float, float],
    p1: tuple[float, float],
    p2: tuple[float, float],
    p3: tuple[float, float],
    steps: int = 48,
) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    for i in range(1, steps + 1):
        t = i / steps
        mt = 1.0 - t
        x = (mt**3) * p0[0] + 3 * (mt**2) * t * p1[0] + 3 * mt * (t**2) * p2[0] + (t**3) * p3[0]
        y = (mt**3) * p0[1] + 3 * (mt**2) * t * p1[1] + 3 * mt * (t**2) * p2[1] + (t**3) * p3[1]
        pts.append((x, y))
    return pts


def corner_arc(
    cx: float,
    cy: float,
    radius: float,
    start_deg: float,
    end_deg: float,
    steps: int = 36,
) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    for i in range(1, steps + 1):
        deg = start_deg + (end_deg - start_deg) * (i / steps)
        rad = math.radians(deg)
        pts.append((cx + radius * math.cos(rad), cy + radius * math.sin(rad)))
    return pts


def rounded_top_rect(
    x0: float,
    y0: float,
    x1: float,
    y1: float,
    radius: float,
    mapper: Callable[[float, float], tuple[float, float]],
) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    pts.append(mapper(x0 + radius, y0))
    pts.append(mapper(x1 - radius, y0))
    pts.extend(mapper(*p) for p in corner_arc(x1 - radius, y0 + radius, radius, -90, 0))
    pts.append(mapper(x1, y1))
    pts.append(mapper(x0, y1))
    pts.append(mapper(x0, y0 + radius))
    pts.extend(mapper(*p) for p in corner_arc(x0 + radius, y0 + radius, radius, 180, 270))
    return pts


def pocket_body_polygon(
    mapper: Callable[[float, float], tuple[float, float]],
) -> list[tuple[float, float]]:
    """Mirror Android ic_launcher_foreground.xml pocket contour cleanly."""
    pts: list[tuple[float, float]] = [mapper(33.5, 42.0), mapper(44.0, 42.0)]
    pts.extend(
        mapper(*p)
        for p in cubic_bezier((44.0, 42.0), (46.5, 42.0), (48.0, 47.5), (54.0, 47.5))
    )
    pts.extend(
        mapper(*p)
        for p in cubic_bezier((54.0, 47.5), (60.0, 47.5), (61.5, 42.0), (64.0, 42.0))
    )
    pts.append(mapper(74.5, 42.0))
    pts.extend(mapper(*p) for p in corner_arc(74.5, 46.5, 4.5, -90, 0, steps=24))
    pts.append(mapper(79.0, 63.0))
    pts.extend(mapper(*p) for p in corner_arc(62.0, 63.0, 17.0, 0, 90, steps=48))
    pts.append(mapper(46.0, 80.0))
    pts.extend(mapper(*p) for p in corner_arc(46.0, 63.0, 17.0, 90, 180, steps=48))
    pts.append(mapper(29.0, 46.5))
    pts.extend(mapper(*p) for p in corner_arc(33.5, 46.5, 4.5, 180, 270, steps=24))
    return pts


def pill_polygon(
    x0: float,
    y0: float,
    x1: float,
    y1: float,
    mapper: Callable[[float, float], tuple[float, float]],
) -> list[tuple[float, float]]:
    r = (y1 - y0) / 2.0
    cy = (y0 + y1) / 2.0
    pts: list[tuple[float, float]] = [mapper(x0 + r, y0), mapper(x1 - r, y0)]
    pts.extend(mapper(*p) for p in corner_arc(x1 - r, cy, r, -90, 90, steps=24))
    pts.append(mapper(x0 + r, y1))
    pts.extend(mapper(*p) for p in corner_arc(x0 + r, cy, r, 90, 270, steps=24))
    return pts


def build_diagonal_gradient(
    size: int,
    start_rgb: tuple[int, int, int],
    mid_rgb: tuple[int, int, int],
    end_rgb: tuple[int, int, int],
    mid_stop: float = 0.52,
) -> Image.Image:
    gy = Image.linear_gradient("L").resize((size, size), Image.Resampling.BILINEAR)
    # In Pillow, linear_gradient runs 0 (top) -> 255 (bottom); rotate(90) puts 0 on left -> 255 on right.
    gx = Image.linear_gradient("L").rotate(90).resize((size, size), Image.Resampling.BILINEAR)
    diag = ImageChops.blend(gx, gy, 0.5)

    r_lut: list[int] = []
    g_lut: list[int] = []
    b_lut: list[int] = []
    for i in range(256):
        t = i / 255.0
        if t <= mid_stop:
            local_t = t / mid_stop
            c0, c1 = start_rgb, mid_rgb
        else:
            local_t = (t - mid_stop) / (1.0 - mid_stop)
            c0, c1 = mid_rgb, end_rgb
        r_lut.append(round(c0[0] + (c1[0] - c0[0]) * local_t))
        g_lut.append(round(c0[1] + (c1[1] - c0[1]) * local_t))
        b_lut.append(round(c0[2] + (c1[2] - c0[2]) * local_t))

    return Image.merge("RGB", (diag.point(r_lut), diag.point(g_lut), diag.point(b_lut)))


def render_icon(palette: IconPalette) -> Image.Image:
    base_rgb = build_diagonal_gradient(
        RENDER_SIZE,
        palette.bg_start,
        palette.bg_mid,
        palette.bg_end,
    )
    canvas = base_rgb.convert("RGBA")

    # Subtle background wave layer
    wave_layer = Image.new("RGBA", (RENDER_SIZE, RENDER_SIZE), (0, 0, 0, 0))
    wave_draw = ImageDraw.Draw(wave_layer)
    wave_pts: list[tuple[float, float]] = [(0.0, RENDER_SIZE * 0.72)]
    wave_pts.extend(
        cubic_bezier(
            (0.0, RENDER_SIZE * 0.72),
            (RENDER_SIZE * 0.26, RENDER_SIZE * 0.59),
            (RENDER_SIZE * 0.56, RENDER_SIZE * 0.81),
            (float(RENDER_SIZE), RENDER_SIZE * 0.63),
            steps=72,
        )
    )
    wave_pts.extend([(float(RENDER_SIZE), float(RENDER_SIZE)), (0.0, float(RENDER_SIZE))])
    wave_draw.polygon(wave_pts, fill=palette.wave_rgba)
    canvas = Image.alpha_composite(canvas, wave_layer)

    # Peeking card + bookmark tab
    card_layer = Image.new("RGBA", (RENDER_SIZE, RENDER_SIZE), (0, 0, 0, 0))
    card_draw = ImageDraw.Draw(card_layer)
    card_draw.polygon(
        rounded_top_rect(33.0, 31.0, 75.0, 58.0, 5.0, lambda x, y: map_pt(x, y)),
        fill=palette.card_rgba,
    )
    card_draw.polygon(
        rounded_top_rect(56.5, 27.0, 67.5, 48.0, 3.5, lambda x, y: map_pt(x, y)),
        fill=palette.tab_rgba,
    )
    canvas = Image.alpha_composite(canvas, card_layer)

    # Subtle elevation drop shadow under pocket body
    shadow_layer = Image.new("RGBA", (RENDER_SIZE, RENDER_SIZE), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow_layer)
    shadow_draw.polygon(
        pocket_body_polygon(lambda x, y: map_pt(x, y + 1.0)),
        fill=palette.shadow_rgba,
    )
    canvas = Image.alpha_composite(canvas, shadow_layer)

    # Front U-pocket + crisp card detail pills & accent dot
    fg_layer = Image.new("RGBA", (RENDER_SIZE, RENDER_SIZE), (0, 0, 0, 0))
    fg_draw = ImageDraw.Draw(fg_layer)
    fg_draw.polygon(
        pocket_body_polygon(lambda x, y: map_pt(x, y)),
        fill=palette.pocket_rgba,
    )
    fg_draw.polygon(
        pill_polygon(39.0, 54.5, 69.0, 58.5, lambda x, y: map_pt(x, y)),
        fill=palette.bar_primary_rgba,
    )
    fg_draw.polygon(
        pill_polygon(39.0, 62.5, 57.0, 66.5, lambda x, y: map_pt(x, y)),
        fill=palette.bar_secondary_rgba,
    )
    cx, cy = map_pt(66.0, 64.5)
    dr = map_len(3.0)
    fg_draw.ellipse((cx - dr, cy - dr, cx + dr, cy + dr), fill=palette.dot_rgba)

    canvas = Image.alpha_composite(canvas, fg_layer)

    # Downsample with Lanczos and strip alpha completely (App Store requirement).
    downsampled = canvas.resize((OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS)
    opaque = Image.new("RGB", (OUTPUT_SIZE, OUTPUT_SIZE), palette.bg_mid)
    opaque.paste(downsampled, (0, 0), downsampled)
    return opaque


def main() -> None:
    APPICONSET_DIR.mkdir(parents=True, exist_ok=True)
    for palette in PALETTES:
        img = render_icon(palette)
        assert img.size == (OUTPUT_SIZE, OUTPUT_SIZE), f"Unexpected size for {palette.filename}"
        assert img.mode == "RGB", f"Expected RGB mode for {palette.filename}, got {img.mode}"
        out_path = APPICONSET_DIR / palette.filename
        img.save(out_path, format="PNG", optimize=True)
        print(f"Generated {out_path.relative_to(ROOT)} ({out_path.stat().st_size} bytes, {img.mode}, {img.size})")


if __name__ == "__main__":
    main()
