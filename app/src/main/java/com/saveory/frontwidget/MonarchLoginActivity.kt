package com.saveory.frontwidget

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.saveory.frontwidget.data.MonarchApi
import com.saveory.frontwidget.data.MonarchSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Monarch sign-in screen (see docs/weekly-spend-widget.md, "Can the widget authenticate to
 * Monarch itself?"). Login belongs in an Activity, never the ring: OTP/TOTP/CAPTCHA can't live in a
 * 10s worker. The worker only *uses* the session this screen stores.
 *
 * Styled to match the app's "Connect Proton Calendar" landing flow — same [FrontWidgetTheme] (dynamic
 * Material You), [BrandMark], bold title + subtitle, and the full-width rounded primary button — so
 * connecting Monarch feels like the same product as connecting Proton.
 *
 * Primary path is native email/password -> token (the documented `monarchmoneycommunity` flow),
 * with an OTP/TOTP field shown on demand. Only when Monarch returns CAPTCHA_REQUIRED do we fall
 * back to a WebView of app.monarch.com and capture the session cookies (the one thing a Custom Tab
 * cannot do — a Custom Tab's cookie jar is out of this process's reach).
 */
class MonarchLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrontWidgetTheme {
                MonarchLoginScreen(
                    onDone = {
                        WeeklySpendWorker.syncNow(applicationContext)
                        finish()
                    }
                )
            }
        }
    }
}

private enum class Step { CREDENTIALS, EMAIL_OTP, MFA, CAPTCHA }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MonarchLoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(Step.CREDENTIALS) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt(emailOtp: String?, totp: String?) {
        loading = true
        error = null
        scope.launch {
            val deviceUuid = MonarchSessionStore.deviceUuid(context)
            val outcome = withContext(Dispatchers.IO) {
                MonarchApi.login(email.trim(), password, deviceUuid, emailOtp, totp)
            }
            loading = false
            when (outcome) {
                is MonarchApi.LoginOutcome.Success -> {
                    withContext(Dispatchers.IO) {
                        MonarchSessionStore.saveToken(context, outcome.token, deviceUuid)
                    }
                    onDone()
                }
                MonarchApi.LoginOutcome.EmailOtpRequired -> { code = ""; step = Step.EMAIL_OTP }
                MonarchApi.LoginOutcome.MfaRequired -> { code = ""; step = Step.MFA }
                MonarchApi.LoginOutcome.CaptchaRequired -> { step = Step.CAPTCHA }
                is MonarchApi.LoginOutcome.Failed -> { error = outcome.message }
            }
        }
    }

    if (step == Step.CAPTCHA) {
        CaptchaScreen(
            onCookies = { cookies ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        MonarchSessionStore.saveCookies(
                            context,
                            cookies,
                            token = null,
                            deviceUuid = MonarchSessionStore.deviceUuid(context)
                        )
                    }
                    onDone()
                }
            }
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
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
                text = "Connect Monarch",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (step) {
                    Step.EMAIL_OTP -> "Enter the one-time code Monarch emailed you."
                    Step.MFA -> "Enter your authenticator (TOTP) code."
                    else -> "Sign in with your Monarch account to track this week's Groceries + Fun spend."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(40.dp))

            if (step == Step.CREDENTIALS) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !loading,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .autofill(
                            autofillTypes = listOf(
                                AutofillType.Username,
                                AutofillType.EmailAddress
                            ),
                            onFill = { email = it }
                        )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .autofill(
                            autofillTypes = listOf(AutofillType.Password),
                            onFill = { password = it }
                        )
                )
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(if (step == Step.MFA) "Authenticator code" else "Email code") },
                    singleLine = true,
                    enabled = !loading,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))

            if (loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Button(
                    onClick = {
                        when (step) {
                            Step.CREDENTIALS -> attempt(emailOtp = null, totp = null)
                            Step.EMAIL_OTP -> attempt(emailOtp = code.trim(), totp = null)
                            Step.MFA -> attempt(emailOtp = null, totp = code.trim())
                            Step.CAPTCHA -> {}
                        }
                    },
                    enabled = when (step) {
                        Step.CREDENTIALS -> email.isNotBlank() && password.isNotBlank()
                        else -> code.isNotBlank()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        if (step == Step.CREDENTIALS) "Sign in" else "Verify",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (step == Step.CREDENTIALS) {
                        "Your Monarch login is stored encrypted on this device."
                    } else {
                        "Didn't get a code? Go back and sign in again."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                if (step != Step.CREDENTIALS) {
                    TextButton(onClick = {
                        step = Step.CREDENTIALS
                        code = ""
                        error = null
                    }) {
                        Text("Back", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * CAPTCHA fallback: sign in to app.monarch.com in an in-process WebView (OTP/CAPTCHA render in the
 * page), then read the session cookies. Only a WebView — not a Custom Tab — can call
 * CookieManager.getCookie for the host. Fires [onCookies] once both required cookies exist.
 */
@Composable
private fun CaptchaScreen(onCookies: (Map<String, String>) -> Unit) {
    var delivered by remember { mutableStateOf(false) }

    fun capture(): Map<String, String>? {
        val cm = CookieManager.getInstance()
        val merged = mutableMapOf<String, String>()
        for (host in listOf("https://app.monarch.com", "https://api.monarch.com")) {
            val raw = cm.getCookie(host) ?: continue
            for (pair in raw.split(";")) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val k = pair.substring(0, idx).trim()
                val v = pair.substring(idx + 1).trim()
                if (k.isNotEmpty()) merged[k] = v
            }
        }
        return if (merged.containsKey("session_id") && merged.containsKey("csrftoken")) merged else null
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Text(
                text = "Sign in to Monarch",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        // First-party Monarch cookies only; the auth flow needs no third-party cookies.
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        settings.javaScriptEnabled = true // required by the Monarch login SPA
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            // Keep the authenticated WebView pinned to Monarch origins so a redirect
                            // or injected navigation can't run in-session on an arbitrary host.
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val host = request?.url?.host ?: return true
                                val allowed = host == "monarch.com" || host.endsWith(".monarch.com")
                                return !allowed // true = we handled (blocked) the navigation
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (delivered) return
                                capture()?.let { delivered = true; onCookies(it) }
                            }
                        }
                        loadUrl("https://app.monarch.com/login")
                    }
                }
            )
            // Manual fallback if cookies land without another onPageFinished (SPA navigation).
            TextButton(
                onClick = { if (!delivered) capture()?.let { delivered = true; onCookies(it) } },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Text("I've signed in", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Wire a text field into the Android Autofill framework so password managers (Google Autofill,
 * 1Password, Bitwarden, …) recognise it and offer to fill/save credentials. Uses the classic
 * [AutofillNode] API because the newer semantics-based `contentType` only exists in Compose UI
 * 1.8+, and we're pinned to the 1.6 line (Compose BOM 2024.06) to keep the Proton login graph
 * intact. Register the node's bounds and request autofill when the field gains focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.autofill(
    autofillTypes: List<AutofillType>,
    onFill: (String) -> Unit
): Modifier = composed {
    val autofill = LocalAutofill.current
    val node = AutofillNode(onFill = onFill, autofillTypes = autofillTypes)
    LocalAutofillTree.current += node

    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) requestAutofillForNode(node)
                else cancelAutofillForNode(node)
            }
        }
}
