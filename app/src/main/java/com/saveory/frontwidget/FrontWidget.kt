package com.saveory.frontwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.os.Build
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.DpSize
import com.saveory.frontwidget.data.AlarmRepository
import com.saveory.frontwidget.data.WeeklySpend
import com.saveory.frontwidget.data.WeeklySpendRepository
import com.saveory.frontwidget.data.TemperatureUnit
import com.saveory.frontwidget.data.WeatherStatus
import com.saveory.frontwidget.proton.calendar.ProtonEvent
import com.saveory.frontwidget.proton.calendar.ProtonEventStore
import java.text.SimpleDateFormat
import java.util.*

class FrontWidget : GlanceAppWidget() {

    companion object {
        private val SMALL = DpSize(120.dp, 120.dp)
        private val MEDIUM = DpSize(250.dp, 120.dp)
        private val LARGE = DpSize(250.dp, 250.dp)

        // Uniform enlargement applied to the calendar (date header), alarm and events sections only
        // (not the clock or weather). Applied as a single multiplier to every text size in those
        // sections so the relative proportions between them stay exactly as designed - bump this one
        // value to scale all three up/down together.
        private const val SECTION_SCALE = 1.30f

        // Per-frame pixel cap for the animated wavy-ring flipper. 8 frames at this size stay well
        // under the RemoteViews bitmap budget (8 * 104^2 * 4 bytes ~= 0.35 MB); the ImageView upscales
        // each capped frame to the ring's display size (52dp).
        private const val FLIPPER_FRAME_MAX_PX = 104

        // Compact restart/dismiss controls: they stack vertically (refresh over X) next to the alarm
        // line, so each button box + glyph is small and a tiny gap separates them. Two boxes + gap is
        // the stack's height, used to size the alarm row so the pair never clips.
        private const val TIMER_BTN_BOX_DP = 20f
        private const val TIMER_BTN_ICON_DP = 14f
        private const val TIMER_BTN_GAP_DP = 2f

        // User-controlled widget container: whether the themed surface is drawn at all, and (when on)
        // how opaque it is (0..100). Persisted in "widget_prefs"; the settings screen writes them and
        // forceRefresh()es. Off = frameless (text straight on the wallpaper) like the original look.
        const val KEY_BG_ENABLED = "bg_enabled"
        const val KEY_BG_OPACITY = "bg_opacity"
        const val DEFAULT_BG_ENABLED = true
        const val DEFAULT_BG_OPACITY = 100

        // Whether the weekly-spend (Monarch) tracker is shown on the widget at all. The settings
        // screen exposes this as a show/hide toggle; off removes the ring from the face entirely.
        const val KEY_SPEND_ENABLED = "spend_enabled"
        const val DEFAULT_SPEND_ENABLED = true

        // Which app calendar/event taps open. "device" = the device's default calendar app (honoring
        // the user's chosen default, e.g. Google/Samsung Calendar); "proton" = the Proton Calendar
        // app/deep link (the integration we ship). Only the calendar links are Proton-aware; maps,
        // timers, alarms and weather always resolve to the device default app.
        const val KEY_CALENDAR_TARGET = "calendar_target"
        const val CALENDAR_TARGET_DEVICE = "device"
        const val CALENDAR_TARGET_PROTON = "proton"
        const val DEFAULT_CALENDAR_TARGET = CALENDAR_TARGET_DEVICE

        /**
         * The currently shown event index, stored in the widget's own Glance state (not plain
         * SharedPreferences). Reading it via currentState() inside the composition subscribes the
         * widget to changes, so an arrow tap (which writes it via updateAppWidgetState) reliably
         * triggers a recomposition — plain updateAll() calls get coalesced and silently drop the
         * update, which is what made the arrows feel stuck.
         */
        val EVENTS_INDEX_KEY = intPreferencesKey("events_index")

        /**
         * Bumped to force a guaranteed recomposition. A plain updateAll() from a receiver/service is
         * coalesced by Glance and frequently dropped (which left the timer stuck on a stale state);
         * changing this Glance-state value reliably re-runs provideGlance so it re-reads the latest
         * SharedPreferences (timer phase/finish, etc.). Use [forceRefresh] instead of updateAll for
         * out-of-composition updates that must land.
         */
        val UPDATE_NONCE_KEY = longPreferencesKey("update_nonce")

        /** Reliable widget refresh: bumps [UPDATE_NONCE_KEY] in Glance state (guaranteeing a
         *  recompose) for every widget instance, then also calls updateAll as a belt-and-suspenders. */
        suspend fun forceRefresh(context: Context) {
            val nonce = System.currentTimeMillis()
            val ids = GlanceAppWidgetManager(context).getGlanceIds(FrontWidget::class.java)
            ids.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[UPDATE_NONCE_KEY] = nonce
                }
            }
            FrontWidget().updateAll(context)
        }

        // Trailing spaces appended to each marquee copy so there's a readable gap between the end of
        // one loop and the start of the next ("...FR    Provence...") instead of the text butting
        // straight into itself.
        private const val TICKER_LOOP_GAP = "     "

        // Approx width of one location-line character as a fraction of its text size, used to size
        // the fixed marquee clip window in dp (proportional sans averages a little over half the
        // text size per glyph). Only affects how wide the visible window is, so a rough value is fine.
        private const val TICKER_CHAR_DP = 0.55f

        // The ticker only marquees when "region, COUNTRY" would actually be clipped in the space
        // line 2 has. That space tracks the locality on line 1 (the block is at least as wide as the
        // city name), so the window is the locality's char count clamped to this range: the floor
        // lets short city names still use the surrounding whitespace before scrolling, and the cap
        // stops a very long name from growing the block into the NYC clock on narrower widgets.
        private const val TICKER_MIN_WINDOW = 12
        private const val TICKER_MAX_WINDOW = 26
        // Extra chars beyond the locality width that line 2 may borrow from the whitespace to its
        // right before it's considered clipped. Keeps text that only just overflows (e.g.
        // "California, US" under "MOUNTAIN VIEW") static instead of scrolling for one stray glyph.
        private const val TICKER_WINDOW_SLACK = 3

        /**
         * Picks the timezone id the widget should render its clock/date in, based on the last
         * geocoded location. Prefers a real IANA id (DST-correct); otherwise builds a fixed-offset
         * "GMT+HH:MM" id from the stored seconds. Returns "" when nothing is known yet, which the
         * caller treats as "use the device timezone" (the correct fallback before the first fetch).
         */
        fun resolveLocationTimeZoneId(storedId: String, offsetSeconds: Int): String {
            if (storedId.isNotEmpty() && TimeZone.getTimeZone(storedId).id == storedId) return storedId
            if (offsetSeconds == Int.MIN_VALUE) return ""
            val totalMinutes = offsetSeconds / 60
            val sign = if (totalMinutes < 0) "-" else "+"
            val h = kotlin.math.abs(totalMinutes) / 60
            val m = kotlin.math.abs(totalMinutes) % 60
            return "GMT%s%02d:%02d".format(sign, h, m)
        }
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

        val protonEvents = ProtonEventStore.load(context)

        // (Re-)arm the events auto-cycle. Because this runs on every render, an arrow tap — which
        // re-renders — also resets the cycle countdown, so a manual jump gives you a fresh interval
        // before it advances on its own. The shown index is deterministic Glance state shared with
        // the arrows (see WidgetCycle / EVENTS_INDEX_KEY).
        WidgetCycle.schedule(context)

        // Arm the region scroll ticker only while ticker reveal mode is selected (no-op otherwise).
        RegionTicker.scheduleOrCancel(context)

        provideContent {
            val size = LocalSize.current
            // Subscribe to the shown-event index from Glance state: an arrow tap writes it via
            // updateAppWidgetState, which reliably recomposes this (unlike a raw updateAll).
            val storedIndex = currentState(EVENTS_INDEX_KEY) ?: 0
            val eventsIndex = if (protonEvents.isEmpty()) 0
                else ((storedIndex % protonEvents.size) + protonEvents.size) % protonEvents.size
            // Subscribe to the refresh nonce so a forceRefresh() (e.g. a timer state change) reliably
            // recomposes this widget even when a plain updateAll would be coalesced/dropped.
            currentState(UPDATE_NONCE_KEY)
            // Read the next alarm HERE (not in the prelude): the prelude runs once per provideGlance,
            // but an alarm change arrives via SystemChangeReceiver's forceRefresh, whose nonce bump
            // only recomposes THIS lambda. Reading it here (not in the prelude) is what makes a newly
            // set/removed alarm actually show up on that recompose - previously it stayed stale.
            val nextAlarm = AlarmRepository(context).getNextAlarm()
            // Read the timer state HERE (not in the prelude): the prelude runs once per provideGlance,
            // but timer changes arrive via forceRefresh's nonce bump, which only recomposes this
            // lambda. TimerListenerService lives in the same process, so this shared SharedPreferences
            // read picks up the latest finish/total on that recompose. finish>now = counting down,
            // 0<finish<=now = ended (show refresh/dismiss), 0 = no timer. total drives "restart".
            val timerFinishMs = prefs.getLong(TimerListenerService.KEY_TIMER_FINISH, 0L)
            val timerTotalMs = prefs.getLong(TimerListenerService.KEY_TIMER_TOTAL, 0L)
            // Container background prefs (read here so a settings change + forceRefresh recomposes).
            val bgEnabled = prefs.getBoolean(KEY_BG_ENABLED, DEFAULT_BG_ENABLED)
            val bgOpacity = prefs.getInt(KEY_BG_OPACITY, DEFAULT_BG_OPACITY)
            val spendEnabled = prefs.getBoolean(KEY_SPEND_ENABLED, DEFAULT_SPEND_ENABLED)

            // Weather + location read HERE (not the prelude) so a WeatherWorker refresh — or a manual
            // location change — reflects on the next recompose, instead of being frozen at the value
            // captured when the Glance session first started.
            val tempCelsius = prefs.getFloat("weather_temp_c", Float.NaN)
            val weatherTemp = if (tempCelsius.isNaN()) "--°" else TemperatureUnit.format(tempCelsius)
            val weatherCond = prefs.getString("weather_cond", "Weather") ?: "Weather"
            val weatherLocality = prefs.getString("weather_locality", "Location") ?: "Location"
            val weatherRegion = prefs.getString("weather_region", "") ?: ""
            val weatherCountry = prefs.getString("weather_country", "") ?: ""
            val weatherStatus = prefs.getString("weather_status", WeatherStatus.UNKNOWN.key)
                ?: WeatherStatus.UNKNOWN.key
            // Decide day/night from the actual sun times so the icon flips at sunrise/sunset even if
            // the weather data itself is a bit stale. Fall back to the stored flag when sun times are
            // missing (e.g. data from an older build).
            val sunriseMs = prefs.getLong("weather_sunrise_ms", 0L)
            val sunsetMs = prefs.getLong("weather_sunset_ms", 0L)
            val weatherIsDay = if (sunriseMs > 0L && sunsetMs > 0L) {
                System.currentTimeMillis() in sunriseMs until sunsetMs
            } else {
                prefs.getBoolean("weather_is_day", true)
            }
            // Drive the clock/date off the geocoded LOCATION's timezone (not the device's), so the
            // hero time and NYC reference reflect where the widget says you are. Empty => device tz.
            val locationTzId = resolveLocationTimeZoneId(
                prefs.getString("location_tz_id", "") ?: "",
                prefs.getInt("location_tz_offset", Int.MIN_VALUE)
            )
            // Read the weekly spend HERE (like weather/events) and pass it down as a parameter, so a
            // sync that rewrites the prefs + forceRefreshes actually recomposes the tracker. If it
            // were read inside the composable with only the (unchanging) contentInset as input,
            // Compose would skip re-rendering it and the face would freeze on the first value.
            val weeklySpend = WeeklySpendRepository(context).getThisWeek()
            GlanceTheme {
                WidgetContent(protonEvents, eventsIndex, nextAlarm, timerFinishMs, timerTotalMs, weatherTemp, weatherCond, weatherLocality, weatherRegion, weatherCountry, weatherStatus, weatherIsDay, locationTzId, size, bgEnabled, bgOpacity, weeklySpend, spendEnabled)
            }
        }
    }

    /**
     * Builds a [RemoteViews] wrapping a self-updating TextClock. The OS ticks it every minute
     * (it has no seconds) using [timeZoneId] (null = device timezone) and picks the 12h/24h
     * pattern based on the device's clock setting, so the widget never shows a stale time.
     */
    private fun clockRemoteViews(
        context: Context,
        format12: String,
        format24: String,
        timeZoneId: String?,
        textSizeSp: Float,
        colorArgb: Int,
        layoutRes: Int = R.layout.widget_text_clock
    ): RemoteViews = RemoteViews(context.packageName, layoutRes).apply {
        setCharSequence(R.id.widget_text_clock, "setFormat12Hour", format12)
        setCharSequence(R.id.widget_text_clock, "setFormat24Hour", format24)
        if (timeZoneId != null) setString(R.id.widget_text_clock, "setTimeZone", timeZoneId)
        setTextViewTextSize(R.id.widget_text_clock, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTextColor(R.id.widget_text_clock, colorArgb)
    }

    /** A tinted icon with a comfortable (~32dp) tap target for the ended-timer controls. */
    @Composable
    private fun TimerControlButton(iconRes: Int, description: String, onClick: androidx.glance.action.Action) {
        // Compact control: the two buttons stack vertically beside the alarm line, so each is kept
        // small (see TIMER_BTN_BOX_DP / TIMER_BTN_ICON_DP) to fit the pair within one row's height.
        Box(
            modifier = GlanceModifier.size(TIMER_BTN_BOX_DP.dp).clickable(onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = description,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.size(TIMER_BTN_ICON_DP.dp)
            )
        }
    }

    /**
     * Builds the alarm line - the alarm label and (when a system timer is set) a tinted hourglass +
     * self-ticking countdown Chronometer - as ONE native RemoteViews so they all sit on the same
     * center_vertical baseline. Mixing a Glance Text (which carries font padding) with an embedded
     * Chronometer (includeFontPadding=false) previously centred them differently, leaving the timer
     * floating above the alarm; keeping both in this native line fixes that.
     *
     * [timerBaseElapsedRealtime] is the finish instant on the SystemClock.elapsedRealtime() timebase;
     * with count-down enabled the launcher renders the remaining time every second on its own, and
     * keeps ticking past the finish into a negative "-M:SS" overtime (matching Clock's own display).
     */
    private fun alarmLineRemoteViews(
        context: Context,
        alarmText: String,
        alarmColorArgb: Int,
        textSizeSp: Float,
        showTimer: Boolean,
        timerBaseElapsedRealtime: Long,
        timerColorArgb: Int
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_alarm_line).apply {
        setTextViewText(R.id.alarm_text, alarmText)
        setTextColor(R.id.alarm_text, alarmColorArgb)
        setTextViewTextSize(R.id.alarm_text, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setOnClickPendingIntent(R.id.alarm_text, alarmPendingIntent(context))
        if (showTimer) {
            // A live countdown already carries its own hourglass; hide the standalone shortcut so
            // the row never shows two hourglasses at once.
            setViewVisibility(R.id.timer_launch_icon, View.GONE)
            setViewVisibility(R.id.timer_group, View.VISIBLE)
            setInt(R.id.timer_icon, "setColorFilter", timerColorArgb)
            setChronometerCountDown(R.id.timer_chrono, true)
            setChronometer(R.id.timer_chrono, timerBaseElapsedRealtime, null, true)
            setTextColor(R.id.timer_chrono, timerColorArgb)
            setTextViewTextSize(R.id.timer_chrono, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            // Tapping the running countdown jumps to the Clock app's Timers tab.
            setOnClickPendingIntent(R.id.timer_group, timerPendingIntent(context))
        } else {
            setViewVisibility(R.id.timer_group, View.GONE)
            // No live timer: show the standalone hourglass, tinted to match the alarm label, as a
            // one-tap shortcut into the Clock app's Timers tab.
            setViewVisibility(R.id.timer_launch_icon, View.VISIBLE)
            setInt(R.id.timer_launch_icon, "setColorFilter", alarmColorArgb)
            setOnClickPendingIntent(R.id.timer_launch_icon, timerPendingIntent(context))
        }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, WidgetIntents.alarms(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun timerPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1, WidgetIntents.timers(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Builds the two-line NYC reference block (live NYC time over "NYC, US") as one RemoteViews so
     * its two lines stack with the same native spacing as the location block, instead of the looser
     * gap a Glance Column produced (an oversized clock box padding the label away).
     */
    private fun nycBlockRemoteViews(
        context: Context,
        timeSizeSp: Float,
        labelSizeSp: Float,
        timeColorArgb: Int,
        labelColorArgb: Int
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_nyc_block).apply {
        setCharSequence(R.id.nyc_clock, "setFormat12Hour", "h:mm a")
        setCharSequence(R.id.nyc_clock, "setFormat24Hour", "HH:mm")
        setString(R.id.nyc_clock, "setTimeZone", "America/New_York")
        setTextViewTextSize(R.id.nyc_clock, TypedValue.COMPLEX_UNIT_SP, timeSizeSp)
        setTextColor(R.id.nyc_clock, timeColorArgb)
        setTextViewText(R.id.nyc_label, "NYC, US")
        setTextViewTextSize(R.id.nyc_label, TypedValue.COMPLEX_UNIT_SP, labelSizeSp)
        setTextColor(R.id.nyc_label, labelColorArgb)
    }

    /**
     * Builds the two-line location block (locality over "region, COUNTRY") as one RemoteViews. The
     * locality is always shown in full; how the "region, COUNTRY" line handles a long region name
     * depends on [mode] ([RegionReveal]):
     *  - ELLIPSIS: region caps at maxWidth and ellipsizes; country code stays fully shown.
     *  - TICKER (default): a fixed-width window through which the whole "region, COUNTRY" line
     *    scrolls as a seamless marquee, but only when it would otherwise be clipped.
     *  - FLIP: a ViewFlipper that auto-alternates between the two halves of "region, COUNTRY".
     */
    private fun locationBlockRemoteViews(
        context: Context,
        locality: String,
        region: String,
        country: String,
        textSizeSp: Float,
        localityColorArgb: Int,
        regionColorArgb: Int,
        mode: String
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_location_block).apply {
        setTextViewText(R.id.loc_line1, locality)
        setTextViewTextSize(R.id.loc_line1, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTextColor(R.id.loc_line1, localityColorArgb)

        // Flip AND ticker modes treat "region, COUNTRY" as one string: the ticker scrolls the whole
        // "region, COUNTRY" (country included) through the window, rather than pinning the country.
        val fullText = region + country

        // Size + tint every second-line variant up front, then toggle which one is visible. The
        // ticker's flipper frames are styled individually as they're created below.
        for (id in intArrayOf(
            R.id.loc_region, R.id.loc_country, R.id.loc_ticker_country,
            R.id.loc_flip_a, R.id.loc_flip_b
        )) {
            setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTextColor(id, regionColorArgb)
        }

        when (mode) {
            RegionReveal.TICKER -> {
                setViewVisibility(R.id.loc_flipper, View.GONE)
                // Only marquee when the REGION alone would be clipped. The block is at least as wide
                // as the locality on line 1, so use that width (clamped) as the space line 2 gets for
                // free; a longer region scrolls through a window of that size while ", COUNTRY" stays
                // pinned and always visible to its right.
                val windowChars = (locality.length + TICKER_WINDOW_SLACK)
                    .coerceIn(TICKER_MIN_WINDOW, TICKER_MAX_WINDOW)

                if (region.length <= windowChars) {
                    // Region fits the available width: render statically like ELLIPSIS mode. Crucially
                    // we DON'T use the flipper here — a ViewFlipper re-applies its slide-in animation
                    // every interval even with a single child, which makes a short line appear to
                    // jitter/scroll. Static text views never animate.
                    setViewVisibility(R.id.loc_ticker_flipper, View.GONE)
                    setViewVisibility(R.id.loc_ticker_country, View.GONE)
                    setViewVisibility(R.id.loc_region, View.VISIBLE)
                    setViewVisibility(R.id.loc_country, View.VISIBLE)
                    setTextViewText(R.id.loc_region, region)
                    setTextViewText(R.id.loc_country, country)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // --- Marquee (API 31+): needs setViewLayoutWidth to size the window + copies. ---
                    setViewVisibility(R.id.loc_region, View.GONE)
                    setViewVisibility(R.id.loc_country, View.GONE)
                    setViewVisibility(R.id.loc_ticker_flipper, View.VISIBLE)
                    // Country code pinned static to the right of the scrolling region window so it's
                    // ALWAYS visible (the region can be arbitrarily long, but ", FR" never scrolls off).
                    setViewVisibility(
                        R.id.loc_ticker_country,
                        if (country.isNotEmpty()) View.VISIBLE else View.GONE
                    )
                    setTextViewText(R.id.loc_ticker_country, country)

                    // Pin the clip window to the locality width so the marquee tracks the city name
                    // (and never grows the block into the NYC clock).
                    val windowDp = windowChars * textSizeSp * TICKER_CHAR_DP
                    setViewLayoutWidth(R.id.loc_ticker_flipper, windowDp, TypedValue.COMPLEX_UNIT_DIP)

                    // Two IDENTICAL region copies. Each copy's TextView is given an EXPLICIT width
                    // equal to its own measured text width — larger than the flipper window. A child
                    // with an exact width is NOT clamped to the parent (unlike wrap_content, which the
                    // flipper would measure AT_MOST its own width and then ellipsize into "…"), so it
                    // holds the full region; the flipper simply clips it to the window. The in/out
                    // animations translate each by its own width, so the copies stay tiled edge-to-edge
                    // and the region scrolls smoothly and seamlessly (no ghosting, no ellipsis) while
                    // ", COUNTRY" stays pinned beside it. The launcher advances the flipper itself.
                    val marqueeText = region + TICKER_LOOP_GAP
                    val textWidthDp = measureTextWidthDp(context, marqueeText, textSizeSp)
                    removeAllViews(R.id.loc_ticker_flipper)
                    repeat(2) {
                        val copy = RemoteViews(context.packageName, R.layout.widget_ticker_frame)
                        copy.setTextViewText(R.id.ticker_frame_text, marqueeText)
                        copy.setTextViewTextSize(R.id.ticker_frame_text, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                        copy.setTextColor(R.id.ticker_frame_text, regionColorArgb)
                        copy.setViewLayoutWidth(R.id.ticker_frame_text, textWidthDp, TypedValue.COMPLEX_UNIT_DIP)
                        addView(R.id.loc_ticker_flipper, copy)
                    }
                } else {
                    // Pre-31 has no setViewLayoutWidth, so a clipped-window marquee isn't possible.
                    // Fall back to the ELLIPSIS look: static region (native end-ellipsis) + country.
                    setViewVisibility(R.id.loc_ticker_flipper, View.GONE)
                    setViewVisibility(R.id.loc_ticker_country, View.GONE)
                    setViewVisibility(R.id.loc_region, View.VISIBLE)
                    setViewVisibility(R.id.loc_country, View.VISIBLE)
                    setTextViewText(R.id.loc_region, region)
                    setTextViewText(R.id.loc_country, country)
                }
            }
            RegionReveal.FLIP -> {
                setViewVisibility(R.id.loc_region, View.GONE)
                setViewVisibility(R.id.loc_country, View.GONE)
                setViewVisibility(R.id.loc_ticker_flipper, View.GONE)
                setViewVisibility(R.id.loc_ticker_country, View.GONE)
                setViewVisibility(R.id.loc_flipper, View.VISIBLE)
                val (a, b) = splitHalf(fullText)
                setTextViewText(R.id.loc_flip_a, a)
                setTextViewText(R.id.loc_flip_b, b)
            }
            else -> {
                setViewVisibility(R.id.loc_ticker_flipper, View.GONE)
                setViewVisibility(R.id.loc_ticker_country, View.GONE)
                setViewVisibility(R.id.loc_flipper, View.GONE)
                setViewVisibility(R.id.loc_region, View.VISIBLE)
                setViewVisibility(R.id.loc_country, View.VISIBLE)
                setTextViewText(R.id.loc_region, region)
                setTextViewText(R.id.loc_country, country)
            }
        }
    }

    /**
     * Measures the rendered width of [text] at [textSizeSp] (default typeface) and returns it in dp.
     * Used to give each marquee copy an explicit width equal to its text, so the flipper clips the
     * full string to the window instead of the child ellipsizing at the (smaller) window width.
     */
    private fun measureTextWidthDp(context: Context, text: String, textSizeSp: Float): Float {
        val dm = context.resources.displayMetrics
        // MUST match the marquee copy's typeface (monospace, see widget_ticker_frame). Monospace has a
        // deterministic, font-defined advance, so this measured width equals what the launcher renders
        // on ANY device - which is exactly what keeps the two tiled copies aligned (no ghosting). A
        // proportional font here can measure narrower than the device's actual (e.g. Sony) system
        // font, under-sizing the copy so its text overflows and overlaps the next copy.
        val paint = android.graphics.Paint().apply {
            textSize = textSizeSp * dm.scaledDensity
            typeface = android.graphics.Typeface.MONOSPACE
        }
        return paint.measureText(text) / dm.density
    }

    /** Splits [text] into two halves at the space nearest the midpoint (for the flip animation). */
    private fun splitHalf(text: String): Pair<String, String> {
        if (text.length < 2) return text to ""
        val mid = text.length / 2
        var idx = -1
        var best = Int.MAX_VALUE
        text.forEachIndexed { i, c ->
            if (c == ' ') {
                val d = kotlin.math.abs(i - mid)
                if (d < best) { best = d; idx = i }
            }
        }
        return if (idx <= 0) text.substring(0, mid) to text.substring(mid)
        else text.substring(0, idx).trim() to text.substring(idx + 1).trim()
    }

    /**
     * Resolves the full-color weather drawable for a canonical status key and day/night, e.g.
     * "light_rain" + day -> R.drawable.wx_light_rain_day. Falls back to the unknown icon if a
     * status somehow has no matching asset.
     */
    private fun weatherIconRes(context: Context, statusKey: String, isDay: Boolean): Int {
        val suffix = if (isDay) "day" else "night"
        val id = context.resources.getIdentifier(
            "wx_${statusKey}_$suffix", "drawable", context.packageName
        )
        return if (id != 0) id else R.drawable.wx_unknown_day
    }

    @Composable
    private fun WidgetContent(
        events: List<ProtonEvent>,
        eventsIndex: Int,
        nextAlarm: String,
        timerFinishMs: Long,
        timerTotalMs: Long,
        weatherTemp: String,
        weatherCond: String,
        weatherLocality: String,
        weatherRegion: String,
        weatherCountry: String,
        weatherStatus: String,
        weatherIsDay: Boolean,
        locationTzId: String,
        size: DpSize,
        bgEnabled: Boolean,
        bgOpacity: Int,
        weeklySpend: WeeklySpend,
        spendEnabled: Boolean
    ) {
        val context = LocalContext.current

        // The clock/date/events follow the geocoded location's timezone. When it's unknown (before
        // the first weather fetch) fall back to the device timezone. In production these match; the
        // split only matters when the phone's tz differs from the displayed location (e.g. testing).
        val localTimeZone: TimeZone =
            if (locationTzId.isNotEmpty()) TimeZone.getTimeZone(locationTzId) else TimeZone.getDefault()
        // TextClock takes a zone id string; null means "device timezone".
        val clockTimeZoneId: String? = locationTzId.ifEmpty { null }

        // Same UTC offset as NYC right now => same wall-clock, so hide the NYC reference.
        val nowMillis = System.currentTimeMillis()
        val nycTimeZone = TimeZone.getTimeZone("America/New_York")
        val isNycTime = localTimeZone.getOffset(nowMillis) == nycTimeZone.getOffset(nowMillis)

        val isSmall = size.width <= 150.dp
        val isTall = size.height >= 200.dp
        val isUltraTall = size.height >= 400.dp

        // Left inset is applied PER-SECTION (not on the container) so the events row can opt out of it
        // and let its ‹ prev arrow live in that reclaimed space while the event title still lines up
        // flush-left with every other section. The events frame's ‹ box is sized to exactly this inset
        // (see widget_event_frame.xml's event_prev width). Top/bottom/end keep the normal padding.
        val contentInset = if (isSmall) 12.dp else 20.dp

        // Optional M3 themed widget surface (dynamic-color aware) at the system corner radius, with
        // user-controlled opacity. On: text keeps its onSurface contrast on ANY wallpaper (critical
        // on OLED where wallpapers are usually dark). Off: frameless (text straight on the wallpaper).
        // Always emit a background node and vary only its alpha (transparent when disabled). Omitting
        // the .background() modifier when off leaves Glance's REUSED RemoteViews painted with the old
        // opaque background until the widget is removed/re-added; painting transparent instead emits a
        // real "clear" instruction, so a settings change (toggle or opacity) reliably repaints on the
        // next forceRefresh.
        val bgAlpha = if (bgEnabled) bgOpacity.coerceIn(0, 100) / 100f else 0f
        val bg = GlanceTheme.colors.widgetBackground.getColor(context).copy(alpha = bgAlpha)
        val containerModifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(top = contentInset, bottom = contentInset, end = contentInset)
        // Center the content block vertically and pin it to the start (left) to match the KWGT look.
        // The whole surface is one big tap target (OpenAppAction); the inner sections keep their own
        // click actions (calendar/maps/weather/events) and take precedence in their own areas, so the
        // empty space around the centered content still opens the app on a single tap. Using a Box
        // (instead of weighted Column fillers) avoids a Glance measurement quirk where two weighted
        // children collapse the wrap-height AndroidRemoteViews rows (the date + clock) to zero height.
        Box(
            modifier = containerModifier.clickable(actionRunCallback<OpenAppAction>(), R.drawable.no_ripple),
            contentAlignment = Alignment.CenterStart
        ) {
            when {
                // Wide but short (the classic landscape / MEDIUM bucket, ~120-145dp tall): the
                // date+clock+weather stack alone already fills the usable height, so appending the
                // alarm+events below pushed them past the bottom edge and clipped them ("cut off
                // events"). Instead lay the content out in TWO COLUMNS and spend the ample horizontal
                // space: date/clock/weather on the left, alarm+events on the right. Nothing overflows
                // and the events stay visible after rotating.
                !isSmall && !isTall -> {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            PrimarySections(
                                clockTimeZoneId, weatherLocality, weatherRegion, weatherCountry,
                                isNycTime, isSmall, isUltraTall,
                                weatherCond, weatherTemp, weatherStatus, weatherIsDay, contentInset
                            )
                        }
                        Spacer(modifier = GlanceModifier.width(16.dp))
                        Column(
                            modifier = GlanceModifier.defaultWeight(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            // Wide-short (MEDIUM): height is already tight, so no spend tracker here.
                            SecondarySections(
                                events, eventsIndex, nextAlarm, timerFinishMs, timerTotalMs, localTimeZone, contentInset,
                                showSpendTracker = false, weeklySpend = weeklySpend
                            )
                        }
                    }
                }
                // Tall enough (portrait / LARGE) or the tiny SMALL bucket: single vertical column.
                // Alarm+events only stack under the primary block when there's both width and height
                // for them (a narrow SMALL widget shows just date/clock/weather).
                else -> {
                    Column(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        PrimarySections(
                            clockTimeZoneId, weatherLocality, weatherRegion, weatherCountry,
                            isNycTime, isSmall, isUltraTall,
                            weatherCond, weatherTemp, weatherStatus, weatherIsDay, contentInset
                        )
                        if (!isSmall && isTall) {
                            SecondarySections(
                                events, eventsIndex, nextAlarm, timerFinishMs, timerTotalMs, localTimeZone, contentInset,
                                showSpendTracker = spendEnabled, weeklySpend = weeklySpend
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The always-shown top block: date header, the clock/location row and the weather line. Shared
     * by the vertical (portrait) layout and the two-column (wide-short) layout so the two never drift.
     */
    @Composable
    private fun PrimarySections(
        clockTimeZoneId: String?,
        weatherLocality: String,
        weatherRegion: String,
        weatherCountry: String,
        isNycTime: Boolean,
        isSmall: Boolean,
        isUltraTall: Boolean,
        weatherCond: String,
        weatherTemp: String,
        weatherStatus: String,
        weatherIsDay: Boolean,
        contentInset: Dp
    ) {
        val context = LocalContext.current
        // Left inset lives here (not on the container) so the events row can skip it and slot its ‹
        // arrow into the reclaimed space; every primary section still starts at the normal margin.
        Column(modifier = GlanceModifier.fillMaxWidth().padding(start = contentInset)) {
            // Date Header - live TextClock so it rolls over at midnight without a refresh.
            val datePattern = if (isSmall) "MMM dd" else "EEEE, MMMM dd"
            AndroidRemoteViews(
                remoteViews = clockRemoteViews(
                    context = context,
                    format12 = datePattern,
                    format24 = datePattern,
                    timeZoneId = clockTimeZoneId,
                    textSizeSp = if (isSmall) 12f else 16f * SECTION_SCALE,
                    colorArgb = GlanceTheme.colors.primary.getColor(context).toArgb()
                ),
                modifier = GlanceModifier.clickable(actionRunCallback<OpenCalendarAction>())
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            // Clock - Bold and Prominent (self-updating)
            ClockDisplay(weatherLocality, weatherRegion, weatherCountry, clockTimeZoneId, isNycTime, isSmall, isUltraTall)

            // Weather sits directly under the clock as one group. No spacer here: the weather
            // row is already taller than its text (the icon sets the height), so an extra gap
            // reads as awkward padding above the line.
            WeatherDisplay(weatherCond, weatherTemp, weatherStatus, weatherIsDay, isSmall)
        }
    }

    /**
     * The alarm line (+ optional running/ended system timer controls) and the events flipper. Shared
     * by both layouts: stacked beneath the primary block when tall, or placed in the right column
     * when the widget is wide but short.
     */
    @Composable
    private fun SecondarySections(
        events: List<ProtonEvent>,
        eventsIndex: Int,
        nextAlarm: String,
        timerFinishMs: Long,
        timerTotalMs: Long,
        localTimeZone: TimeZone,
        contentInset: Dp,
        showSpendTracker: Boolean,
        weeklySpend: WeeklySpend
    ) {
        val context = LocalContext.current
        // Smaller than the gap below: the weather row above is icon-height, so its extra
        // bottom padding already contributes visual space above the alarm.
        Spacer(modifier = GlanceModifier.height(4.dp))

        // Alarm + system timer on ONE native line so the alarm label and the self-ticking
        // countdown share a single baseline (see alarmLineRemoteViews). The row height is
        // pinned and the native line fills it (fillMaxHeight), which both stops the embedded
        // RemoteViews collapsing to ~0 height inside the Row and keeps the countdown's
        // vertical position fixed when the restart/dismiss controls appear at expiry.
        val nowMs = System.currentTimeMillis()
        val hasTimer = timerFinishMs > 0L
        val timerPassed = hasTimer && timerFinishMs <= nowMs
        // The restart/dismiss controls stack vertically (refresh over X) so the pair stays
        // narrow and fits beside the alarm+countdown on one line. The row must be tall enough
        // for that two-button stack, so size it to the larger of the text line and the stack.
        val alarmTextLineDp = 15f * SECTION_SCALE * 1.6f
        val timerStackHeightDp = TIMER_BTN_BOX_DP * 2 + TIMER_BTN_GAP_DP
        val alarmRowHeight = maxOf(alarmTextLineDp.dp, timerStackHeightDp.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Alarm carries the normal left inset; the events row below deliberately does NOT (its ‹
            // arrow fills that inset), so the alarm text and the event title still line up flush-left.
            modifier = GlanceModifier.fillMaxWidth().padding(start = contentInset).height(alarmRowHeight)
        ) {
            // Dim the countdown once passed to signal it's done (now ticking overtime negative).
            val timerColor = if (timerPassed) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface
            // Convert the wall-clock finish into a Chronometer elapsedRealtime base so it ticks
            // on its own in the launcher (no app refresh needed).
            val base = SystemClock.elapsedRealtime() + (timerFinishMs - nowMs)
            AndroidRemoteViews(
                remoteViews = alarmLineRemoteViews(
                    context = context,
                    alarmText = nextAlarm,
                    alarmColorArgb = GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb(),
                    textSizeSp = 15f * SECTION_SCALE,
                    showTimer = hasTimer,
                    timerBaseElapsedRealtime = base,
                    timerColorArgb = timerColor.getColor(context).toArgb()
                ),
                modifier = GlanceModifier.wrapContentWidth().fillMaxHeight()
            )
            if (timerPassed) {
                Spacer(modifier = GlanceModifier.width(6.dp))
                // Stack the controls vertically (refresh above X) so they take one narrow
                // column instead of a wide pair, keeping the whole alarm line on one row.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (timerTotalMs > 0L) {
                        TimerControlButton(
                            iconRes = R.drawable.ic_refresh,
                            description = "Restart timer",
                            onClick = actionRunCallback<RestartTimerAction>()
                        )
                        Spacer(modifier = GlanceModifier.height(TIMER_BTN_GAP_DP.dp))
                    }
                    TimerControlButton(
                        iconRes = R.drawable.ic_close,
                        description = "Dismiss timer",
                        onClick = actionRunCallback<TimerDismissAction>()
                    )
                }
            }
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        // Events Section: a self-cycling ViewFlipper of upcoming Proton events; tapping a
        // frame opens that event in Proton.
        EventsFlipper(events, eventsIndex, localTimeZone)

        // Weekly spend tracker sits directly under events. Only shown when the layout has real
        // vertical room (tall/portrait): the wide-short MEDIUM bucket is already height-constrained
        // (see WidgetContent), so we skip it there rather than clip it.
        if (showSpendTracker) {
            WeeklySpendTracker(contentInset, weeklySpend)
        }
    }

    @Composable
    private fun ClockDisplay(
        weatherLocality: String,
        weatherRegion: String,
        weatherCountry: String,
        clockTimeZoneId: String?,
        isNycTime: Boolean,
        isSmall: Boolean,
        isUltraTall: Boolean
    ) {
        val context = LocalContext.current
        // Kept intentionally moderate: a larger clock eats the row width and clips the locality /
        // NYC blocks to its right.
        val timeSizeSp: Float = when {
            isSmall -> 23f
            isUltraTall -> 44f
            else -> 28f
        }
        val locationLineSize = (timeSizeSp / 2.4f).sp
        val onSurface = GlanceTheme.colors.onSurface.getColor(context).toArgb()
        val onSurfaceVariant = GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb()

        // Embedded RemoteViews (AndroidRemoteViews) collapse to ~0 height inside a Glance Row
        // because their intrinsic height isn't propagated to the row's measurement. Pin an
        // explicit row height sized to the clock text so the time/locality/NYC blocks stay
        // visible; the TextClock/location layouts center their content vertically within it.
        val clockRowHeight = (timeSizeSp * 1.35f).dp

        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .height(clockRowHeight)
                .clickable(actionRunCallback<OpenMapsAction>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Local Time - live TextClock in the location's timezone. Wrap width so it doesn't
            // eat the whole row and squeeze out the locality + NYC blocks.
            AndroidRemoteViews(
                remoteViews = clockRemoteViews(context, "h:mm a", "HH:mm", clockTimeZoneId, timeSizeSp, onSurface),
                modifier = GlanceModifier.wrapContentWidth().fillMaxHeight()
            )

            if (!isSmall) {
                Spacer(modifier = GlanceModifier.width(10.dp))

                // Location block (locality over "region, COUNTRY") as one RemoteViews, sized to its
                // content (wrapContentWidth) so it packs against the local time and leaves room for
                // the NYC block. The city name and country code are always shown in full; only the
                // region name is capped (native maxWidth in the layout) and ellipsizes when long,
                // which is what stops an oversized region from clipping the NYC block.
                val countrySuffix = when {
                    weatherRegion.isNotEmpty() && weatherCountry.isNotEmpty() -> ", $weatherCountry"
                    weatherRegion.isEmpty() -> weatherCountry
                    else -> ""
                }
                val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val revealMode = prefs.getString(RegionReveal.PREF_MODE, RegionReveal.TICKER)
                    ?: RegionReveal.TICKER
                AndroidRemoteViews(
                    remoteViews = locationBlockRemoteViews(
                        context = context,
                        locality = weatherLocality.uppercase(),
                        region = weatherRegion,
                        country = countrySuffix,
                        textSizeSp = locationLineSize.value,
                        localityColorArgb = onSurface,
                        regionColorArgb = onSurfaceVariant,
                        mode = revealMode
                    ),
                    modifier = GlanceModifier.wrapContentWidth().fillMaxHeight()
                )

                // NYC reference: a tall, skinny separator bar followed by a compact two-line
                // block (live NYC time over "NYC, US"). Kept intrinsically sized so it never gets
                // cut off, and smaller than both the local time and the locality on its left.
                if (!isNycTime) {
                    // Right block stays clearly subordinate to the prominent local time on the
                    // left, but large enough to read: ~0.54x the local clock.
                    val nycTimeSize = timeSizeSp * 0.54f
                    val nycLabelSize = (timeSizeSp / 2.8f).sp
                    val dividerHeight = (locationLineSize.value * 2.4f).dp

                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Box(
                        modifier = GlanceModifier
                            .width(2.dp)
                            .height(dividerHeight)
                            .background(GlanceTheme.colors.onSurfaceVariant)
                    ) {}
                    Spacer(modifier = GlanceModifier.width(10.dp))

                    // Single RemoteViews (native LinearLayout) so the NYC time and "NYC, US" label
                    // stack with the same tight vertical spacing as the locality / "region, COUNTRY"
                    // block. wrap_content + fillMaxHeight lets native measurement size the width
                    // (no clock clipping) and center it vertically in the row, matching the location
                    // block to its left.
                    AndroidRemoteViews(
                        remoteViews = nycBlockRemoteViews(
                            context = context,
                            timeSizeSp = nycTimeSize,
                            labelSizeSp = nycLabelSize.value,
                            timeColorArgb = onSurface,
                            labelColorArgb = onSurfaceVariant
                        ),
                        modifier = GlanceModifier.wrapContentWidth().fillMaxHeight()
                    )
                }

                // Absorb leftover width on the far right so the time/locality/NYC group packs
                // tightly on the left instead of leaving a gap in the middle.
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
        }
    }

    /**
     * The weekly Groceries + Fun spend tracker rendered under the events flipper. Read-only first
     * step: the spendable LIMIT is computed on-device from the plan overlay (see
     * [WeeklySpendRepository]); SPENT is whatever a later Monarch sync has written to prefs.
     *
     * The face is deliberately minimal - a wavy M3-style progress ring whose centre shows the cash
     * left this week, plus a one-word sync state - so a glance answers "how much can I still spend?".
     *
     * Accessibility (per Android's a11y + Material 3 guidance):
     * - The ring carries one spoken [contentDescription] ("$X left, last synced N min ago" or
     *   "$X over budget"), so the over state never rides on the red ring colour alone.
     * - Colours come from [GlanceTheme] M3 roles (onSurface / surfaceVariant / primary / error), so
     *   contrast holds on any wallpaper and the ring follows the user's dynamic theme.
     * - Type/ring scale with the shared SECTION_SCALE, tracking the user's density/text-size choice.
     */
    @Composable
    private fun WeeklySpendTracker(contentInset: Dp, spend: WeeklySpend) {
        val context = LocalContext.current

        // Fraction of the weekly limit already spent, clamped to [0,1]. With no budget (Nov/Dec)
        // any spend fills the ring so the over state still reads at a glance.
        val fraction = when {
            spend.limitCents > 0L -> (spend.spentCents.toFloat() / spend.limitCents).coerceIn(0f, 1f)
            spend.spentCents > 0L -> 1f
            else -> 0f
        }

        val onSurfaceVariant = GlanceTheme.colors.onSurfaceVariant
        val onSurface = GlanceTheme.colors.onSurface
        // The ring outline (track) is drawn in the widget background colour so it reads as a subtle
        // groove in the surface rather than a contrasting band; only the wavy indicator stands out.
        val trackArgb = GlanceTheme.colors.widgetBackground.getColor(context).toArgb()
        val indicatorArgb =
            (if (spend.over) GlanceTheme.colors.error else GlanceTheme.colors.primary)
                .getColor(context).toArgb()
        val amountSp = (16f * SECTION_SCALE).sp
        val captionSp = (10f * SECTION_SCALE).sp

        // Only two things on the face: the cash figure and the sync/auth state. They stack beside the
        // ring (amount over state), mirroring the NYC time-over-label block. auth_ok=false means no
        // Monarch session, so the number can't be trusted - say "Sign in" rather than a stale figure.
        // When over budget, show how far over as a negative figure (spent - limit) instead of a flat
        // "$0 left", so the amount itself carries the overspend (not just the red ring colour).
        val overCents = (spend.spentCents - spend.limitCents).coerceAtLeast(0L)
        val cashLeft = if (spend.over) "-" + formatDollars(overCents) else formatDollars(spend.remainingCents)
        val synced = spend.authOk && spend.asOfMs > 0L
        // Show the wall-clock time of the last successful read rather than a bare "Synced" word, so a
        // glance tells the user how fresh the figure is. Locale-aware 12/24h via DateUtils.
        val lastSyncTime = DateUtils.formatDateTime(context, spend.asOfMs, DateUtils.FORMAT_SHOW_TIME)
        val syncText = when {
            !spend.authOk -> "Sign in"
            synced -> "Last sync $lastSyncTime"
            else -> "Not synced"
        }

        // Smaller ring now that the numbers live beside it, not inside it (empty centre label).
        val ringDp = 40f * SECTION_SCALE

        val spoken = buildString {
            append("Weekly groceries and fun. ")
            append(
                if (spend.over) formatDollars(overCents) + " over budget. "
                else "$cashLeft left. "
            )
            append(
                when {
                    !spend.authOk -> "Not connected to Monarch, sign in."
                    synced -> "Last synced " + DateUtils.getRelativeTimeSpanString(
                        spend.asOfMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    )
                    else -> "Not synced"
                }
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = contentInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The sync tap target is ONLY the ring + text (wrap-content), not the full-width row, and
            // uses the no-ripple drawable so tapping shows no white highlight. Tapping it forces an
            // immediate Monarch spend sync (see SyncSpendAction), overriding the container's open-app
            // tap just in this area. The clickable lives on a wrapping container (not on the embedded
            // AndroidRemoteViews) because Glance won't reliably attach a PendingIntent to a RemoteViews
            // subtree.
            Row(
                modifier = GlanceModifier.clickable(actionRunCallback<SyncSpendAction>(), R.drawable.no_ripple),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated wavy ring: an auto-cycling 8-frame ViewFlipper (the only reliable way to
                // animate inside RemoteViews) morphs the wave so the ring visibly moves. Each frame is
                // rendered at a capped resolution and upscaled by its ImageView, so all 8 frames stay
                // well under the RemoteViews bitmap budget.
                Box(modifier = GlanceModifier.size(ringDp.dp)) {
                    AndroidRemoteViews(
                        remoteViews = wavyFlipperRemoteViews(
                            context = context,
                            fraction = fraction,
                            sizeDp = ringDp,
                            strokeDp = 5f,
                            trackArgb = trackArgb,
                            indicatorArgb = indicatorArgb,
                            spoken = spoken
                        ),
                        modifier = GlanceModifier.size(ringDp.dp)
                    )
                }
                Spacer(modifier = GlanceModifier.width(10.dp))
                Column {
                    Text(
                        text = cashLeft,
                        style = TextStyle(fontSize = amountSp, fontWeight = FontWeight.Bold, color = onSurface)
                    )
                    Text(
                        text = syncText,
                        style = TextStyle(fontSize = captionSp, color = onSurfaceVariant)
                    )
                }
            }
        }
    }

    /**
     * Draws a determinate, Material-3-expressive WAVY progress ring (flat thin track + a sine-wave
     * indicator arc with a centred label) to a Bitmap. Home-screen widgets are RemoteViews, so
     * neither Compose's CircularWavyProgressIndicator nor the Material Views CircularProgressIndicator
     * can run here; a Canvas-drawn bitmap shown via a Glance Image is the reliable way to get a
     * determinate wavy radial indicator. Colours are passed in already resolved from GlanceTheme so
     * the ring follows the M3 dynamic theme (and light/dark).
     */
    private fun radialProgressBitmap(
        context: Context,
        fraction: Float,
        sizeDp: Float,
        strokeDp: Float,
        trackArgb: Int,
        indicatorArgb: Int,
        textArgb: Int,
        centerText: String,
        phaseDeg: Float = 0f,
        maxSizePx: Int = Int.MAX_VALUE
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        // Cap the rendered pixel size (the animated flipper passes a cap) so N phase frames stay well
        // within the RemoteViews bitmap budget that strict OEM launchers enforce; stroke/amplitude
        // scale with it to preserve proportions, and the ImageView upscales the capped frame to the
        // ring's display size. Unset (single static frame) renders at full display resolution.
        val fullPx = sizeDp * density
        val renderScale = if (fullPx > maxSizePx) maxSizePx / fullPx else 1f
        val size = (fullPx * renderScale).toInt().coerceAtLeast(1)
        val stroke = strokeDp * density * renderScale
        // Wave swing (how far the radius oscillates). Clamp it so the wave can never exceed the
        // circle: even at a peak the arc stays within the ring's radius (comfortably inside the
        // diameter) and baseR stays positive.
        val amplitude = (stroke * 1.05f).coerceAtMost(size / 2f - stroke)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        // Leave room for both the stroke half-width and the wave's outward swing so it never clips.
        val baseR = size / 2f - stroke / 2f - amplitude - 1f

        // Flat, thin track behind everything (the "remaining" part of the ring).
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * 0.6f
            strokeCap = Paint.Cap.ROUND
            color = trackArgb
        }
        canvas.drawCircle(cx, cy, baseR, trackPaint)

        // Wavy active arc: the radius oscillates as a sine wave along the arc, sampled as a polyline
        // in small angular steps and stroked with round caps/joins for the smooth M3 look.
        val sweep = fraction.coerceIn(0f, 1f) * 360f
        if (sweep > 0f) {
            val waves = 9            // wave cycles around a full circle
            val startDeg = -90f      // begin at 12 o'clock
            val stepDeg = 2f
            val path = Path()
            var deg = 0f
            var first = true
            while (deg <= sweep) {
                // phaseDeg shifts the wave along the arc; stepping it across flipper frames makes the
                // wave appear to travel (one full period over a loop = a seamless morph).
                val r = baseR + amplitude * Math.sin(Math.toRadians((deg * waves + phaseDeg).toDouble())).toFloat()
                val rad = Math.toRadians((startDeg + deg).toDouble())
                val x = cx + r * Math.cos(rad).toFloat()
                val y = cy + r * Math.sin(rad).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                deg += stepDeg
            }
            val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                // Thin moving line: draw the wavy indicator at ~55% of the base stroke so it reads as
                // a slim line, not a heavy band, while the wave amplitude (from the full stroke) keeps
                // the bumps pronounced.
                strokeWidth = stroke * 0.55f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = indicatorArgb
            }
            canvas.drawPath(path, indicatorPaint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textArgb
            textAlign = Paint.Align.CENTER
            textSize = size * 0.26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(centerText, cx, ty, textPaint)

        return bmp
    }

    /**
     * Builds the auto-cycling wavy ring for the widget: one [radialProgressBitmap] per ViewFlipper
     * frame, each with the wave phase advanced by 360/N so the loop shows exactly one wavelength of
     * travel (seamless). The flipper (autoStart + short flipInterval, no transition) does the motion
     * on the launcher's UI thread - the only reliable way to animate inside RemoteViews. The frame
     * count is kept small (8) AND each frame is rendered at a capped resolution (FLIPPER_FRAME_MAX_PX)
     * then upscaled by the ImageView, so all 8 frames together stay well under the RemoteViews bitmap
     * budget some OEM launchers (e.g. Sony's XperiaLauncher) enforce - which previously blanked the
     * widget when full-density frames pushed the payload past ~1 MB.
     */
    private fun wavyFlipperRemoteViews(
        context: Context,
        fraction: Float,
        sizeDp: Float,
        strokeDp: Float,
        trackArgb: Int,
        indicatorArgb: Int,
        spoken: String
    ): RemoteViews {
        val frameIds = intArrayOf(
            R.id.frame_0, R.id.frame_1, R.id.frame_2, R.id.frame_3,
            R.id.frame_4, R.id.frame_5, R.id.frame_6, R.id.frame_7
        )
        val n = frameIds.size
        return RemoteViews(context.packageName, R.layout.widget_wavy_flipper).apply {
            for (i in 0 until n) {
                val bmp = radialProgressBitmap(
                    context = context,
                    fraction = fraction,
                    sizeDp = sizeDp,
                    strokeDp = strokeDp,
                    trackArgb = trackArgb,
                    indicatorArgb = indicatorArgb,
                    textArgb = indicatorArgb,
                    centerText = "",
                    phaseDeg = 360f * i / n,
                    maxSizePx = FLIPPER_FRAME_MAX_PX
                )
                setImageViewBitmap(frameIds[i], bmp)
            }
            setContentDescription(R.id.wavy_flipper, spoken)
        }
    }

    /** Compact whole-dollar currency for the widget face (e.g. 17566 cents -> "$176"). */
    private fun formatDollars(cents: Long): String {
        val dollars = Math.round(cents / 100.0)
        return "$" + java.text.NumberFormat.getIntegerInstance(Locale.US).format(dollars)
    }

    @Composable
    private fun EventsFlipper(
        events: List<ProtonEvent>,
        displayIndex: Int,
        tz: TimeZone
    ) {
        val context = LocalContext.current
        if (events.isEmpty()) {
            Text(
                text = "No upcoming events",
                style = TextStyle(fontSize = (15f * SECTION_SCALE).sp, color = GlanceTheme.colors.onSurfaceVariant)
            )
            return
        }

        val titleColor = GlanceTheme.colors.onSurface.getColor(context).toArgb()
        val subColor = GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb()
        // displayIndex is a composition input (see provideGlance): passing it here makes an arrow tap
        // produce a genuinely different RemoteViews, so Glance actually pushes the update.
        AndroidRemoteViews(
            remoteViews = eventsRowRemoteViews(context, events, displayIndex, tz, titleColor, subColor),
            modifier = GlanceModifier.fillMaxWidth()
        )
    }

    /** Builds one event frame (title over "when" + optional "n/total") with its open-on-tap intent. */
    private fun eventFrameRemoteViews(
        context: Context,
        event: ProtonEvent,
        index: Int,
        total: Int,
        tz: TimeZone,
        titleColorArgb: Int,
        subColorArgb: Int
    ): RemoteViews {
        val dateFmt = SimpleDateFormat(
            if (event.fullDay) "EEE, MMM d" else "EEE, MMM d • HH:mm",
            Locale.getDefault()
        )
        // Full-day events are anchored at midnight UTC (Proton stores the DATE that way); rendering
        // them in the device timezone would shift a UTC midnight back to the previous evening and show
        // the wrong day (e.g. a Fri "Pay Day" as Thu). Format the date itself in UTC; timed events
        // still render in the local zone.
        dateFmt.timeZone = if (event.fullDay) TimeZone.getTimeZone("UTC") else tz

        return RemoteViews(context.packageName, R.layout.widget_event_frame).apply {
            // Scale the events section by the shared SECTION_SCALE off each view's baked base size
            // (title 15 / when 13 / counter 11 / arrows 24sp), so it enlarges in lockstep with the
            // calendar and alarm sections while keeping their internal proportions intact.
            setTextViewTextSize(R.id.event_title, TypedValue.COMPLEX_UNIT_SP, 15f * SECTION_SCALE)
            setTextViewTextSize(R.id.event_when, TypedValue.COMPLEX_UNIT_SP, 13f * SECTION_SCALE)
            setTextViewText(R.id.event_title, event.title)
            setTextColor(R.id.event_title, titleColorArgb)
            setTextViewText(R.id.event_when, dateFmt.format(Date(event.startTime)))
            setTextColor(R.id.event_when, subColorArgb)
            if (total > 1) {
                setViewVisibility(R.id.event_counter, View.VISIBLE)
                setTextViewTextSize(R.id.event_counter, TypedValue.COMPLEX_UNIT_SP, 11f * SECTION_SCALE)
                setTextViewText(R.id.event_counter, "${index + 1}/$total")
                setTextColor(R.id.event_counter, subColorArgb)

                // This frame carries its own arrows, so their targets are absolute and correct for
                // THIS event regardless of how far the flipper has auto-advanced: ‹ wraps to the
                // last event when on the first, › wraps to the first when on the last.
                val prevTarget = (index - 1 + total) % total
                val nextTarget = (index + 1) % total
                setViewVisibility(R.id.event_prev, View.VISIBLE)
                setViewVisibility(R.id.event_next, View.VISIBLE)
                // TalkBack reads these as the literal ‹ / › glyphs otherwise; label them by intent.
                setContentDescription(R.id.event_prev, "Previous event")
                setContentDescription(R.id.event_next, "Next event")
                setTextViewTextSize(R.id.event_prev, TypedValue.COMPLEX_UNIT_SP, 24f * SECTION_SCALE)
                setTextViewTextSize(R.id.event_next, TypedValue.COMPLEX_UNIT_SP, 24f * SECTION_SCALE)
                setTextColor(R.id.event_prev, subColorArgb)
                setTextColor(R.id.event_next, subColorArgb)
                setOnClickPendingIntent(
                    R.id.event_prev,
                    eventNavPendingIntent(context, index * 2, prevTarget)
                )
                setOnClickPendingIntent(
                    R.id.event_next,
                    eventNavPendingIntent(context, index * 2 + 1, nextTarget)
                )
            } else {
                setViewVisibility(R.id.event_counter, View.GONE)
                setViewVisibility(R.id.event_prev, View.GONE)
                setViewVisibility(R.id.event_next, View.GONE)
            }
            setOnClickPendingIntent(
                R.id.event_frame_root,
                eventClickPendingIntent(context, index, event.eventId, event.startTime)
            )
        }
    }

    /**
     * Builds the events row: ‹ / › arrows flanking the event area. With 2+ events the flipper holds
     * every event and [displayIndex] (from the widget's Glance state) picks the shown one; with a
     * single event we use a static holder instead of a one-child flipper (which would flicker). Each
     * frame keeps its own open-on-tap intent.
     */
    private fun eventsRowRemoteViews(
        context: Context,
        events: List<ProtonEvent>,
        displayIndex: Int,
        tz: TimeZone,
        titleColorArgb: Int,
        subColorArgb: Int
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_events_row).apply {
        removeAllViews(R.id.events_flipper)
        removeAllViews(R.id.events_single)
        val total = events.size

        if (total <= 1) {
            // Single (or no) event: no cycling, no arrows, static holder to avoid flipper flicker.
            setViewVisibility(R.id.events_flipper, View.GONE)
            setViewVisibility(R.id.events_single, View.VISIBLE)
            events.firstOrNull()?.let { event ->
                addView(
                    R.id.events_single,
                    eventFrameRemoteViews(context, event, 0, total, tz, titleColorArgb, subColorArgb)
                )
            }
            return@apply
        }

        setViewVisibility(R.id.events_single, View.GONE)
        setViewVisibility(R.id.events_flipper, View.VISIBLE)

        events.forEachIndexed { index, event ->
            addView(
                R.id.events_flipper,
                eventFrameRemoteViews(context, event, index, total, tz, titleColorArgb, subColorArgb)
            )
        }

        // Show exactly the requested index (the flipper no longer auto-advances), so every render —
        // background refresh or arrow tap — lands on the intended event. Clamp into range in case the
        // event count shrank since the index was set.
        val safeIndex = ((displayIndex % total) + total) % total
        setDisplayedChild(R.id.events_flipper, safeIndex)
    }

    /**
     * A per-arrow PendingIntent that jumps the flipper to an absolute [targetIndex]. [requestCode]
     * must be unique per arrow so the intents don't collide: PendingIntents are keyed by request
     * code + intent fields (NOT extras), so reusing a code with FLAG_UPDATE_CURRENT would make every
     * arrow share the last-built target. We derive it from the frame index and direction.
     */
    private fun eventNavPendingIntent(context: Context, requestCode: Int, targetIndex: Int): PendingIntent {
        val intent = Intent(context, EventNavReceiver::class.java)
            .setAction(EventNavReceiver.ACTION_GO)
            .putExtra(EventNavReceiver.EXTRA_TARGET, targetIndex)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun eventClickPendingIntent(
        context: Context,
        requestCode: Int,
        eventId: String,
        startMillis: Long
    ): PendingIntent {
        val intent = Intent(context, EventClickReceiver::class.java)
            .setAction(EventClickReceiver.ACTION)
            .putExtra(EventClickReceiver.EXTRA_EVENT_ID, eventId)
            .putExtra(EventClickReceiver.EXTRA_START, startMillis)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Composable
    private fun WeatherDisplay(
        weatherCond: String,
        weatherTemp: String,
        weatherStatus: String,
        weatherIsDay: Boolean,
        isSmall: Boolean
    ) {
        val context = LocalContext.current
        val iconRes = weatherIconRes(context, weatherStatus, weatherIsDay)

        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .clickable(actionRunCallback<OpenWeatherSearchAction>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isSmall) {
                Text(
                    text = "$weatherCond • ",
                    style = TextStyle(
                        fontSize = 20.sp, 
                        fontWeight = FontWeight.Medium, 
                        color = GlanceTheme.colors.onSurfaceVariant
                    )
                )
            }
            
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = weatherCond,
                modifier = GlanceModifier.size(if (isSmall) 28.dp else 36.dp)
            )
            
            Spacer(modifier = GlanceModifier.width(4.dp))
            
            Text(
                text = weatherTemp,
                style = TextStyle(
                    fontSize = if (isSmall) 17.sp else 20.sp, 
                    fontWeight = FontWeight.Medium, 
                    color = GlanceTheme.colors.onSurfaceVariant
                )
            )
        }
    }

}
