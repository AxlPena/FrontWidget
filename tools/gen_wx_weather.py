#!/usr/bin/env python3
"""
SHIPPING weather-icon generator (multi-tone), driven by the "Material Design Weather Icon
Prompt Matrix". Emits the production set the widget actually loads:
    res/drawable/wx_<key>_day.xml
    res/drawable/wx_<key>_night.xml

Design rules (per the matrix):
  - Authentic Google Material Symbols glyphs (sun=light_mode, moon=nightlight, cloud, bolt,
    snowflake, air, foggy/mist, tornado/cyclone, volcano) placed at natural Material positions
    on the native 960 grid, NOT the old hand-tuned wx compositions.
  - Multi-tone flat fills from the color-token mapping (amber sun / lavender moon / slate cloud /
    blue rain / light-blue snow / amber bolt / grey fog+ash / tan sand / pale hail / brown lava).
  - Precip is drawn as clean uniform strokes (rain = 60 deg diagonal streaks, drizzle = short
    vertical dashes, snow = six-point flakes, hail = open circles, grains = tiny squares) so the
    count/spacing matches the matrix exactly and the line weight stays uniform.
  - Every scene carries a luminary that swaps by time of day: day = sun, night = moon. Sky and
    atmosphere scenes place it themselves; precip/thunder scenes get one peeking behind the cloud.
    Overcast hides it entirely.

Glyph paths + a few helpers are reused from gen_ms_weather.py so both stay in sync.
Run from repo root:  python tools/gen_wx_weather.py
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_ms_weather as ms  # authentic Material Symbols glyph paths + place()/circ()/f()

OUT_DIR = ms.OUT_DIR
PREFIX = "wx"

# ------------------------------------------------------------------ palette (color-token mapping)
SUN = ms.SUN            # #FFB300
MOON = ms.MOON          # #C7B3F0
CLOUD = ms.CLOUD        # #8CA0B3
CLOUD_LIGHT = ms.CLOUD_LIGHT  # #AEBAC6
CLOUD_DARK = ms.CLOUD_DARK    # #6E7F8F
RAIN = ms.RAIN          # #4FA6E8
SNOW = ms.SNOW          # #8FD0F5
BOLT = ms.BOLT          # #FFC107
FOG = ms.FOG            # #AEBAC6
SAND = ms.SAND          # #D8B95E
ASH = ms.ASH            # #9AA0A6
HAIL = ms.HAIL          # #CDE9F7
LAVA = ms.LAVA          # #7E6E64
ASH_DARK = ms.ASH_DARK  # #4A4A4A

f = ms.f
place = ms.place

# ------------------------------------------------------------------ primitives
def sun_at(cx, cy, s):
    return place(ms.G_SUN, s, cx, cy, SUN)


def moon_at(cx, cy, s, ox=480.0, oy=-480.0):
    """Crescent moon, always mirrored (scaleX negative) so its belly faces RIGHT.

    Using one facing everywhere keeps the moon consistent across every icon — the standalone
    clear-night moon, the atmosphere scenes, and the cloud-peek scenes all match."""
    tx = cx + ox * s
    ty = cy - oy * s
    return (f'  <group android:scaleX="{f(-s)}" android:scaleY="{f(s)}" '
            f'android:translateX="{f(tx)}" android:translateY="{f(ty)}">\n'
            f'    <path android:pathData="{ms.G_MOON}" android:fillColor="{MOON}" />\n'
            f'  </group>')


def moon_right(cx, cy, s):
    """Alias for [moon_at]; the moon is now mirrored everywhere so this needs no special case."""
    return moon_at(cx, cy, s)


def lum(night, cx, cy, s):
    return moon_at(cx, cy, s) if night else sun_at(cx, cy, s)


def corner_lum(night, cx, cy, s):
    """Luminary for scenes that park it in the top-right corner: mirrored moon at night so its
    belly (not a thin sliver) faces out; plain sun by day."""
    return moon_right(cx, cy, s) if night else sun_at(cx, cy, s)


def spiral(cx, cy, r0, rn, turns, col, w, n=64):
    """A minimalist stroked vortex spiral (used for dust whirls)."""
    pts = []
    for i in range(n):
        t = i / (n - 1)
        ang = turns * 2 * math.pi * t - math.pi / 2
        r = r0 + (rn - r0) * t
        pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    d = "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts)
    return stroke(d, col, w)


def raincloud(fill=CLOUD):
    """Google's precip cloud (leaves room below for streaks)."""
    return place(ms.G_RAINCLOUD, 1.0, 480, 480, fill)


def bigcloud(cx, cy, s, fill):
    return place(ms.G_CLOUD, s, cx, cy, fill)


def stroke(d, col, w, join=False):
    extra = ' android:strokeLineJoin="round"' if join else ""
    return (f'  <path android:strokeColor="{col}" android:strokeWidth="{f(w)}" '
            f'android:strokeLineCap="round"{extra} android:pathData="{d.strip()}" />')


def fill(d, col):
    return f'  <path android:fillColor="{col}" android:pathData="{d.strip()}" />'


def spread(n, cx=480.0, span=175.0):
    if n <= 1:
        return [cx]
    return [cx - span + i * (2 * span / (n - 1)) for i in range(n)]


def diag(xs, y0=675.0, ln=170.0, col=RAIN, w=46.0):
    """Diagonal rain streaks (~60 deg from horizontal, falling down-left)."""
    dx, dy = -ln * 0.5, ln * 0.866
    d = "".join(f"M{f(x)},{f(y0)} l{f(dx)},{f(dy)} " for x in xs)
    return stroke(d, col, w)


def dashes(xs, y0=690.0, ln=95.0, col=RAIN, w=44.0):
    """Short vertical drizzle dashes."""
    d = "".join(f"M{f(x)},{f(y0)} l0,{f(ln)} " for x in xs)
    return stroke(d, col, w)


def bars(xs, y0=665.0, ln=210.0, col=RAIN, w=70.0):
    """Thick vertical deluge bars (extreme rain)."""
    d = "".join(f"M{f(x)},{f(y0)} l0,{f(ln)} " for x in xs)
    return stroke(d, col, w)


def snow_pts(pts, s=0.2):
    return ms.flakes(pts, s, SNOW)


def snow_row(n, rows=(720.0, 815.0), s=0.2, span=175.0):
    xs = spread(n, 480.0, span)
    pts = [(x, rows[i % len(rows)]) for i, x in enumerate(xs)]
    return snow_pts(pts, s)


def hail(pts, r=32.0, col=HAIL, w=28.0):
    d = "".join(ms.circ(x, y, r) for (x, y) in pts)
    return stroke(d, col, w)


def squares(pts, half=24.0, col=SNOW):
    d = "".join(f"M{f(x - half)},{f(y - half)} h{f(2 * half)} v{f(2 * half)} h{f(-2 * half)} Z "
                for (x, y) in pts)
    return fill(d, col)


def bolt(cx=470.0, cy=650.0, s=0.42, col=BOLT):
    return ms.bolt(cx, cy, s, col)


# ------------------------------------------------------------------ sky / clouds
def b_clear(night):
    return [lum(night, 480, 480, 0.95)]


def b_mainly_clear(night):
    # Prominent luminary, tiny cloud hint overlapping the bottom-right.
    return [lum(night, 420, 400, 0.6), bigcloud(640, 650, 0.46, CLOUD)]


def b_partly_cloudy(night):
    # Cloud with the luminary half-peeking from the background: sun top-left (day),
    # moon top-right (night).
    if night:
        return [moon_right(700, 300, 0.62), bigcloud(415, 575, 0.74, CLOUD)]
    return [sun_at(315, 305, 0.58), bigcloud(560, 575, 0.74, CLOUD)]


def b_broken(night):
    # Two overlapping clouds; luminary peeks from the rear layer (mirrored for night so the
    # moon sits on the right).
    if night:
        return [moon_right(700, 270, 0.54),
                bigcloud(590, 405, 0.48, CLOUD_LIGHT),
                bigcloud(375, 575, 0.78, CLOUD)]
    return [sun_at(290, 275, 0.5),
            bigcloud(370, 405, 0.48, CLOUD_LIGHT),
            bigcloud(585, 575, 0.78, CLOUD)]


def b_overcast(night):
    # Dense flat cloud filling the frame, with just a sliver of sun/moon peeking over the top.
    return [lum(night, 360, 250, 0.44),
            bigcloud(430, 430, 0.6, CLOUD_LIGHT),
            bigcloud(500, 525, 1.02, CLOUD_DARK)]


# ------------------------------------------------------------------ atmosphere
def hlines(ys, x1, x2, col, w):
    d = "".join(f"M{f(x1)},{f(y)} H{f(x2)} " for y in ys)
    return stroke(d, col, w)


def broken_hlines(rows, x1, x2, col, w, gap, segs, stagger=90):
    """Horizontal haze/fog lines split into `segs` segments separated by `gap`, staggered row to
    row. The gap size encodes intensity: big gaps = thin/patchy (mist), small gaps = dense (fog)."""
    out = []
    total = x2 - x1
    seg_len = (total - gap * (segs - 1)) / segs
    for i, y in enumerate(rows):
        off = (i % 2) * stagger
        x = x1 - off
        d = ""
        for _ in range(segs + 1):
            sx = max(x, x1)
            ex = min(x + seg_len, x2)
            if ex > sx:
                d += f"M{f(sx)},{f(y)} H{f(ex)} "
            x += seg_len + gap
        out.append(stroke(d, col, w))
    return out


def b_mist(night):
    # Lightest: short, very broken lines (big gaps) over a faint luminary.
    return [lum(night, 480, 380, 0.5),
            *broken_hlines([560, 680, 800], 250, 710, FOG, 54, gap=120, segs=2, stagger=120)]


def b_smoke(night):
    # Rising heat-shimmer (air/heat) overlapping the luminary.
    return [lum(night, 480, 430, 0.5), place(ms.G_HEAT, 0.62, 500, 560, ASH)]


def b_haze(night):
    # Medium: longer lines with a single moderate break, over the luminary.
    return [lum(night, 480, 340, 0.52),
            *broken_hlines([640, 770], 175, 785, FOG, 60, gap=70, segs=2, stagger=80)]


def b_dust_whirls(night):
    # Same wind glyph as squalls, tinted the sand/dust color.
    return [corner_lum(night, 725, 255, 0.4), place(ms.G_AIR, 0.7, 440, 540, SAND)]


def b_fog(night):
    # Densest: four nearly-full lines with only a small break, covering the luminary's lower half.
    return [lum(night, 480, 380, 0.6),
            *broken_hlines([520, 630, 740, 850], 150, 810, FOG, 56, gap=42, segs=2, stagger=70)]


def b_rime_fog(night):
    # Dense like fog (small breaks) but with icy crystal accents on the ends.
    return [lum(night, 480, 360, 0.54),
            *broken_hlines([560, 690, 820], 175, 785, FOG, 52, gap=48, segs=2, stagger=70),
            snow_pts([(250, 560), (720, 560)], 0.13)]


def b_sand(night):
    pts = [(300, 300), (680, 320), (250, 560), (720, 600), (360, 760), (620, 780), (480, 470)]
    return [lum(night, 480, 470, 0.48), ms_dots(pts, 26, SAND)]


def ms_dots(pts, r, col):
    d = "".join(ms.circ(x, y, r) for (x, y) in pts)
    return fill(d, col)


def b_dust(night):
    pts = [(220, 800), (330, 830), (440, 810), (550, 830), (660, 805), (770, 830),
           (280, 720), (500, 720), (700, 720)]
    return [lum(night, 480, 400, 0.46), ms_dots(pts, 22, SAND)]


def b_volcanic_ash(night):
    # Big volcano + big ash plume, centered as one composition filling the icon.
    return [corner_lum(night, 850, 165, 0.24),
            place(ms.G_VOLCANO, 1.0, 480, 610, LAVA),
            place(ms.G_MIST, 1.2, 480, 380, ASH_DARK)]


def b_squall(night):
    return [corner_lum(night, 725, 255, 0.4), place(ms.G_AIR, 0.7, 440, 540, CLOUD)]


def b_tornado(night):
    return [corner_lum(night, 735, 245, 0.38), place(ms.G_TORNADO, 0.74, 450, 520, CLOUD)]


def b_hurricane(night):
    # Cyclone glyph with a light "eye" at the centre; bold dark body reads as a strong system.
    return [place(ms.G_CYCLONE, 0.98, 480, 470, CLOUD_DARK),
            ms_dots([(480, 470)], 40, CLOUD_LIGHT)]


def b_tropical_storm(night):
    # Same cyclone symbol, lighter and a touch smaller than a full hurricane.
    return [place(ms.G_CYCLONE, 0.82, 480, 470, CLOUD)]


# ------------------------------------------------------------------ drizzle (vertical dashes)
def b_drizzle(n, tight=False, dark=False):
    def build(night):
        span = 165.0 if tight else 175.0
        w = 36.0 if tight else 44.0
        return [raincloud(CLOUD_DARK if dark else CLOUD), dashes(spread(n, 480, span), w=w)]
    return build


def b_drizzle_rain(n, dark=False):
    # Alternating short dashes + full diagonal streaks.
    def build(night):
        xs = spread(n, 480, 185.0)
        dsh = [x for i, x in enumerate(xs) if i % 2 == 0]
        strk = [x for i, x in enumerate(xs) if i % 2 == 1]
        return [raincloud(CLOUD_DARK if dark else CLOUD),
                dashes(dsh, ln=85), diag(strk, ln=150)]
    return build


def b_shower_drizzle(night):
    # Angled dashes falling isolated on one (right) side.
    return [raincloud(), dashes([540, 620, 700], y0=690, ln=90)]


def b_freezing_drizzle(night):
    # Dashes interlocked with a crisp ice flake.
    return [raincloud(), dashes([400, 560], ln=80), snow_pts([(480, 850)], 0.16)]


# ------------------------------------------------------------------ rain (diagonal streaks)
def b_rain(n, dark=False, ln=170.0, w=46.0):
    def build(night):
        return [raincloud(CLOUD_DARK if dark else CLOUD), diag(spread(n, 480, 180.0), ln=ln, w=w)]
    return build


def b_very_heavy_rain(night):
    # Double cloud + tiered overlapping streaks.
    return [bigcloud(360, 360, 0.5, CLOUD_LIGHT), raincloud(CLOUD_DARK),
            diag(spread(4, 480, 190.0), y0=650, ln=150),
            diag(spread(3, 480, 130.0), y0=740, ln=150)]


def b_extreme_rain(night):
    return [raincloud(CLOUD_DARK), bars(spread(4, 480, 165.0))]


def b_light_showers(night):
    # Offset cloud, two isolated streaks from its right curve.
    return [raincloud(), diag([560, 650], ln=165)]


def b_shower_rain(night):
    # Grouped bursts separated by gaps.
    return [raincloud(), diag([360, 420], ln=160), diag([560, 620], ln=160)]


def b_heavy_shower_rain(night):
    return [raincloud(CLOUD_DARK), diag([330, 385, 440], ln=165), diag([545, 600, 655], ln=165)]


def b_ragged_shower_rain(night):
    # Uneven, multi-angled jagged streaks.
    c = raincloud()
    a = stroke("M360,670 l-70,150", RAIN, 46)
    b = stroke("M470,690 l-50,160", RAIN, 46)
    d = stroke("M600,660 l-95,140", RAIN, 46)
    return [c, a, b, d]


# ------------------------------------------------------------------ snow / mixed
def b_snow(n, dark=False):
    def build(night):
        rows = (700.0, 800.0)
        s = 0.2 if n <= 3 else 0.17
        return [raincloud(CLOUD_DARK if dark else CLOUD), snow_row(n, rows=rows, s=s)]
    return build


def b_sleet(night):
    # Alternating one rain stroke + one flake.
    return [raincloud(), diag([380], ln=150), snow_pts([(560, 760)], 0.19),
            diag([650], ln=150)]


def b_light_shower_sleet(night):
    return [raincloud(), diag([560], ln=150), snow_pts([(660, 800)], 0.18)]


def b_shower_sleet(night):
    return [raincloud(), diag([360], ln=150), snow_pts([(470, 780)], 0.18),
            diag([600], ln=150)]


def b_light_rain_snow(night):
    # Dominant rain + a single accent flake.
    return [raincloud(), diag([360, 470, 580], ln=155), snow_pts([(660, 820)], 0.18)]


def b_rain_snow(night):
    # 50/50 balance rain + snow.
    return [raincloud(), diag([360, 500], ln=155), snow_pts([(600, 760), (700, 830)], 0.19)]


def b_light_snow_showers(night):
    # Two sparse flakes side-swept to the right.
    return [raincloud(), snow_pts([(560, 730), (660, 820)], 0.19)]


def b_snow_showers(night):
    # Grouped flake clusters.
    return [raincloud(), snow_pts([(360, 740), (420, 820)], 0.17),
            snow_pts([(580, 740), (640, 820)], 0.17)]


def b_heavy_shower_snow(night):
    return [raincloud(CLOUD_DARK), snow_row(6, rows=(700.0, 800.0), s=0.15)]


def b_snow_grains(night):
    pts = [(360, 730), (470, 730), (580, 730), (410, 830), (520, 830)]
    return [raincloud(), squares(pts)]


# ------------------------------------------------------------------ thunderstorm
def b_thunder(rain=0, drizzle=0, bolts=1, big=False, hail_n=0):
    def build(night):
        elems = [raincloud(CLOUD_DARK)]
        if rain:
            elems.append(diag(spread(rain, 480, 190.0), y0=690, ln=150))
        if drizzle:
            elems.append(dashes(spread(drizzle, 480, 180.0), y0=700, ln=85))
        if bolts == 1:
            elems.append(bolt(470, 690 if big else 660, 0.5 if big else 0.42))
        elif bolts == 2:
            elems.append(bolt(390, 660, 0.36))
            elems.append(bolt(600, 700, 0.32))
        else:  # ragged: multiple small erratic bolts
            elems.append(bolt(370, 660, 0.3))
            elems.append(bolt(520, 700, 0.3))
            elems.append(bolt(650, 660, 0.3))
        if hail_n:
            elems.append(hail([(360, 840), (480, 870), (600, 840)]))
        return elems
    return build


def b_unknown(night):
    return [bigcloud(480, 470, 0.9, CLOUD), place(ms.G_QUESTION, 0.4, 480, 470, "#FFFFFF")]


# ------------------------------------------------------------------ registry
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
    "fog": b_fog,
    "rime_fog": b_rime_fog,
    "sand": b_sand,
    "dust": b_dust,
    "volcanic_ash": b_volcanic_ash,
    "squall": b_squall,
    "tornado": b_tornado,
    "tropical_storm": b_tropical_storm,
    "hurricane": b_hurricane,

    "light_drizzle": b_drizzle(2),
    "drizzle": b_drizzle(4),
    "heavy_drizzle": b_drizzle(6, tight=True, dark=True),
    "light_drizzle_rain": b_drizzle_rain(4),
    "drizzle_rain": b_drizzle_rain(4),
    "heavy_drizzle_rain": b_drizzle_rain(6, dark=True),
    "shower_drizzle": b_shower_drizzle,
    "freezing_drizzle": b_freezing_drizzle,

    "light_rain": b_rain(2),
    "moderate_rain": b_rain(4),
    "heavy_rain": b_rain(6, dark=True, w=44),
    "very_heavy_rain": b_very_heavy_rain,
    "extreme_rain": b_extreme_rain,
    "freezing_rain": lambda night: [raincloud(), diag([380, 560], ln=160), snow_pts([(650, 840)], 0.15)],
    "light_shower_rain": b_light_showers,
    "shower_rain": b_shower_rain,
    "heavy_shower_rain": b_heavy_shower_rain,
    "ragged_shower_rain": b_ragged_shower_rain,

    "light_snow": b_snow(2),
    "snow": b_snow(4),
    "heavy_snow": b_snow(6, dark=True),
    "sleet": b_sleet,
    "light_shower_sleet": b_light_shower_sleet,
    "shower_sleet": b_shower_sleet,
    "light_rain_snow": b_light_rain_snow,
    "rain_snow": b_rain_snow,
    "light_shower_snow": b_light_snow_showers,
    "shower_snow": b_snow_showers,
    "heavy_shower_snow": b_heavy_shower_snow,
    "snow_grains": b_snow_grains,

    "thunderstorm_light_rain": b_thunder(rain=2),
    "thunderstorm_rain": b_thunder(rain=4),
    "thunderstorm_heavy_rain": b_thunder(rain=4, bolts=2),
    "light_thunderstorm": b_thunder(bolts=1, rain=0),
    "thunderstorm": b_thunder(bolts=1, big=True),
    "heavy_thunderstorm": b_thunder(bolts=1, big=True, rain=0),
    "ragged_thunderstorm": b_thunder(bolts=3),
    "thunderstorm_light_drizzle": b_thunder(drizzle=2),
    "thunderstorm_drizzle": b_thunder(drizzle=4),
    "thunderstorm_heavy_drizzle": b_thunder(drizzle=6),
    "thunderstorm_hail": b_thunder(bolts=1, hail_n=3),

    "unknown": b_unknown,
}

# Scenes that draw their own luminary (sky + atmosphere). Everything else is precip and gets a
# small luminary peeking behind the cloud (sun by day, moon by night). Overcast/unknown hide it.
SELF_LUM = {"clear", "mainly_clear", "partly_cloudy", "broken_clouds",
            "mist", "smoke", "haze", "dust_whirls", "fog", "rime_fog",
            "sand", "dust", "volcanic_ash", "squall", "tornado"}
NO_LUM = {"overcast", "unknown", "hurricane", "tropical_storm"}


def back_lum(night):
    """A luminary peeking prominently from behind the top corner of the precip cloud.

    Enlarged so a good portion of the disc clears the cloud silhouette and reads clearly at
    widget size. Sun sits top-left (day); moon sits top-right (night) so the two are easy to
    tell apart at a glance."""
    if night:
        return moon_right(705, 250, 0.64)
    return sun_at(290, 265, 0.58)


def vector(elements):
    body = "\n".join(elements)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- AUTO-GENERATED by tools/gen_wx_weather.py (multi-tone Material). Do not edit by hand. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="960"\n    android:viewportHeight="960">\n'
            f"{body}\n</vector>\n")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    count = 0
    for key, builder in BUILDERS.items():
        for night in (False, True):
            elems = builder(night)
            if key not in SELF_LUM and key not in NO_LUM:
                elems = [back_lum(night)] + elems
            xml = vector(elems)
            name = f"{PREFIX}_{key}_{'night' if night else 'day'}.xml"
            with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as fh:
                fh.write(xml)
            count += 1
    print(f"Wrote {count} weather drawables ({PREFIX}_*) to {OUT_DIR}")


if __name__ == "__main__":
    main()
