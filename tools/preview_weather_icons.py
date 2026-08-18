#!/usr/bin/env python3
"""
Renders the generated wx_*.xml vector drawables into a single labeled contact sheet PNG so the
whole weather icon set can be reviewed at a glance (day icons on light tiles, night icons on dark
tiles). This is a dev-preview tool only; Android renders the real drawables on device.

    python tools/preview_weather_icons.py
"""
import io
import os
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics import renderPM
from svglib.svglib import svg2rlg

DRAWABLE_DIR = os.path.join("app", "src", "main", "res", "drawable")
OUT = os.path.join("assets", "weather_icons_preview.png")
A = "{http://schemas.android.com/apk/res/android}"

TILE = 104
LIGHT = (243, 244, 246)
DARK = (241, 238, 250)  # light lavender tile for night (no black background)


def vd_to_svg(path):
    tree = ET.parse(path)
    root = tree.getroot()
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" '
             'width="24" height="24">']

    def emit_path(el):
        d = el.get(f"{A}pathData")
        fill = el.get(f"{A}fillColor")
        stroke = el.get(f"{A}strokeColor")
        sw = el.get(f"{A}strokeWidth")
        cap = el.get(f"{A}strokeLineCap", "round")
        join = el.get(f"{A}strokeLineJoin", "round")
        attrs = [f'd="{d}"']
        attrs.append(f'fill="{fill}"' if fill else 'fill="none"')
        if stroke:
            attrs.append(f'stroke="{stroke}"')
            attrs.append(f'stroke-width="{sw or 1.5}"')
            attrs.append(f'stroke-linecap="{cap}"')
            attrs.append(f'stroke-linejoin="{join}"')
        return f'<path {" ".join(attrs)} />'

    for el in root:
        tag = el.tag.split("}")[-1]
        if tag == "group":
            sx = float(el.get(f"{A}scaleX", 1))
            sy = float(el.get(f"{A}scaleY", 1))
            tx = float(el.get(f"{A}translateX", 0))
            ty = float(el.get(f"{A}translateY", 0))
            parts.append(f'<g transform="translate({tx},{ty}) scale({sx},{sy})">')
            for child in el:
                parts.append(emit_path(child))
            parts.append("</g>")
        elif tag == "path":
            parts.append(emit_path(el))
    parts.append("</svg>")
    return "\n".join(parts)


def render_icon(path, bg):
    svg = vd_to_svg(path)
    drawing = svg2rlg(io.StringIO(svg))
    scale = TILE / 24.0
    drawing.scale(scale, scale)
    drawing.width = TILE
    drawing.height = TILE
    bg_int = (bg[0] << 16) | (bg[1] << 8) | bg[2]
    png = renderPM.drawToString(drawing, fmt="PNG", bg=bg_int)
    return Image.open(io.BytesIO(png)).convert("RGB")


def status_keys():
    keys = set()
    for fn in os.listdir(DRAWABLE_DIR):
        m = re.match(r"wx_(.+)_(day|night)\.xml$", fn)
        if m:
            keys.add(m.group(1))
    # Preserve the enum-ish ordering by reading WeatherStatus.kt order.
    order = []
    ks = os.path.join("app", "src", "main", "java", "com", "saveory",
                      "frontwidget", "data", "WeatherStatus.kt")
    if os.path.exists(ks):
        txt = open(ks, encoding="utf-8").read()
        for m in re.finditer(r'\("([a-z_]+)",\s*"', txt):
            if m.group(1) in keys and m.group(1) not in order:
                order.append(m.group(1))
    for k in sorted(keys):
        if k not in order:
            order.append(k)
    return order


def load_labels():
    ks = os.path.join("app", "src", "main", "java", "com", "saveory",
                      "frontwidget", "data", "WeatherStatus.kt")
    out = {}
    if os.path.exists(ks):
        txt = open(ks, encoding="utf-8").read()
        for m in re.finditer(r'\("([a-z_]+)",\s*"([^"]+)"\)', txt):
            out[m.group(1)] = m.group(2)
    return out


def render_page(keys, labels, out_path, title):
    cols = 2
    label_w = 230
    pad = 14
    cell_w = TILE * 2 + label_w + pad
    cell_h = TILE + 22
    rows = (len(keys) + cols - 1) // cols
    top = 52
    W = cols * cell_w + 24
    H = rows * cell_h + top + 16
    sheet = Image.new("RGB", (W, H), (255, 255, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arialbd.ttf", 20)
        sub = ImageFont.truetype("arial.ttf", 15)
        title_font = ImageFont.truetype("arialbd.ttf", 24)
    except Exception:
        font = ImageFont.load_default()
        sub = font
        title_font = font
    draw.text((14, 14), title, fill=(20, 20, 20), font=title_font)

    for i, key in enumerate(keys):
        r, c = divmod(i, cols)
        x = 12 + c * cell_w
        y = top + r * cell_h
        day = render_icon(os.path.join(DRAWABLE_DIR, f"wx_{key}_day.xml"), LIGHT)
        night = render_icon(os.path.join(DRAWABLE_DIR, f"wx_{key}_night.xml"), DARK)
        sheet.paste(day, (x, y))
        sheet.paste(night, (x + TILE + 2, y))
        tx = x + TILE * 2 + 12
        draw.text((tx, y + TILE // 2 - 20), key, fill=(25, 25, 25), font=font)
        draw.text((tx, y + TILE // 2 + 4), labels.get(key, ""), fill=(120, 120, 120), font=sub)

    sheet.save(out_path)
    print(f"Wrote {out_path} ({W}x{H}) for {len(keys)} statuses")


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    keys = status_keys()
    labels = load_labels()
    half = (len(keys) + 1) // 2
    base = OUT[:-4]
    render_page(keys[:half], labels, f"{base}_1.png",
                "Weather icons 1/2   (left tile = DAY, right tile = NIGHT)")
    render_page(keys[half:], labels, f"{base}_2.png",
                "Weather icons 2/2   (left tile = DAY, right tile = NIGHT)")


if __name__ == "__main__":
    main()
