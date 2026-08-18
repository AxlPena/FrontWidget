#!/usr/bin/env python3
"""
Generates full-color, Material-style weather icons as Android vector drawables.

For every canonical WeatherStatus key (see WeatherStatus.kt) this emits two files:
    res/drawable/wx_<key>_day.xml
    res/drawable/wx_<key>_night.xml

Icons are composed from a small library of primitives (sun, moon, cloud, rain, snow,
bolt, fog, hail, ...) so the whole set is consistent and can be retuned in one place.
Day art uses a bright sun + light clouds; night art swaps to a crescent moon + darker,
moonlit clouds so every status has a genuinely distinct day/night file.

Run from the repo root:  python tools/gen_weather_icons.py
"""
import math
import os

OUT_DIR = os.path.join("app", "src", "main", "res", "drawable")

# ----------------------------------------------------------------------------- palettes
# Google Material style: clouds/precip keep the same colors day and night; the only
# difference is the luminary. Day shows the sun only on sky-visible conditions
# ("occasional sun"); every night icon gets a soft-gold crescent moon.
MOON_COLOR = "#C7B3F0"  # pastel purple (light lavender)
DAY = dict(
    sun="#FFC107", cloud="#AEBAC6", cloud_dark="#8493A1", cloud_back="#C7D0D9",
    rain="#4FA6E8", snow="#EAF4FC", flake="#AFD3F2", bolt="#FFB300",
    fog="#AEBAC6", hail="#CDE9F7", sand="#D8BE84", ash="#9AA0A6", tornado="#8493A1",
)
# Night reuses the day palette (no darker "background" clouds); only the luminary swaps.
NIGHT = dict(DAY, sun=MOON_COLOR)

# Conditions that already draw their own luminary (so we don't add a second moon at night).
SKY_STATUSES = {"clear", "mainly_clear", "partly_cloudy", "haze"}
# Night conditions with no cloud to tuck a moon behind; the moon sits in a top corner instead.
NO_CLOUD_STATUSES = {"smoke", "dust", "sand", "dust_whirls", "tornado", "squall", "volcanic_ash"}

# Google-style sun (disc + 8 rays), centered at (12,12).
SUN_D = ("M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5z"
         "M2,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1L2,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1z"
         "M20,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1h-2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1z"
         "M11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1L13,2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1z"
         "M11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1z"
         "M5.99,4.58c-0.39,-0.39 -1.03,-0.39 -1.41,0s-0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41L5.99,4.58z"
         "M18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0s-0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41l-1.06,-1.06z"
         "M7.05,18.36c-0.39,-0.39 -1.03,-0.39 -1.41,0s-0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41l-1.06,-1.06z"
         "M18.36,5.99c0.39,-0.39 0.39,-1.03 0,-1.41s-1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06z")

# Crescent moon (a lune between a big outer rim and a tighter inner arc). Smaller inner rx =
# fuller/thicker crescent (thickness at the belly = 10 - rx). rx=3 gives a fat, traditional
# crescent. Bounding box ~x2..12 y2..22 (center 7,12).
MOON_D = "M12,2 a10,10 0 1,0 0,20 a3,10 0 1,1 0,-20 Z"
# Slight clockwise tilt so the crescent reads as a classic tilted moon rather than a vertical
# lune. Applied as a group rotation about the crescent's own center (see moon()).
MOON_TILT = -25


def f(x):
    return f"{x:.3f}".rstrip("0").rstrip(".")


def circ(cx, cy, r):
    return (f"M{f(cx - r)},{f(cy)} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 "
            f"a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0 Z")


def rect(x1, y1, x2, y2):
    return f"M{f(x1)},{f(y1)} H{f(x2)} V{f(y2)} H{f(x1)} Z"


def path(d, fill=None, stroke=None, sw=1.7):
    attrs = [f'android:pathData="{d}"']
    if fill:
        attrs.append(f'android:fillColor="{fill}"')
    if stroke:
        attrs.append(f'android:strokeColor="{stroke}"')
        attrs.append(f'android:strokeWidth="{f(sw)}"')
        attrs.append('android:strokeLineCap="round"')
        attrs.append('android:strokeLineJoin="round"')
    return "    <path\n        " + "\n        ".join(attrs) + " />"


def scaled(d, s, cx, cy, ox, oy, fill, rot=0.0):
    if not rot:
        # No rotation: plain scale + translate (pivot at origin) so day art is byte-identical.
        tx, ty = cx - ox * s, cy - oy * s
        return (f'    <group android:scaleX="{f(s)}" android:scaleY="{f(s)}" '
                f'android:translateX="{f(tx)}" android:translateY="{f(ty)}">\n'
                f'      <path android:pathData="{d}" android:fillColor="{fill}" />\n'
                f'    </group>')
    # With rotation, pivot on (ox,oy) so the shape spins about its own center. A group maps its
    # pivot to (translate + pivot) regardless of scale/rotation, so translate = (cx-ox, cy-oy)
    # keeps the icon centered exactly on (cx,cy) at any tilt.
    tx, ty = cx - ox, cy - oy
    return (f'    <group android:scaleX="{f(s)}" android:scaleY="{f(s)}" '
            f'android:rotation="{f(rot)}" android:pivotX="{f(ox)}" android:pivotY="{f(oy)}" '
            f'android:translateX="{f(tx)}" android:translateY="{f(ty)}">\n'
            f'      <path android:pathData="{d}" android:fillColor="{fill}" />\n'
            f'    </group>')


def sun(cx, cy, s, col):
    return scaled(SUN_D, s, cx, cy, 12, 12, col)


def moon(cx, cy, s, col):
    return scaled(MOON_D, s, cx, cy, 7, 12, col, rot=MOON_TILT)


def fill_union(parts, fill):
    """Render a list of subpaths as SEPARATE filled <path> elements.

    Vector drawables apply their fill rule (nonZero) per <path>, so concatenating lobes
    and a base rectangle into one pathData makes opposite windings cancel where they
    overlap (the classic "teeth" gaps in a cloud bottom). Emitting each opaque piece as
    its own path unions them cleanly regardless of winding direction.
    """
    return "\n".join(path(d, fill=fill) for d in parts)


def cloud_parts(cx, cy, s=1.0):
    # Flat bottom spans only between the two side lobes so its corners meet the circles
    # tangentially (no rectangular tabs poking out past the rounded sides).
    return [
        circ(cx - 4 * s, cy + 0.5 * s, 4 * s),
        circ(cx + 4 * s, cy + 0.5 * s, 4 * s),
        circ(cx, cy - 2.4 * s, 5 * s),
        rect(cx - 4 * s, cy + 0.5 * s, cx + 4 * s, cy + 4.5 * s),
    ]


def cloud(cx, cy, s, fill):
    return fill_union(cloud_parts(cx, cy, s), fill)


def cloud_flat_parts(cx, cy, s=1.0):
    """Wide, low-profile cloud (stratus) - used for overcast layers and fog banks."""
    return [
        circ(cx - 6 * s, cy + 0.9 * s, 3.0 * s),
        circ(cx - 2 * s, cy - 0.6 * s, 3.7 * s),
        circ(cx + 2.5 * s, cy - 0.4 * s, 3.5 * s),
        circ(cx + 6 * s, cy + 0.9 * s, 3.0 * s),
        rect(cx - 6 * s, cy + 0.9 * s, cx + 6 * s, cy + 3.9 * s),
    ]


def cloud_flat(cx, cy, s, fill):
    return fill_union(cloud_flat_parts(cx, cy, s), fill)


def cloud_storm_parts(cx, cy, s=1.0):
    """Tall, heavy cumulonimbus with an extra top lobe - used for thunderstorms."""
    return [
        circ(cx - 4.6 * s, cy + 0.6 * s, 4.2 * s),
        circ(cx + 4.6 * s, cy + 0.6 * s, 4.2 * s),
        circ(cx - 1.6 * s, cy - 2.9 * s, 4.5 * s),
        circ(cx + 3.4 * s, cy - 3.7 * s, 3.5 * s),
        rect(cx - 4.6 * s, cy + 0.6 * s, cx + 4.6 * s, cy + 4.8 * s),
    ]


def cloud_storm(cx, cy, s, fill):
    return fill_union(cloud_storm_parts(cx, cy, s), fill)


def rain(xs, y0, length, slant, col, sw=1.7):
    d = "".join(f"M{f(x)},{f(y0)} l{f(-slant)},{f(length)} " for x in xs)
    return path(d.strip(), stroke=col, sw=sw)


def flake(x, y, r, col, sw=1.3):
    d = (f"M{f(x)},{f(y - r)} L{f(x)},{f(y + r)} "
         f"M{f(x - r * 0.87)},{f(y - r * 0.5)} L{f(x + r * 0.87)},{f(y + r * 0.5)} "
         f"M{f(x - r * 0.87)},{f(y + r * 0.5)} L{f(x + r * 0.87)},{f(y - r * 0.5)}")
    return path(d, stroke=col, sw=sw)


def flakes(pts, col, r=1.4):
    return "\n".join(flake(x, y, r, col) for (x, y) in pts)


def dots(pts, r, col):
    d = "".join(circ(x, y, r) for (x, y) in pts)
    return path(d, fill=col)


def bolt(col, cx=12.0, cy=15.5, s=1.0):
    # Simple lightning polygon pointing down.
    pts = [(0.6, -3.5), (-3.4, 3.5), (-0.4, 3.5), (-1.6, 8.5), (3.6, 1.0), (0.4, 1.0)]
    d = f"M{f(cx + pts[0][0]*s)},{f(cy + pts[0][1]*s)} "
    d += " ".join(f"L{f(cx + px*s)},{f(cy + py*s)}" for (px, py) in pts[1:])
    d += " Z"
    return path(d, fill=col)


def fog_lines(ys, x1, x2, col, sw=1.7):
    d = "".join(f"M{f(x1)},{f(y)} H{f(x2)} " for y in ys)
    return path(d.strip(), stroke=col, sw=sw)


def wave(x, y, w, col, sw=1.7):
    d = f"M{f(x)},{f(y)} q{f(w*0.25)},{f(-2)} {f(w*0.5)},0 t{f(w*0.5)},0"
    return path(d, stroke=col, sw=sw)


def wavy_line(x1, x2, y, amp, col, sw=1.7):
    """A horizontal line drawn as alternating up/down bumps (a real wave, not a straight bar)."""
    n = max(2, int(round((x2 - x1) / 4.2)))
    seg = (x2 - x1) / n
    d = f"M{f(x1)},{f(y)} "
    for i in range(n):
        cx = x1 + seg * (i + 0.5)
        ex = x1 + seg * (i + 1)
        cy = y + (amp if i % 2 == 0 else -amp)
        d += f"Q{f(cx)},{f(cy)} {f(ex)},{f(y)} "
    return path(d.strip(), stroke=col, sw=sw)


def wavy_lines(ys, x1, x2, amp, col, sw=1.7):
    return "\n".join(wavy_line(x1, x2, y, amp, col, sw) for y in ys)


# ----------------------------------------------------------------------------- builders
# Each builder returns a list of element strings for the given palette + night flag.
def b_clear(p, night):
    return [moon(12, 12, 0.95, p["sun"])] if night else [sun(12, 12, 0.92, p["sun"])]


def b_lumcloud(p, night, lum_s, cloud_s, cloud_fill=None):
    lum = moon(8.5, 8.5, lum_s, p["sun"]) if night else sun(8.5, 8.5, lum_s, p["sun"])
    return [lum, cloud(13, 13.5, cloud_s, cloud_fill or p["cloud"])]


def b_mainly_clear(p, night):
    return b_lumcloud(p, night, 0.62 if night else 0.6, 0.62)


def b_partly_cloudy(p, night):
    return b_lumcloud(p, night, 0.66 if night else 0.64, 0.78)


def b_broken(p, night):
    # "Cloudy": two distinct puffy clouds.
    return [cloud(9.5, 10.5, 0.6, p["cloud_back"]), cloud(14, 14, 0.85, p["cloud"])]


def b_overcast(p, night):
    # Full grey cover: two stacked flat layers, darker - visually heavier than "cloudy".
    return [cloud_flat(12.5, 9.6, 0.82, p["cloud_back"]),
            cloud_flat(11.5, 13.2, 0.98, p["cloud_dark"])]


def precip_cloud(p, night):
    # Same light cloud day and night; night is signalled by the moon added behind it.
    return cloud(12, 9.2, 0.92, p["cloud"])


def b_rain(count, sw=1.8, slant=1.2, y0=15.0, length=4.2):
    def build(p, night):
        span = 4.4
        if count == 1:
            xs = [12.0]
        else:
            xs = [12 - span + i * (2 * span / (count - 1)) for i in range(count)]
        return [precip_cloud(p, night), rain(xs, y0, length, slant, p["rain"], sw)]
    return build


def b_shower(count):
    return b_rain(count, sw=1.8, slant=2.4, y0=15.0, length=4.0)


def b_drizzle(count):
    return b_rain(count, sw=1.5, slant=0.6, y0=15.5, length=2.4)


def b_snow(count):
    def build(p, night):
        span = 4.0
        if count == 1:
            xs = [12.0]
        else:
            xs = [12 - span + i * (2 * span / (count - 1)) for i in range(count)]
        pts = [(x, 18.0 if i % 2 == 0 else 20.0) for i, x in enumerate(xs)]
        return [precip_cloud(p, night), flakes(pts, p["flake"])]
    return build


def b_snow_grains(p, night):
    pts = [(9, 17.5), (12, 19), (15, 17.5), (10.5, 20.5), (13.5, 20.5)]
    return [precip_cloud(p, night), dots(pts, 0.7, p["flake"])]


def b_sleet(p, night):
    c = precip_cloud(p, night)
    return [c, rain([9.5, 14.5], 15.2, 3.4, 1.0, p["rain"], 1.7),
            flakes([(12, 18.5)], p["flake"])]


def b_rain_snow(light=False):
    def build(p, night):
        c = precip_cloud(p, night)
        drops = [10.5] if light else [8.5, 12.5]
        flk = [(14.5, 18.5)] if not light else [(14, 18.5)]
        return [c, rain(drops, 15.2, 3.6, 1.0, p["rain"], 1.7), flakes(flk, p["flake"])]
    return build


def b_freezing_rain(p, night):
    c = precip_cloud(p, night)
    return [c, rain([9.5, 14.5], 15.0, 3.2, 1.0, p["rain"], 1.7),
            dots([(12, 20.5)], 0.9, p["hail"])]


def b_freezing_drizzle(p, night):
    c = precip_cloud(p, night)
    return [c, rain([10, 14], 15.5, 2.0, 0.5, p["rain"], 1.4),
            dots([(12, 20)], 0.8, p["hail"])]


def b_thunder(rain_count=0, hail=False, heavy=False):
    def build(p, night):
        c = cloud_storm(12, 9.4, 0.98 if heavy else 0.92, p["cloud_dark"])
        elems = [c, bolt(p["bolt"], cx=12, cy=14.8, s=1.05 if heavy else 0.95)]
        if rain_count:
            xs = [7.5, 16.5] if rain_count >= 2 else [8.0]
            elems.append(rain(xs, 15.0, 4.0, 1.0, p["rain"], 1.7))
        if hail:
            elems.append(dots([(8, 20), (16, 20)], 1.0, p["hail"]))
        return elems
    return build


def b_fog(nlines):
    def build(p, night):
        # Flat stratus bank (distinct from puffy precip clouds) over wavy fog lines.
        return [cloud_flat(12, 8.8, 0.82, p["cloud"]),
                wavy_lines([15.5, 18.0, 20.5][:nlines], 5.0, 19.0, 0.9, p["fog"], 1.8)]
    return build


def b_rime_fog(p, night):
    return [cloud_flat(12, 8.6, 0.82, p["cloud"]),
            wavy_lines([15.5, 18.0], 5.0, 19.0, 0.9, p["fog"], 1.8),
            dots([(8, 20.7), (12, 20.7), (16, 20.7)], 0.8, p["hail"])]


def b_mist(p, night):
    return [cloud_flat(12, 8.4, 0.76, p["cloud_back"]),
            wavy_lines([16.5, 19.5], 5.5, 18.5, 0.8, p["fog"], 1.5)]


def b_haze(p, night):
    lum = moon(11, 9, 0.7, p["sun"]) if night else sun(11, 9, 0.66, p["sun"])
    return [lum, fog_lines([15.5, 18.5, 21.0], 4.5, 19.5, p["fog"], 1.7)]


def b_smoke(p, night):
    return [wave(7, 20, 4, p["ash"], 1.7), wave(13, 20, 4, p["ash"], 1.7),
            wave(9.5, 16, 4, p["ash"], 1.7)]


def b_sand(p, night):
    return [fog_lines([9, 12, 15, 18], 4, 20, p["sand"], 1.9)]


def b_dust(p, night):
    return [fog_lines([10, 13.5, 17], 4, 20, p["sand"], 1.9),
            dots([(7, 20.5), (17, 8.5)], 0.9, p["sand"])]


def b_dust_whirls(p, night):
    # An actual inward spiral so it reads as a whirl rather than a straight streak.
    spiral = ("M18,9 C20,13 16.5,18 12,17 "
              "C8.2,16.2 7.8,11.5 11,10.5 "
              "C13.4,9.8 14.2,12.8 12.6,13.6")
    return [path(spiral, stroke=p["sand"], sw=1.9)]


def b_volcanic_ash(p, night):
    mtn = "M4,20 L11,9 L14,14 L16,11 L20,20 Z"
    return [path(mtn, fill=p["ash"]),
            dots([(11, 6), (13, 4), (9, 4.5)], 0.9, p["ash"])]


def b_squall(p, night):
    # Gusts of wind: a wavy body ending in a curling hook (Material "air" feel).
    col = p["tornado"]
    sw = 1.9
    out = []
    for (y, x2, cr) in [(9.0, 15.5, 2.3), (14.0, 17.5, 2.5)]:
        out.append(wavy_line(4.0, x2, y, 1.0, col, sw))
        out.append(path(f"M{f(x2)},{f(y)} a{f(cr)},{f(cr)} 0 1,1 {f(-cr)},{f(cr)}",
                        stroke=col, sw=sw))
    return out


def b_tornado(p, night):
    d = ("M4,5 H20 M6,8 H19 M8,11 H17 M10,14 H16 M12,17 H15.5 M13.5,20 H15")
    tail = "M15,20 q1,-1 0.5,-3"
    return [path(d, stroke=p["tornado"], sw=1.8), path(tail, stroke=p["tornado"], sw=1.6)]


def b_unknown(p, night):
    return [cloud(12, 12, 0.9, p["cloud"]),
            path("M12,14 v0.2 M12,10 a2,2 0 1,1 2,2 q-2,0.5 -2,2",
                 stroke="#FFFFFF", sw=1.6)]


BUILDERS = {
    "clear": b_clear,
    "mainly_clear": b_mainly_clear,
    "partly_cloudy": b_partly_cloudy,
    "broken_clouds": b_broken,
    "overcast": b_overcast,

    "mist": b_mist,
    "smoke": b_smoke,
    "haze": b_haze,
    "dust_whirls": b_dust_whirls,
    "fog": b_fog(3),
    "rime_fog": b_rime_fog,
    "sand": b_sand,
    "dust": b_dust,
    "volcanic_ash": b_volcanic_ash,
    "squall": b_squall,
    "tornado": b_tornado,

    "light_drizzle": b_drizzle(2),
    "drizzle": b_drizzle(3),
    "heavy_drizzle": b_drizzle(4),
    "light_drizzle_rain": b_rain(2, sw=1.6, slant=0.9, length=3.4),
    "drizzle_rain": b_rain(3, sw=1.6, slant=0.9, length=3.4),
    "heavy_drizzle_rain": b_rain(4, sw=1.7, slant=0.9, length=3.6),
    "shower_drizzle": b_shower(2),
    "freezing_drizzle": b_freezing_drizzle,

    "light_rain": b_rain(2),
    "moderate_rain": b_rain(3),
    "heavy_rain": b_rain(4, sw=2.0),
    "very_heavy_rain": b_rain(5, sw=2.0),
    "extreme_rain": b_rain(6, sw=2.1),
    "freezing_rain": b_freezing_rain,
    "light_shower_rain": b_shower(2),
    "shower_rain": b_shower(3),
    "heavy_shower_rain": b_shower(4),
    "ragged_shower_rain": b_shower(3),

    "light_snow": b_snow(2),
    "snow": b_snow(3),
    "heavy_snow": b_snow(4),
    "sleet": b_sleet,
    "light_shower_sleet": b_sleet,
    "shower_sleet": b_sleet,
    "light_rain_snow": b_rain_snow(light=True),
    "rain_snow": b_rain_snow(light=False),
    "light_shower_snow": b_snow(2),
    "shower_snow": b_snow(3),
    "heavy_shower_snow": b_snow(4),
    "snow_grains": b_snow_grains,

    "thunderstorm_light_rain": b_thunder(rain_count=2),
    "thunderstorm_rain": b_thunder(rain_count=2),
    "thunderstorm_heavy_rain": b_thunder(rain_count=2, heavy=True),
    "light_thunderstorm": b_thunder(),
    "thunderstorm": b_thunder(),
    "heavy_thunderstorm": b_thunder(heavy=True),
    "ragged_thunderstorm": b_thunder(),
    "thunderstorm_light_drizzle": b_thunder(rain_count=1),
    "thunderstorm_drizzle": b_thunder(rain_count=2),
    "thunderstorm_heavy_drizzle": b_thunder(rain_count=2),
    "thunderstorm_hail": b_thunder(hail=True),

    "unknown": b_unknown,
}


def vector(elements):
    body = "\n".join(elements)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- AUTO-GENERATED by tools/gen_weather_icons.py. Do not edit by hand. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
            f"{body}\n</vector>\n")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    count = 0
    for key, builder in BUILDERS.items():
        for night in (False, True):
            p = NIGHT if night else DAY
            elems = builder(p, night)
            # Every night icon gets a crescent moon. Sky-visible conditions already draw one;
            # for the rest, tuck a moon behind the cloud (or in a top corner if there's no cloud).
            if night and key not in SKY_STATUSES:
                if key in NO_CLOUD_STATUSES:
                    elems = elems + [moon(18.7, 5.0, 0.5, MOON_COLOR)]
                else:
                    elems = [moon(5.7, 5.1, 0.44, MOON_COLOR)] + elems
            xml = vector(elems)
            name = f"wx_{key}_{'night' if night else 'day'}.xml"
            with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as fh:
                fh.write(xml)
            count += 1
    print(f"Wrote {count} weather icon drawables to {OUT_DIR}")


if __name__ == "__main__":
    main()
