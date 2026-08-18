# At a Glance

A minimalist home‑screen **widget** for Android that puts your day at a glance: a clean clock and date, live weather, your next alarm, a running‑timer countdown, and upcoming Proton Calendar events — all left‑aligned and readable straight on your wallpaper.

Built with Jetpack **Glance** (widget) and **Compose** (settings), with Material You dynamic theming.

> Independent app — not affiliated with Proton AG. Proton sign‑in is handled securely by the official Proton Core SDK.

---

## Features

### Clock & date
- Self‑updating clock and date header that roll over without needing a refresh.
- The clock follows the **timezone of your geocoded weather location**, so it shows local time for where the widget says you are (falls back to the device timezone).

### Weather
- Current temperature, condition text, and a **day/night icon** that flips at the actual sunrise/sunset for your location.
- Choose your data source: **OpenWeather** or **Open‑Meteo**.
- Location is resolved from GPS and reverse‑geocoded to *locality, region, country*.

### Location line (marquee)
- Shows `Locality` on line one and `Region, Country` on line two.
- If the region is too long to fit, it **scrolls smoothly** (monospace, ghost‑free) while the **country code stays pinned** on the right. Short regions render statically — no needless motion.

### Alarms
- Displays your **next scheduled alarm**.

### Live system timer
- Mirrors a running **Google Clock** timer as a **live countdown** right on the widget.
- When the timer ends, the widget shows the **expired/overtime** state with **Restart** (↻) and **Dismiss** (×) controls.
  - **Restart** re‑arms a new timer with the original duration.
  - **Dismiss** clears it from the widget.
- Uses an exact alarm so the ended state appears on time even if the app is backgrounded.
- Requires a one‑time **Notification access** grant (see [Enabling the timer](#enabling-the-timer-notification-access)).

### Calendar events
- Shows your **upcoming Proton Calendar events**, **auto‑cycling every 4 seconds** with smooth transitions.
- **Arrow buttons** (‹ ›) step through events manually; tapping an arrow **resets the 4‑second timer** so the event you picked stays put.
- Handles **recurring** and **multi‑day** events, showing each instance separately.
- Pick how far ahead to look: **7, 14, or 28 days**.

### Tap targets
Every part of the widget is a shortcut:

| Tap… | Opens… |
| --- | --- |
| Date header | Your calendar (device default or Proton) |
| Clock / location | Maps for your location |
| Weather line | A web weather search for your location |
| Alarm line | The Clock app's alarms |
| Running timer | The Clock app's timers |
| An event | That event in your calendar |
| Empty widget space | The **At a Glance** app |

### Appearance
- **Frameless** (text directly on your wallpaper) or a **themed surface** background with adjustable **opacity**.
- **Material You** dynamic colors on Android 12+.

---

## Install

1. Go to the [**Releases**](../../releases) page and download the latest `at-a-glance-vX.Y.Z.apk`.
2. Open the APK on your phone. If prompted, allow your browser/file manager to **install unknown apps**.
3. Tap **Install**.

**Requirements:** Android **8.0+ (API 26)**. The release APK is a universal build (arm64 / arm / x86_64).

> The published APK is signed with a debug key for easy sideloading (it is not a Play Store upload build).

---

## Setup & usage

### 1) Add the widget
- Long‑press your home screen → **Widgets** → find **At a Glance** → drag it to a home screen.
- Resize as you like; the layout centers vertically and scales its sections to fit.

### 2) Grant permissions
On first launch the app asks for:
- **Location** — used only to fetch weather for where you are.
- **Calendar** — to read calendar events.

Allow these for the widget to show weather and events.

### 3) Connect Proton Calendar (optional)
- Open the app and tap **Connect Proton Calendar**.
- Sign in through Proton's secure login flow.
- Once connected, your events populate the widget. Use **Show events for the next** to choose a 7 / 14 / 28‑day window. Tap **Disconnect** anytime.

### 4) Enabling the timer (Notification access)
The system exposes no public API for Clock countdowns, so the widget reads the Clock app's ongoing timer notification.

- In the app, find **Show running timers** and tap **Enable**.
- Toggle **At a Glance** (FrontWidget Timer) **on** in the system's Notification access screen, then return to the app — it will show **Enabled** automatically.
- Start a timer in **Google Clock**; the widget shows the live countdown.

---

## Settings reference

| Setting | What it does |
| --- | --- |
| **Connect / Disconnect** | Link or unlink your Proton account. |
| **Show events for the next** | Event look‑ahead window: 7, 14, or 28 days. |
| **Weather source** | OpenWeather or Open‑Meteo. |
| **Widget background** | Frameless vs. themed surface, with an opacity slider. |
| **Open calendar & events in** | Route calendar/event taps to your **device default** or **Proton Calendar**. |
| **Show running timers** | Grant/inspect Notification access for the live timer. |
| **About** | Support the project, view source, and open‑source attributions. |

---

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Fetch weather. |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | Locate you for weather + local time. |
| `READ_CALENDAR` | Read calendar events. |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Flip the widget to the "timer ended" state exactly on time. |
| `SET_ALARM` | Start/restart a system timer from the widget. |
| `RECEIVE_BOOT_COMPLETED` | Restore the widget's schedule after a reboot. |
| **Notification access** (optional) | Read the Clock's running‑timer notification to show the countdown. |

---

## Building from source

**Prerequisites:** JDK **17**, Android SDK **35**, and the Android SDK path in `local.properties` (`sdk.dir=...`).

```bash
# Debug build (installs on phones + emulator: arm64-v8a + x86_64)
./gradlew assembleDebug

# Release build (minified, universal ABIs)
./gradlew assembleRelease
```

Outputs land in `app/build/outputs/apk/`.

- Debug builds bundle only `arm64-v8a` and `x86_64` to stay small; **release** is universal.
- Release is minified/shrunk with R8 and (by default) signed with the debug key for sideloading. For a distributable build, configure your own release keystore.
- The OpenWeather API key currently lives in `app/src/main/java/com/saveory/frontwidget/data/Constants.kt`. If you fork/publish, move it into `local.properties` → `BuildConfig` and rotate the key.

---

## Tech stack

- **Kotlin**, **Jetpack Compose** (settings UI), **Jetpack Glance** (app widget)
- **WorkManager** for weather/event refresh, **Hilt** for DI
- **Retrofit / OkHttp / Gson** for networking
- **Proton Core Android SDK** `34.3.0` (account, auth, calendar)
- **Play Services Location** + `Geocoder` for location/timezone
- **NotificationListenerService** for the live timer
- AGP 8.5.2 · Kotlin 2.0.21 · Gradle 9.3 · compileSdk 35 · minSdk 26

---

## Privacy

- Location is used solely to fetch weather and derive local time; it is not shared with third parties beyond the weather provider you choose.
- Proton authentication and calendar access are handled by the official Proton Core SDK; your credentials are never seen by this app.

---

## License

Licensed under the **GNU General Public License v3.0 (GPLv3)** — see [`LICENSE`](LICENSE). At a Glance links the GPLv3 Proton Core Android SDK; the complete corresponding source is this repository.

---

## Support

If you find this useful, you can support development: **[Buy me a coffee](https://buymeacoffee.com/alxcodes)** ☕
