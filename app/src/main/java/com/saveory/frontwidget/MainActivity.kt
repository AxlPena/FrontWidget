package com.saveory.frontwidget

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import com.saveory.frontwidget.data.WeatherProviders
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.proton.core.account.domain.entity.isReady
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.accountmanager.presentation.observe
import me.proton.core.accountmanager.presentation.onAccountCreateAddressFailed
import me.proton.core.accountmanager.presentation.onAccountCreateAddressNeeded
import me.proton.core.accountmanager.presentation.onAccountDeviceSecretNeeded
import me.proton.core.accountmanager.presentation.onAccountReady
import me.proton.core.accountmanager.presentation.onAccountTwoPassModeFailed
import me.proton.core.accountmanager.presentation.onAccountTwoPassModeNeeded
import me.proton.core.accountmanager.presentation.onSessionSecondFactorNeeded
import me.proton.core.auth.presentation.AuthOrchestrator
import me.proton.core.auth.presentation.onLoginResult
import javax.inject.Inject

/**
 * Branded FrontWidget entry point. Shows our own landing screen (this is an independent app,
 * not a Proton product) and, when the user chooses to connect, hands off to Proton Core's
 * secure login flow. The account/session is persisted by Proton Core in Room.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var accountManager: AccountManager

    @Inject
    lateinit var authOrchestrator: AuthOrchestrator

    enum class ConnectState { Landing, Connecting, Connected }

    private var connectState by mutableStateOf(ConnectState.Landing)

    // Set when opened via the widget's triple-tap gesture; auto-starts Proton login once we know
    // the account isn't already connected.
    private var openSignInRequested = false

    // How many days ahead of "now" the widget pulls Proton events for. User-selectable.
    private var eventsWindowDays by mutableStateOf(EventsWorker.DEFAULT_WINDOW_DAYS)

    // Which weather backend the widget fetches from (OpenWeather / Open-Meteo). User-selectable.
    private var weatherProvider by mutableStateOf(WeatherProviders.DEFAULT)

    // Widget container background: whether it's drawn, and its opacity (0..100). User-selectable.
    private var backgroundEnabled by mutableStateOf(FrontWidget.DEFAULT_BG_ENABLED)
    private var backgroundOpacity by mutableStateOf(FrontWidget.DEFAULT_BG_OPACITY)

    // Which app calendar/event taps open: device default vs the Proton Calendar integration.
    private var calendarTarget by mutableStateOf(FrontWidget.DEFAULT_CALENDAR_TARGET)

    // Whether the user has granted Notification access, which the timer feature needs to read the
    // Clock app's running-countdown notification. Re-read in onResume so the UI flips to "enabled"
    // the moment the user returns from the system settings screen after granting it.
    private var notificationAccessGranted by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshWidget() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationAccessGranted = isNotificationAccessGranted()

        openSignInRequested = intent?.getBooleanExtra(EXTRA_OPEN_SIGN_IN, false) == true

        getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .edit()
            .remove("selected_calendars")
            .apply()

        eventsWindowDays = getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .getInt("events_window_days", EventsWorker.DEFAULT_WINDOW_DAYS)

        weatherProvider = getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .getString(WeatherProviders.PREF_KEY, WeatherProviders.DEFAULT) ?: WeatherProviders.DEFAULT

        getSharedPreferences("widget_prefs", MODE_PRIVATE).let { p ->
            backgroundEnabled = p.getBoolean(FrontWidget.KEY_BG_ENABLED, FrontWidget.DEFAULT_BG_ENABLED)
            backgroundOpacity = p.getInt(FrontWidget.KEY_BG_OPACITY, FrontWidget.DEFAULT_BG_OPACITY)
            calendarTarget = p.getString(FrontWidget.KEY_CALENDAR_TARGET, FrontWidget.DEFAULT_CALENDAR_TARGET)
                ?: FrontWidget.DEFAULT_CALENDAR_TARGET
        }

        // Debug hook to A/B the region reveal styles: `am start ... --es reveal_mode flip|ticker|ellipsis`.
        intent?.getStringExtra("reveal_mode")?.let { mode ->
            getSharedPreferences("widget_prefs", MODE_PRIVATE).edit()
                .putString(RegionReveal.PREF_MODE, mode)
                .putInt(RegionReveal.PREF_OFFSET, 0)
                .apply()
            refreshWidget()
        }

        // Wire the Proton account state machine (login, 2FA, two-pass, address, device secret).
        authOrchestrator.register(this)
        accountManager.observe(lifecycle, minActiveState = Lifecycle.State.CREATED)
            .onAccountReady { onAccountReady() }
            .onSessionSecondFactorNeeded { authOrchestrator.startSecondFactorWorkflow(it) }
            .onAccountTwoPassModeNeeded { authOrchestrator.startTwoPassModeWorkflow(it) }
            .onAccountCreateAddressNeeded { authOrchestrator.startChooseAddressWorkflow(it) }
            .onAccountDeviceSecretNeeded { authOrchestrator.startDeviceSecretWorkflow(it) }
            .onAccountTwoPassModeFailed { lifecycleScope.launch { accountManager.disableAccount(it.userId) } }
            .onAccountCreateAddressFailed { lifecycleScope.launch { accountManager.disableAccount(it.userId) } }

        // If the user backs out of Proton's login, return to our landing screen.
        authOrchestrator.onLoginResult { result ->
            if (result == null && connectState != ConnectState.Connected) {
                connectState = ConnectState.Landing
            }
        }

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        // Reflect whether a Proton account is already connected.
        accountManager.getAccounts().onEach { accounts ->
            val connected = accounts.any { it.isReady() }
            connectState = when {
                connected -> ConnectState.Connected
                connectState == ConnectState.Connecting -> ConnectState.Connecting
                else -> ConnectState.Landing
            }
            // Honor the widget triple-tap gesture: jump straight into Proton login unless already
            // connected or a login is in flight.
            if (openSignInRequested && !connected && connectState != ConnectState.Connecting) {
                openSignInRequested = false
                startConnect()
            }
        }.launchIn(lifecycleScope)

        val showIconGrid = intent?.getBooleanExtra("show_icon_grid", false) == true
        val showIconPopup = intent?.getBooleanExtra("show_icon_popup", false) == true
        val showMswGrid = intent?.getBooleanExtra("show_msw_grid", false) == true
        setContent {
            FrontWidgetTheme {
                if (showMswGrid) { MaterialWeatherGallery(); return@FrontWidgetTheme }
                if (showIconGrid) { WeatherIconGallery(); return@FrontWidgetTheme }
                if (showIconPopup) { WeatherIconPopup(onDismiss = ::finish); return@FrontWidgetTheme }
                LandingScaffold(
                    state = connectState,
                    onConnect = ::startConnect,
                    onDone = ::finish,
                    onDisconnect = ::disconnect,
                    eventsWindowDays = eventsWindowDays,
                    onWindowSelected = ::setEventsWindow,
                    weatherProvider = weatherProvider,
                    onProviderSelected = ::chooseWeatherProvider,
                    backgroundEnabled = backgroundEnabled,
                    backgroundOpacity = backgroundOpacity,
                    onBackgroundEnabledChange = ::applyBackgroundEnabled,
                    onBackgroundOpacityChange = { backgroundOpacity = it },
                    onBackgroundOpacityCommit = ::commitBackgroundOpacity,
                    calendarTarget = calendarTarget,
                    onCalendarTargetSelected = ::chooseCalendarTarget,
                    notificationAccessGranted = notificationAccessGranted,
                    onEnableNotificationAccess = ::openNotificationAccess,
                    onOpenUrl = ::openUrl
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just toggled Notification access in system settings; reflect it.
        notificationAccessGranted = isNotificationAccessGranted()
    }

    /** True if this app is currently allowed to read notifications (needed for the Clock timer). */
    private fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /**
     * Send the user to grant Notification access. On Android 11+ we deep-link straight to this app's
     * listener detail page; older versions only expose the full listeners list. Both fall back to
     * the list if the specific screen is unavailable on a given OEM build.
     */
    private fun openNotificationAccess() {
        val listSettings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(this, TimerListenerService::class.java).flattenToString()
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else {
            listSettings
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(listSettings)
            } catch (_: Exception) {
            }
        }
    }

    /** Persist the events window, refresh the widget's event feed, and update the UI. */
    private fun setEventsWindow(days: Int) {
        if (days == eventsWindowDays) return
        eventsWindowDays = days
        getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .edit()
            .putInt("events_window_days", days)
            .apply()
        EventsWorker.enqueue(this, force = true)
    }

    /** Toggle the widget's themed background on/off and re-render immediately. */
    private fun applyBackgroundEnabled(enabled: Boolean) {
        backgroundEnabled = enabled
        getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean(FrontWidget.KEY_BG_ENABLED, enabled)
            .apply()
        refreshWidgetContainer()
    }

    /**
     * Persist the current background opacity and re-render. Called when the slider is released
     * (onValueChangeFinished) rather than on every drag tick, so we don't spam widget updates.
     */
    private fun commitBackgroundOpacity() {
        getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .edit()
            .putInt(FrontWidget.KEY_BG_OPACITY, backgroundOpacity)
            .apply()
        refreshWidgetContainer()
    }

    /** Force a guaranteed recomposition so a container setting change lands on every widget. */
    private fun refreshWidgetContainer() {
        lifecycleScope.launch { FrontWidget.forceRefresh(applicationContext) }
    }

    /** Open an external link (donation/source) in the device's default browser. */
    private fun openUrl(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** Choose whether calendar/event taps open the device default calendar or Proton Calendar. */
    private fun chooseCalendarTarget(target: String) {
        if (target == calendarTarget) return
        calendarTarget = target
        getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .edit()
            .putString(FrontWidget.KEY_CALENDAR_TARGET, target)
            .apply()
    }

    /** Persist the chosen weather backend and immediately re-fetch so the change is visible. */
    private fun chooseWeatherProvider(id: String) {
        if (id == weatherProvider) return
        weatherProvider = id
        getSharedPreferences("widget_prefs", MODE_PRIVATE)
            .edit()
            .putString(WeatherProviders.PREF_KEY, id)
            .apply()
        WeatherWorker.enqueue(this, force = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SIGN_IN, false) && connectState == ConnectState.Landing) {
            startConnect()
        }
    }

    private fun startConnect() {
        connectState = ConnectState.Connecting
        authOrchestrator.startLoginWorkflow()
    }

    private fun disconnect() {
        lifecycleScope.launch {
            accountManager.getAccounts().first().forEach { accountManager.removeAccount(it.userId) }
            connectState = ConnectState.Landing
        }
    }

    private fun onAccountReady() {
        connectState = ConnectState.Connected
        refreshWidget()
    }

    private fun refreshWidget() {
        WeatherWorker.enqueue(this, force = true)
        EventsWorker.enqueue(this, force = true)
        lifecycleScope.launch { FrontWidget().updateAll(applicationContext) }
    }

    companion object {
        const val EXTRA_OPEN_SIGN_IN = "open_sign_in"
    }
}

// FrontWidget is GPLv3 (it links the GPLv3 Proton Core SDK). GPLv3 requires offering the complete
// corresponding source, so this must point at the PUBLIC repository. NOTE: the repo must be public
// for this link to resolve for end users (a private repo returns GitHub's 404 page).
private const val SOURCE_URL = "https://github.com/AxlPena/FrontWidget"
private const val DONATION_URL = "https://buymeacoffee.com/alxcodes"

/**
 * App theme that follows the device: Material You dynamic colors on Android 12+ (so the settings
 * screen matches the home-screen wallpaper palette), falling back to the M3 baseline scheme on
 * older devices. Light/dark is driven by the system setting via [isSystemInDarkTheme].
 */
@Composable
private fun FrontWidgetTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun LandingScaffold(
    state: MainActivity.ConnectState = MainActivity.ConnectState.Landing,
    onConnect: () -> Unit,
    onDone: () -> Unit,
    onDisconnect: () -> Unit,
    eventsWindowDays: Int = EventsWorker.DEFAULT_WINDOW_DAYS,
    onWindowSelected: (Int) -> Unit = {},
    weatherProvider: String = WeatherProviders.DEFAULT,
    onProviderSelected: (String) -> Unit = {},
    backgroundEnabled: Boolean = FrontWidget.DEFAULT_BG_ENABLED,
    backgroundOpacity: Int = FrontWidget.DEFAULT_BG_OPACITY,
    onBackgroundEnabledChange: (Boolean) -> Unit = {},
    onBackgroundOpacityChange: (Int) -> Unit = {},
    onBackgroundOpacityCommit: () -> Unit = {},
    calendarTarget: String = FrontWidget.DEFAULT_CALENDAR_TARGET,
    onCalendarTargetSelected: (String) -> Unit = {},
    notificationAccessGranted: Boolean = false,
    onEnableNotificationAccess: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {}
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        // Scrollable so the (always-visible) weather selector and disclaimer never overlap the
        // action buttons on shorter screens or when the Connected state adds extra controls.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.widget_label),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A minimalist clock, weather and calendar for your home screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(40.dp))

            when (state) {
                MainActivity.ConnectState.Landing -> LandingActions(onConnect)
                MainActivity.ConnectState.Connecting -> ConnectingActions()
                MainActivity.ConnectState.Connected -> ConnectedActions(
                    onDone = onDone,
                    onDisconnect = onDisconnect,
                    eventsWindowDays = eventsWindowDays,
                    onWindowSelected = onWindowSelected
                )
            }

            // Weather source is independent of Proton, so it's always adjustable.
            Spacer(Modifier.height(28.dp))
            WeatherProviderSelector(selected = weatherProvider, onSelected = onProviderSelected)

            // Widget appearance: background on/off + opacity. Always adjustable.
            Spacer(Modifier.height(28.dp))
            WidgetBackgroundSelector(
                enabled = backgroundEnabled,
                opacity = backgroundOpacity,
                onEnabledChange = onBackgroundEnabledChange,
                onOpacityChange = onBackgroundOpacityChange,
                onOpacityCommit = onBackgroundOpacityCommit
            )

            // Which app calendar/event taps open (device default vs Proton).
            Spacer(Modifier.height(28.dp))
            CalendarTargetSelector(selected = calendarTarget, onSelected = onCalendarTargetSelected)

            // Notification access gate for the running-timer display on the widget.
            Spacer(Modifier.height(28.dp))
            NotificationAccessSelector(
                granted = notificationAccessGranted,
                onEnable = onEnableNotificationAccess
            )

            // How to use & feature overview: onboarding for new users, collapsed by default.
            Spacer(Modifier.height(28.dp))
            HowToUseSection()

            // About: donation, source, and open-source attribution (GPLv3 obligations).
            Spacer(Modifier.height(28.dp))
            AboutSection(onOpenUrl = onOpenUrl)

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.widget_label) +
                    " is an independent app and is not affiliated with " +
                    "Proton AG. Sign-in is handled securely by Proton.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun BrandMark() {
    // Captured before Canvas: the draw lambda runs in DrawScope, not a @Composable scope.
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(accent.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(44.dp)) {
            val stroke = size.minDimension * 0.09f
            val corner = CornerRadius(size.minDimension * 0.16f, size.minDimension * 0.16f)
            val top = size.height * 0.18f
            val body = Rect(0f, top, size.width, size.height)

            // Calendar body outline.
            drawRoundRect(
                color = accent,
                topLeft = body.topLeft,
                size = body.size,
                cornerRadius = corner,
                style = Stroke(width = stroke)
            )
            // Header bar.
            drawRoundRect(
                color = accent,
                topLeft = body.topLeft,
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.24f),
                cornerRadius = corner
            )
            // Binder rings.
            val ringY = top - size.height * 0.04f
            drawLine(accent, Offset(size.width * 0.3f, size.height * 0.02f), Offset(size.width * 0.3f, ringY), strokeWidth = stroke)
            drawLine(accent, Offset(size.width * 0.7f, size.height * 0.02f), Offset(size.width * 0.7f, ringY), strokeWidth = stroke)
            // A "day" dot.
            drawCircle(accent, radius = size.minDimension * 0.09f, center = Offset(size.width * 0.5f, size.height * 0.62f))
        }
    }
}

@Composable
private fun LandingActions(onConnect: () -> Unit) {
    Button(
        onClick = onConnect,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text("Connect Proton Calendar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Sign in with your Proton account to show your events.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ConnectingActions() {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Opening secure sign-in…",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp
    )
}

@Composable
private fun ConnectedActions(
    onDone: () -> Unit,
    onDisconnect: () -> Unit,
    eventsWindowDays: Int,
    onWindowSelected: (Int) -> Unit
) {
    Text(
        text = "Connected",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Your Proton Calendar is linked. Events will appear in the widget.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
    Spacer(Modifier.height(24.dp))
    EventWindowSelector(selectedDays = eventsWindowDays, onSelected = onWindowSelected)
    Spacer(Modifier.height(28.dp))
    Button(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onDisconnect) {
        Text("Disconnect", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeatherProviderSelector(selected: String, onSelected: (String) -> Unit) {
    Text(
        text = "Weather source",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(WeatherProviders.OPEN_WEATHER, WeatherProviders.OPEN_METEO).forEach { id ->
            val isSel = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelected(id) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = WeatherProviders.label(id),
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun WidgetBackgroundSelector(
    enabled: Boolean,
    opacity: Int,
    onEnabledChange: (Boolean) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onOpacityCommit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Widget background",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (enabled) "Themed surface behind the widget" else "Frameless (text on wallpaper)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }

    if (enabled) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Opacity", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(text = "$opacity%", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        }
        Slider(
            value = opacity.toFloat(),
            onValueChange = { onOpacityChange(it.roundToInt()) },
            onValueChangeFinished = onOpacityCommit,
            valueRange = 0f..100f
        )
    }
}

private const val OSS_ATTRIBUTION =
    "At a Glance is free software licensed under the GNU General Public License v3.0 (GPLv3), " +
    "because it links the Proton Core Android SDK. The complete corresponding source code is " +
    "available at the repository above.\n\n" +
    "Third-party components:\n" +
    "• Proton Core Android (me.proton.core) — GPL-3.0\n" +
    "• GopenPGP, go-srp — MIT\n" +
    "• AndroidX (Core, Activity, Lifecycle, Compose, Glance, Room, WorkManager) — Apache-2.0\n" +
    "• Dagger / Hilt — Apache-2.0\n" +
    "• Retrofit, OkHttp, Gson (Square) — Apache-2.0\n" +
    "• kotlinx (Coroutines, Serialization) — Apache-2.0\n" +
    "• Timber — Apache-2.0\n" +
    "• Google Play Services Location — proprietary (Google APIs Terms of Service)"

private val FEATURE_LINES = listOf(
    "Clock & date, always up to date",
    "Local weather (OpenWeather or Open-Meteo)",
    "Proton Calendar events with ‹ › navigation",
    "Next alarm — updates the moment it changes",
    "Live system-timer countdown on the widget",
    "Adjustable background, opacity & resizable layout"
)

private val HOW_TO_STEPS = listOf(
    "Add it: long-press the home screen → Widgets → At a Glance, then drop it in place.",
    "Optional: tap Connect Proton Calendar to show your events.",
    "Pick a weather source and allow location for local conditions.",
    "Enable Notification access to show running timers.",
    "Tap a section to open Clock or Calendar; use ‹ › to browse events.",
    "Resize by long-pressing the widget and dragging the handles."
)

@Composable
private fun HowToUseSection() {
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = "How to use",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(6.dp))

    AboutRow(
        title = "How to use & features",
        subtitle = if (expanded) "Tap to hide" else "Tap to view a quick guide",
        onClick = { expanded = !expanded }
    )

    if (expanded) {
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp)
        ) {
            Text(
                text = "Features",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            FEATURE_LINES.forEach { line ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = "•  ",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Getting started",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            HOW_TO_STEPS.forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = "${index + 1}.  ",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = step,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection(onOpenUrl: (String) -> Unit) {
    var showLicenses by remember { mutableStateOf(false) }

    Text(
        text = "About",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(6.dp))

    AboutRow(
        title = "Support development",
        subtitle = "Buy me a coffee",
        onClick = { onOpenUrl(DONATION_URL) }
    )
    AboutRow(
        title = "Source code",
        subtitle = "GPLv3 — view on GitHub",
        onClick = { onOpenUrl(SOURCE_URL) }
    )
    AboutRow(
        title = "Open-source licenses",
        subtitle = if (showLicenses) "Tap to hide" else "Tap to view attribution",
        onClick = { showLicenses = !showLicenses }
    )

    if (showLicenses) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = OSS_ATTRIBUTION,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp)
        )
    }
}

@Composable
private fun AboutRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Text(
            text = "›",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 20.sp
        )
    }
}

@Composable
private fun CalendarTargetSelector(selected: String, onSelected: (String) -> Unit) {
    Text(
        text = "Open calendar & events in",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val options = listOf(
            FrontWidget.CALENDAR_TARGET_DEVICE to "Device default",
            FrontWidget.CALENDAR_TARGET_PROTON to "Proton Calendar"
        )
        options.forEach { (id, label) ->
            val isSel = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelected(id) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun NotificationAccessSelector(granted: Boolean, onEnable: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = "Show running timers",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (granted) {
                    "Enabled — the widget shows a live countdown for your Clock timers."
                } else {
                    "Needs Notification access so the widget can read the Clock's timer."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        // Both states deep-link to the same setting: "Enable" to grant, "Manage" to revoke/inspect.
        if (granted) {
            TextButton(onClick = onEnable) {
                Text("Manage", color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Button(
                onClick = onEnable,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Enable", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EventWindowSelector(selectedDays: Int, onSelected: (Int) -> Unit) {
    Text(
        text = "Show events for the next",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(7, 14, 28).forEach { days ->
            val selected = days == selectedDays
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelected(days) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$days days",
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
