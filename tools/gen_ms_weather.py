#!/usr/bin/env python3
"""
PROTOTYPE weather-icon generator, pass 2 - built from Google **Material Symbols (Rounded,
filled)** glyphs, MULTI-TONE (layered fills), per user direction and the Material Symbols
guidance (https://developers.google.com/fonts/docs/material_symbols).

For every canonical WeatherStatus key it emits:
    res/drawable/msw_<key>_day.xml
    res/drawable/msw_<key>_night.xml

Rules:
  - Prefer an EXISTING official glyph; only compose when a scenario has no glyph (heavy/
    ragged/extreme variants, freezing, smoke/haze/sand/dust, etc.).
  - Multi-tone: slate cloud + blue rain + light-blue snow + amber bolt + amber sun / lavender
    moon, using authentic Google shapes (the standard rain-cloud, the `rainy` streak capsule,
    the `bolt` glyph, circles for snow) so pieces line up as Google designed them.
  - partly_cloudy / mainly_clear stay COMPOSED (sun|moon + cloud), never the packaged combo.
  - Rain/snow/thunder art is identical day and night; night just tucks a small crescent moon
    behind the cloud so the two files still differ.
  - Atmosphere reuses the semantic glyph tinted to taste: mist->sand for sand, air->ash for
    smoke, cyclone for dust whirls, volcano(+black ash) for volcanic ash, etc.

Everything is on Material's native 960 grid; place() maps a glyph's center to any target.
Run from repo root:  python tools/gen_ms_weather.py
"""
import os

OUT_DIR = os.path.join("app", "src", "main", "res", "drawable")
PREFIX = "msw"

# ------------------------------------------------------------------ palette
SUN = "#FFB300"
MOON = "#C7B3F0"
CLOUD = "#8CA0B3"
CLOUD_LIGHT = "#AEBAC6"
CLOUD_DARK = "#6E7F8F"
RAIN = "#4FA6E8"
SNOW = "#8FD0F5"
BOLT = "#FFC107"
FOG = "#AEBAC6"
SAND = "#D8B95E"
ASH = "#9AA0A6"
HAIL = "#CDE9F7"
LAVA = "#7E6E64"
ASH_DARK = "#4A4A4A"

SKY = {"clear", "mainly_clear", "partly_cloudy", "haze"}
NO_CLOUD = {"smoke", "dust", "sand", "dust_whirls", "tornado", "squall", "volcanic_ash",
            "mist", "fog", "rime_fog"}

# ------------------------------------------------------------------ raw glyph paths (fill1, native 960 grid; y in -960..0)
G_SUN = ("M480-760q-17 0-28.5-11.5T440-800v-80q0-17 11.5-28.5T480-920q17 0 28.5 11.5T520-880v80q0 17-11.5 28.5T480-760Z"
         "m198 82q-11-11-11-27.5t11-28.5l56-57q12-12 28.5-12t28.5 12q11 11 11 28t-11 28l-57 57q-11 11-28 11t-28-11Z"
         "m122 238q-17 0-28.5-11.5T760-480q0-17 11.5-28.5T800-520h80q17 0 28.5 11.5T920-480q0 17-11.5 28.5T880-440h-80Z"
         "M480-40q-17 0-28.5-11.5T440-80v-80q0-17 11.5-28.5T480-200q17 0 28.5 11.5T520-160v80q0 17-11.5 28.5T480-40Z"
         "M226-678l-57-56q-12-12-12-29t12-28q11-11 28-11t28 11l57 57q11 11 11 28t-11 28q-12 11-28 11t-28-11Z"
         "m508 509-56-57q-11-12-11-28.5t11-27.5q11-11 27.5-11t28.5 11l57 56q12 11 11.5 28T791-169q-12 12-29 12t-28-12Z"
         "M80-440q-17 0-28.5-11.5T40-480q0-17 11.5-28.5T80-520h80q17 0 28.5 11.5T200-480q0 17-11.5 28.5T160-440H80Z"
         "m89 271q-11-11-11-28t11-28l57-57q11-11 27.5-11t28.5 11q12 12 12 28.5T282-225l-56 56q-12 12-29 12t-28-12Z"
         "m311-71q-100 0-170-70t-70-170q0-100 70-170t170-70q100 0 170 70t70 170q0 100-70 170t-170 70Z")

G_MOON = ("M524-40q-84 0-157.5-32t-128-86.5Q184-213 152-286.5T120-444q0-128 72-232t193-146q22-8 41 5.5t18 36.5"
          "q-3 85 27 162t90 137q60 60 137 90t162 27q26-1 38.5 17.5T903-305q-44 120-147.5 192.5T524-40Z")

# The big low cloud (used for cloud-only scenes: partly/broken/overcast).
G_CLOUD = ("M260-160q-91 0-155.5-63T40-377q0-78 47-139t123-78q25-92 100-149t170-57q117 0 198.5 81.5T760-520"
           "q69 8 114.5 59.5T920-340q0 75-52.5 127.5T740-160H260Z")

# Google's standard "precip cloud" (higher, leaves room below for streaks) - the exact cloud used
# inside rainy/thunderstorm/etc., so the rainy streak capsule lines up beneath it.
G_RAINCLOUD = ("M300-320q-91 0-155.5-64.5T80-540q0-83 55-145t136-73q32-57 87.5-89.5T480-880q90 0 156.5 57.5T717-679"
               "q69 6 116 57t47 122q0 75-52.5 127.5T700-320H300Z")

# Authentic rain-streak glyphs (used whole, tinted; NOT reconstructed):
#   RAINY_MED = the 3 streaks from `rainy` (native, already sit under the rain-cloud)
#   RAINY_LIGHT = `rainy_light` (8 fine streaks)  RAINY_HEAVY = `rainy_heavy` (4 long streaks)
RAINY_MED = ("M558-84q-15 8-30.5 2.5T504-102l-60-120q-8-15-2.5-30.5T462-276q15-8 30.5-2.5T516-258l60 120"
             "q8 15 2.5 30.5T558-84Zm240 0q-15 8-30.5 2.5T744-102l-60-120q-8-15-2.5-30.5T702-276q15-8 30.5-2.5"
             "T756-258l60 120q8 15 2.5 30.5T798-84Zm-480 0q-15 8-30.5 2.5T264-102l-60-120q-8-15-2.5-30.5T222-276"
             "q15-8 30.5-2.5T276-258l60 120q8 15 2.5 30.5T318-84Z")
RAINY_LIGHT = ("M198-484q-15 8-30.5 2.5T144-502L44-702q-8-15-2.5-30.5T62-756q15-8 30.5-2.5T116-738l100 200"
               "q8 15 2.5 30.5T198-484Zm140 280q-15 8-30.5 2.5T284-222l-80-160q-8-15-2.5-30.5T222-436"
               "q15-8 30.5-2.5T276-418l80 160q8 15 2.5 30.5T338-204Zm82-200q-15 8-30.5 2.5T366-422L226-702"
               "q-8-15-2.5-30.5T244-756q15-8 30.5-2.5T298-738l140 280q8 15 2.5 30.5T420-404Zm86-200"
               "q-15 8-30.5 2.5T452-622l-39-80q-8-15-2.5-30.5T431-756q15-8 30-2.5t23 20.5l40 80q8 15 2.5 30.5T506-604Z"
               "m24 399q-15 8-30.5 3T476-222l-40-80q-8-15-2.5-30.5T454-356q15-8 30.5-2.5T508-338l40 80"
               "q8 15 2.5 30T530-205Zm186 0q-15 8-30.5 3T662-222L522-502q-8-15-2.5-30.5T540-556q15-8 30.5-2.5"
               "T594-538l140 280q8 15 2.5 30T716-205Zm62-239q-15 8-30.5 2.5T724-462L604-702q-8-15-2.5-30.5"
               "T622-756q15-8 30.5-2.5T676-738l120 240q8 15 2.5 30.5T778-444Zm120 240q-15 8-30.5 2.5T844-222"
               "l-60-120q-8-15-2.5-30.5T802-396q15-8 30.5-2.5T856-378l60 120q8 15 2.5 30.5T898-204Z")
RAINY_HEAVY = ("M338-204q-15 8-30.5 2.5T284-222L44-702q-8-15-2.5-30.5T62-756q15-8 30.5-2.5T116-738l240 480"
               "q8 15 2.5 30.5T338-204Zm187 0q-15 8-30.5 2.5T471-222L231-702q-8-15-2.5-30.5T249-756"
               "q15-8 30-2.5t23 20.5l241 480q8 15 2.5 30.5T525-204Zm187-1q-15 8-30.5 3T658-222L418-702"
               "q-8-15-2.5-30.5T436-756q15-8 30-2.5t23 20.5l241 480q8 15 2.5 30T712-205Zm186 1q-15 8-30.5 2.5"
               "T844-222L604-702q-8-15-2.5-30.5T622-756q15-8 30.5-2.5T676-738l240 480q8 15 2.5 30.5T898-204Z")

# `grain` glyph (cluster of small dots) - snow grains / hail / blowing dust.
G_GRAIN = ("M240-160q-33 0-56.5-23.5T160-240q0-33 23.5-56.5T240-320q33 0 56.5 23.5T320-240q0 33-23.5 56.5T240-160Z"
           "m320 0q-33 0-56.5-23.5T480-240q0-33 23.5-56.5T560-320q33 0 56.5 23.5T640-240q0 33-23.5 56.5T560-160Z"
           "M400-320q-33 0-56.5-23.5T320-400q0-33 23.5-56.5T400-480q33 0 56.5 23.5T480-400q0 33-23.5 56.5T400-320Z"
           "m320 0q-33 0-56.5-23.5T640-400q0-33 23.5-56.5T720-480q33 0 56.5 23.5T800-400q0 33-23.5 56.5T720-320Z"
           "M240-480q-33 0-56.5-23.5T160-560q0-33 23.5-56.5T240-640q33 0 56.5 23.5T320-560q0 33-23.5 56.5T240-480Z"
           "m320 0q-33 0-56.5-23.5T480-560q0-33 23.5-56.5T560-640q33 0 56.5 23.5T640-560q0 33-23.5 56.5T560-480Z"
           "M400-640q-33 0-56.5-23.5T320-720q0-33 23.5-56.5T400-800q33 0 56.5 23.5T480-720q0 33-23.5 56.5T400-640Z"
           "m320 0q-33 0-56.5-23.5T640-720q0-33 23.5-56.5T720-800q33 0 56.5 23.5T800-720q0 33-23.5 56.5T720-640Z")

# `question_mark` glyph - for the unknown state.
G_QUESTION = ("M584-637q0-43-28.5-69T480-732q-29 0-52.5 12.5T387-683q-16 23-43.5 26.5T296-671q-14-13-15.5-32"
              "t9.5-36q32-48 81.5-74.5T480-840q97 0 157.5 55T698-641q0 45-19 81t-70 85q-37 35-50 54.5T542-376"
              "q-4 24-20.5 40T482-320q-23 0-39.5-15.5T426-374q0-39 17-71.5t57-68.5q51-45 67.5-69.5T584-637Z"
              "M480-80q-33 0-56.5-23.5T400-160q0-33 23.5-56.5T480-240q33 0 56.5 23.5T560-160q0 33-23.5 56.5T480-80Z")

# Six-point `snowflake` (native center ~ (480,-480)) - the snow accent under clouds.
G_SNOWFLAKE = ("m440-411-132 76-20 110q-3 17-16 26.5t-30 6.5q-17-3-26-16.5t-6-30.5l8-43-30 17q-14 8-30 3.5T134-280"
               "q-8-14-4-30.5t18-24.5l30-17-42-15q-16-5-23-20t-1-31q5-16 20-22.5t31-1.5l105 38 132-76-132-76-105 38"
               "q-16 5-30.5-1.5T112-542q-6-16 1-31.5t23-20.5l42-14-30-17q-14-8-18-24.5t4-30.5q8-14 24-18.5t30 3.5l30 17"
               "-8-43q-3-17 6-30.5t26-16.5q17-3 30 6.5t16 26.5l20 110 132 76v-153l-85-72q-13-11-14.5-27t9.5-29"
               "q11-13 27-14.5t29 9.5l34 29v-34q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v34l34-29q13-11 29-9.5"
               "t27 14.5q11 13 9.5 29T605-774l-85 72v153l132-76 20-110q3-17 16-26.5t30-6.5q17 3 26 16.5t6 30.5l-8 43"
               "l30-17q14-8 30-3.5t24 18.5q8 14 4 30.5T812-625l-30 17 42 15q16 5 23 20t1 31q-5 16-20 22.5t-31 1.5"
               "l-105-38-132 76 132 76 105-38q16-5 30.5 1.5T848-418q6 16-1 31.5T824-366l-42 14 30 17q14 8 18 24.5"
               "t-4 30.5q-8 14-24 18.5t-30-3.5l-30-17 8 43q3 17-6 30.5T718-192q-17 3-30-6.5T672-225l-20-110-132-76v153"
               "l85 72q13 11 14.5 27t-9.5 29q-11 13-27 14.5t-29-9.5l-34-29v34q0 17-11.5 28.5T480-80q-17 0-28.5-11.5"
               "T440-120v-34l-34 29q-13 11-29 9.5T350-130q-11-13-9.5-29t14.5-27l85-72v-153Z")

# Rising heat-shimmer (`heat`) - used for smoke.
G_HEAT = ("M532-131q-6 5-12.5 8t-14.5 3q-8 0-16-3.5t-14-9.5q-41-44-60.5-90T395-320q0-37 11-78t38-106"
          "q23-57 32-87.5t9-56.5q0-34-15-63.5T423-771q-6-6-9.5-14t-3.5-16q0-8 3-14.5t8-12.5q6-6 13.5-9t15.5-3"
          "q8 0 15 3t13 8q44 41 65.5 86t21.5 95q0 35-10.5 73.5T518-474q-25 60-34 92t-9 61q0 35 14.5 67.5T534-188"
          "q5 6 8 13t3 15q0 8-3 15.5T532-131Zm195 0q-6 5-12.5 8t-14.5 3q-8 0-16-3.5t-14-9.5q-41-44-60.5-89.5T590-319"
          "q0-37 11-79t38-106q23-57 32-87t9-56q0-34-15-64.5T618-771q-6-6-9-13.5t-3-15.5q0-8 2.5-14.5T616-827"
          "q6-6 14-9.5t16-3.5q8 0 14.5 3t12.5 8q44 41 65.5 86t21.5 95q0 35-10.5 73.5T713-473q-25 60-34 92t-9 60"
          "q0 35 15 68.5t45 65.5q5 6 7.5 13t2.5 14q0 8-3 16t-10 13Zm-390 0q-6 5-12.5 8t-14.5 3q-8 0-16-3.5t-14-9.5"
          "q-41-44-60.5-89.5T200-319q0-37 11-79t38-106q23-57 32-87t9-56q0-34-15-64.5T228-771q-7-6-10-13.5t-3-15.5"
          "q0-8 3-15t8-13q6-6 13.5-9t15.5-3q8 0 15 3t13 8q44 41 65.5 85.5T370-648q0 35-10 73.5T324-474"
          "q-25 60-34 92t-9 61q0 35 14.5 68.5T340-187q5 6 7.5 13t2.5 14q0 8-3 16t-10 13Z")

G_BOLT = ("M360-360H236q-24 0-35.5-21.5T203-423l299-430q10-14 26-19.5t33 .5q17 6 25 21t6 32l-32 259h155"
          "q26 0 36.5 23t-6.5 43L416-100q-11 13-27 17t-31-3q-15-7-23.5-21.5T328-139l32-221Z")

G_AIR = ("M460-160q-32 0-59.5-16T356-220q-11-20-.5-40t32.5-20q13 0 23 8t18 18q5 7 13.5 10.5T460-240"
         "q17 0 28.5-11.5T500-280q0-17-11.5-28.5T460-320H120q-17 0-28.5-11.5T80-360q0-17 11.5-28.5T120-400h340"
         "q50 0 85 35t35 85q0 50-35 85t-85 35ZM120-560q-17 0-28.5-11.5T80-600q0-17 11.5-28.5T120-640h500"
         "q25 0 42.5-17.5T680-700q0-25-17.5-42.5T620-760q-16 0-30 7.5T568-731q-7 12-17 21.5t-24 9.5"
         "q-20 0-32.5-15t-6.5-32q14-42 50.5-67.5T620-840q58 0 99 41t41 99q0 58-41 99t-99 41H120Z"
         "m678 308q-20 9-39-2.5T740-288q0-14 9.5-23.5T771-328q14-8 21.5-22t7.5-30q0-25-17.5-42.5T740-440H120"
         "q-17 0-28.5-11.5T80-480q0-17 11.5-28.5T120-520h620q58 0 99 41t41 99q0 42-22 76.5T798-252Z")

G_MIST = ("M160-200q-17 0-28.5-11.5T120-240q0-17 11.5-28.5T160-280h400q17 0 28.5 11.5T600-240q0 17-11.5 28.5T560-200H160Z"
          "m560 0q-17 0-28.5-11.5T680-240q0-17 11.5-28.5T720-280h80q17 0 28.5 11.5T840-240q0 17-11.5 28.5T800-200h-80Z"
          "M160-360q-17 0-28.5-11.5T120-400q0-17 11.5-28.5T160-440h80q17 0 28.5 11.5T280-400q0 17-11.5 28.5T240-360h-80Z"
          "m240 0q-17 0-28.5-11.5T360-400q0-17 11.5-28.5T400-440h400q17 0 28.5 11.5T840-400q0 17-11.5 28.5T800-360H400Z"
          "M160-520q-17 0-28.5-11.5T120-560q0-17 11.5-28.5T160-600h440q17 0 28.5 11.5T640-560q0 17-11.5 28.5T600-520H160Z"
          "m600 0q-17 0-28.5-11.5T720-560q0-17 11.5-28.5T760-600h40q17 0 28.5 11.5T840-560q0 17-11.5 28.5T800-520h-40Z"
          "M160-680q-17 0-28.5-11.5T120-720q0-17 11.5-28.5T160-760h200q17 0 28.5 11.5T400-720q0 17-11.5 28.5T360-680H160Z"
          "m360 0q-17 0-28.5-11.5T480-720q0-17 11.5-28.5T520-760h280q17 0 28.5 11.5T840-720q0 17-11.5 28.5T800-680H520Z")

G_FOGGY = ("M720-200q-17 0-28.5-11.5T680-240q0-17 11.5-28.5T720-280q17 0 28.5 11.5T760-240q0 17-11.5 28.5T720-200Z"
           "M280-80q-17 0-28.5-11.5T240-120q0-17 11.5-28.5T280-160q17 0 28.5 11.5T320-120q0 17-11.5 28.5T280-80Z"
           "m-40-120q-17 0-28.5-11.5T200-240q0-17 11.5-28.5T240-280h360q17 0 28.5 11.5T640-240q0 17-11.5 28.5T600-200H240Z"
           "M400-80q-17 0-28.5-11.5T360-120q0-17 11.5-28.5T400-160h280q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H400Z"
           "M300-320q-91 0-155.5-64.5T80-540q0-83 55-145t136-73q32-57 87.5-89.5T480-880q90 0 156.5 57.5T717-679"
           "q69 6 116 57t47 122q0 75-52.5 127.5T700-320H300Z")

G_TORNADO = ("m110-720 46 80h648l46-80q23-40 .5-80T781-840H179q-47 0-69.5 40t.5 80Z"
             "m92 160 70 120h416l70-120H202Zm116 200 93 160q23 40 69 40t69-40l93-160H318Z")

G_CYCLONE = ("M480-320q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47Z"
             "m0-80q33 0 56.5-23.5T560-480q0-33-23.5-56.5T480-560q-33 0-56.5 23.5T400-480q0 33 23.5 56.5T480-400Z"
             "m0 240q-114 0-203.5-13.5T125-202q-20-5-32.5-19.5T80-256q0-16 13.5-25.5T123-286q42 11 74.5 17.5L250-258"
             "q-42-43-66-100t-24-122q0-114 13.5-203.5T202-835q5-20 19.5-32.5T256-880q16 0 25.5 13.5T286-837"
             "q-12 42-18.5 74.5T258-710q43-42 100-66t122-24q114 0 203.5 13.5T835-758q20 5 32.5 19.5T880-704"
             "q0 16-13.5 25.5T837-674q-42-12-74.5-18.5T710-702q42 43 66 100t24 122q0 114-13.5 203.5T758-125"
             "q-5 20-19.5 32.5T704-80q-16 0-25.5-13.5T674-123q11-42 17.5-74.5L702-250q-43 42-100 66t-122 24Z")

G_VOLCANO = ("M774-80H203q-44 0-67.5-36t-5.5-76l89-200q10-22 29.5-35t43.5-13h68l60-150q9-23 29-36.5t45-13.5h166"
             "q27 0 48 16t29 42l114 400q11 38-13 70t-64 32Z"
             "M520-800v-80q0-17 11.5-28.5T560-920q17 0 28.5 11.5T600-880v80q0 17-11.5 28.5T560-760q-17 0-28.5-11.5T520-800Z"
             "m153 31 57-57q11-11 27.5-11t28.5 11q12 12 12 28t-12 28l-57 57q-11 11-27.5 11.5T673-713q-11-11-11-28t11-28Z"
             "m-282 56-57-57q-11-11-11-27.5t11-28.5q12-12 28-12t28 12l57 57q11 11 11.5 27.5T447-713q-11 11-28 11t-28-11Z")


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def place(glyph, s, cx, cy, fill, ox=480.0, oy=-480.0):
    """Scale a native glyph so its native center (ox,oy) lands on output (cx,cy)."""
    tx, ty = cx - ox * s, cy - oy * s
    return (f'  <group android:scaleX="{f(s)}" android:scaleY="{f(s)}" '
            f'android:translateX="{f(tx)}" android:translateY="{f(ty)}">\n'
            f'    <path android:pathData="{glyph}" android:fillColor="{fill}" />\n'
            f'  </group>')


def raincloud(fill=CLOUD):
    return place(G_RAINCLOUD, 1.0, 480, 480, fill)


# Whole authentic rain glyphs, tinted + positioned so their streaks hang below the cloud.
def rain_light():
    return place(RAINY_LIGHT, 0.46, 480, 800, RAIN)


def rain_med():
    return place(RAINY_MED, 1.0, 480, 480, RAIN)


def rain_heavy():
    return place(RAINY_HEAVY, 0.5, 480, 790, RAIN)


def grain(cx, cy, s, fill):
    return place(G_GRAIN, s, cx, cy, fill)


def spread(count, span=170, cx=470):
    if count == 1:
        return [cx]
    return [cx - span + i * (2 * span / (count - 1)) for i in range(count)]


def circ(cx, cy, r):
    return (f"M{f(cx - r)},{f(cy)} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 "
            f"a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0 Z")


def dots(pts, r, fill):
    d = "".join(circ(x, y, r) for (x, y) in pts)
    return f'  <path android:fillColor="{fill}" android:pathData="{d}" />'


def flakes(pts, s=0.19, fill=SNOW):
    """Stamp the `snowflake` glyph at each (x,y) - the snow accent under a cloud."""
    return "\n".join(place(G_SNOWFLAKE, s, x, y, fill) for (x, y) in pts)


def hlines(ys, x1, x2, col, w):
    d = "".join(f"M{f(x1)},{f(y)} H{f(x2)} " for y in ys)
    return (f'  <path android:strokeColor="{col}" android:strokeWidth="{f(w)}" '
            f'android:strokeLineCap="round" android:pathData="{d.strip()}" />')


def bolt(cx=470, cy=650, s=0.42, fill=BOLT):
    return place(G_BOLT, s, cx, cy, fill)


def lum(night, cx, cy, s):
    return place(G_MOON, s, cx, cy, MOON) if night else place(G_SUN, s, cx, cy, SUN)


# ------------------------------------------------------------------ builders (return list of elements)
def b_clear(night):
    return [lum(night, 480, 480, 0.95)]


def b_mainly_clear(night):
    return [lum(night, 380, 380, 0.5), place(G_CLOUD, 0.58, 600, 600, CLOUD)]


def b_partly_cloudy(night):
    return [lum(night, 355, 355, 0.52), place(G_CLOUD, 0.74, 560, 560, CLOUD)]


def b_broken(night):
    return [place(G_CLOUD, 0.5, 360, 380, CLOUD_LIGHT), place(G_CLOUD, 0.78, 560, 560, CLOUD)]


def b_overcast(night):
    return [place(G_CLOUD, 0.6, 380, 400, CLOUD_LIGHT), place(G_CLOUD, 0.92, 500, 520, CLOUD_DARK)]


# --- rain / drizzle / showers -------------------------------------------------
# Real rain glyphs under the cloud (precip drawn first so the cloud caps the streak tops).
def b_rain_light(night):
    return [rain_light(), raincloud()]


def b_rain_med(night):
    return [raincloud(), rain_med()]


def b_rain_heavy(night):
    return [rain_heavy(), raincloud()]


# Freezing rain/drizzle: cloud + light rain + an icy snowflake.
def b_freezing_rain(night):
    return [rain_light(), raincloud(), flakes([(480, 900)], 0.18)]


# --- snow ---------------------------------------------------------------------
def b_snow(count):
    def build(night):
        xs = spread(count, span=175)
        pts = [(x, 720 if i % 2 == 0 else 810) for i, x in enumerate(xs)]
        s = 0.2 if count <= 2 else 0.17
        return [raincloud(), flakes(pts, s)]
    return build


def b_snow_grains(night):
    # Grains = fine particles: the `grain` glyph (a cluster of dots) under the cloud.
    return [raincloud(), grain(480, 780, 0.34, SNOW)]


def b_sleet(night):
    return [rain_light(), raincloud(), flakes([(560, 830)], 0.19)]


def b_rain_snow(light=False):
    def build(night):
        return [rain_light() if light else rain_med(), raincloud(), flakes([(575, 810)], 0.2)]
    return build


# --- thunderstorm -------------------------------------------------------------
def b_thunder(rain=None, heavy=False):
    def build(night):
        elems = []
        if rain == "light":
            elems.append(rain_light())
        elif rain == "heavy":
            elems.append(rain_heavy())
        elems.append(raincloud(CLOUD_DARK))
        if heavy:
            elems.append(bolt(400, 640, 0.4))
            elems.append(bolt(600, 700, 0.34))
        else:
            elems.append(bolt(470, 660, 0.42))
        return elems
    return build


def b_thunder_hail(night):
    return [raincloud(CLOUD_DARK), bolt(470, 630, 0.4), grain(470, 850, 0.3, HAIL)]


# --- atmosphere ---------------------------------------------------------------
def b_fog(night):
    return [place(G_FOGGY, 0.92, 480, 480, FOG)]


def b_rime_fog(night):
    # Freezing fog: snow + fog stacked (snowflakes over the fog bank).
    return [place(G_FOGGY, 0.92, 480, 520, FOG),
            flakes([(330, 250), (600, 230)], 0.17)]


def b_mist(night):
    return [place(G_MIST, 0.92, 480, 480, CLOUD_LIGHT)]


def b_haze(night):
    return [lum(night, 380, 350, 0.48), hlines([620, 720, 820], 180, 780, FOG, 64)]


def b_smoke(night):
    return [place(G_HEAT, 0.9, 480, 480, ASH)]


def b_squall(night):
    return [place(G_AIR, 0.88, 480, 480, CLOUD)]


def b_sand(night):
    return [place(G_MIST, 0.92, 480, 480, SAND)]


def b_dust(night):
    return [place(G_MIST, 0.86, 480, 450, SAND), grain(560, 780, 0.24, SAND)]


def b_dust_whirls(night):
    return [place(G_AIR, 0.85, 480, 480, SAND)]


def b_tornado(night):
    return [place(G_TORNADO, 0.85, 480, 480, CLOUD)]


def b_volcanic_ash(night):
    # Volcano + black "mist" smoke plume on top (no separate ash dots).
    return [place(G_VOLCANO, 0.9, 480, 520, LAVA),
            place(G_MIST, 0.5, 480, 250, ASH_DARK)]


def b_unknown(night):
    return [place(G_CLOUD, 0.9, 480, 470, CLOUD), place(G_QUESTION, 0.4, 480, 470, "#FFFFFF")]


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

    "light_drizzle": b_rain_light,
    "drizzle": b_rain_light,
    "heavy_drizzle": b_rain_light,
    "light_drizzle_rain": b_rain_light,
    "drizzle_rain": b_rain_light,
    "heavy_drizzle_rain": b_rain_med,
    "shower_drizzle": b_rain_light,
    "freezing_drizzle": b_freezing_rain,

    "light_rain": b_rain_light,
    "moderate_rain": b_rain_med,
    "heavy_rain": b_rain_heavy,
    "very_heavy_rain": b_rain_heavy,
    "extreme_rain": b_rain_heavy,
    "freezing_rain": b_freezing_rain,
    "light_shower_rain": b_rain_light,
    "shower_rain": b_rain_med,
    "heavy_shower_rain": b_rain_heavy,
    "ragged_shower_rain": b_rain_med,

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

    "thunderstorm_light_rain": b_thunder(rain="light"),
    "thunderstorm_rain": b_thunder(rain="light"),
    "thunderstorm_heavy_rain": b_thunder(rain="heavy", heavy=True),
    "light_thunderstorm": b_thunder(),
    "thunderstorm": b_thunder(),
    "heavy_thunderstorm": b_thunder(rain="heavy", heavy=True),
    "ragged_thunderstorm": b_thunder(),
    "thunderstorm_light_drizzle": b_thunder(rain="light"),
    "thunderstorm_drizzle": b_thunder(rain="light"),
    "thunderstorm_heavy_drizzle": b_thunder(rain="light"),
    "thunderstorm_hail": b_thunder_hail,

    "unknown": b_unknown,
}


def vector(elements):
    body = "\n".join(elements)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- AUTO-GENERATED by tools/gen_ms_weather.py (Material Symbols, multi-tone). -->\n'
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
            # Night: keep the art identical, just tuck a subtle crescent moon behind the cloud
            # (or a top corner when there's no cloud). Sky scenes already swap sun->moon.
            if night and key not in SKY:
                if key in NO_CLOUD:
                    elems = elems + [place(G_MOON, 0.3, 810, 165, MOON)]
                else:
                    elems = [place(G_MOON, 0.28, 240, 210, MOON)] + elems
            xml = vector(elems)
            name = f"{PREFIX}_{key}_{'night' if night else 'day'}.xml"
            with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as fh:
                fh.write(xml)
            count += 1
    print(f"Wrote {count} prototype drawables ({PREFIX}_*) to {OUT_DIR}")


if __name__ == "__main__":
    main()
