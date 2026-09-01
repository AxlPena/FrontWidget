package com.saveory.frontwidget.data

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Low-level Monarch web API client for `https://api.monarch.com`, ported from the
 * `monarchmoneycommunity` library and the MCP server's `monarch_auth.py` so the phone speaks the
 * exact same protocol the desktop server does:
 *
 *  - `POST /auth/login/` with `{ username, password, supports_mfa, supports_email_otp,
 *    supports_recaptcha, trusted_device, email_otp?, totp? }`. `trusted_device=true` is REQUIRED to
 *    get a long-lived token (`tokenExpiration=null`); a JWT-style two-dot token is refused.
 *  - `POST /graphql` with the same operations the tools use (GetCategories / GetTransactionsList).
 *
 * Every request carries the web headers Monarch validates: a stable `device-uuid`, `monarch-client`
 * / `monarch-client-version`, and `Origin`. Token mode adds `Authorization: Token <token>`; cookie
 * mode (the CAPTCHA fallback) sends the `Cookie` header + `X-Csrftoken` instead.
 */
object MonarchApi {

    private const val TAG = "MonarchApi"
    private const val BASE = "https://api.monarch.com"
    private const val LOGIN_URL = "$BASE/auth/login/"
    private const val GRAPHQL_URL = "$BASE/graphql"

    // Captured from the Monarch web app. If logins / GraphQL start returning 403 "app update
    // required", recapture from DevTools (any api.monarch.com request -> header
    // "monarch-client-version") and bump this. Matches the MCP server's monarch_auth.py.
    const val CLIENT_VERSION = "v1.0.1668"
    private const val CLIENT = "monarch-core-web-app-graphql"
    private const val ORIGIN = "https://app.monarch.com"
    private const val USER_AGENT = "FrontWidget (Android; monarchmoneycommunity-compatible)"

    // Two round trips (categories + transactions) on cell; the spec flags 10s as too tight.
    private const val TIMEOUT_MS = 25_000

    /** Monarch rejected the session (401/403, or a not-authenticated GraphQL error). Widget -> Sign in. */
    class AuthException(message: String) : Exception(message)

    sealed interface LoginOutcome {
        data class Success(val token: String) : LoginOutcome
        object EmailOtpRequired : LoginOutcome
        object MfaRequired : LoginOutcome
        object CaptchaRequired : LoginOutcome
        data class Failed(val message: String) : LoginOutcome
    }

    /**
     * Attempts a password login. On the first call pass just email+password; if Monarch answers
     * with [EmailOtpRequired] or [MfaRequired], call again with the corresponding code. [CaptchaRequired]
     * means fall back to the WebView cookie path. Blocking I/O — call off the main thread.
     */
    fun login(
        email: String,
        password: String,
        deviceUuid: String,
        emailOtp: String? = null,
        totp: String? = null
    ): LoginOutcome {
        val payload = JSONObject()
            .put("username", email)
            .put("password", password)
            .put("supports_mfa", true)
            .put("supports_email_otp", true)
            .put("supports_recaptcha", true)
            .put("trusted_device", true)
        if (!emailOtp.isNullOrBlank()) payload.put("email_otp", emailOtp)
        if (!totp.isNullOrBlank()) payload.put("totp", totp)

        val conn = (URL(LOGIN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            applyBaseHeaders(this, deviceUuid)
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = readBody(conn)
            val json = try {
                if (body.isNotBlank()) JSONObject(body) else JSONObject()
            } catch (e: Exception) {
                JSONObject().put("detail", body)
            }

            if (status == 200) {
                val token = json.optString("token").takeIf { it.isNotBlank() }
                    ?: return LoginOutcome.Failed("Login response did not include a token")
                if (token.count { it == '.' } == 2) {
                    return LoginOutcome.Failed("Received a short-lived (JWT) token; refusing.")
                }
                val exp = json.opt("tokenExpiration")
                if (exp != null && exp != JSONObject.NULL && exp.toString() != "null") {
                    return LoginOutcome.Failed("Short-lived token (tokenExpiration=$exp); expected null.")
                }
                return LoginOutcome.Success(token)
            }

            val errorCode = json.optString("error_code")
            val detail = json.optString("detail")
            val combined = "$detail $errorCode"
            when {
                status == 403 && errorCode == "CAPTCHA_REQUIRED" -> LoginOutcome.CaptchaRequired
                errorCode == "EMAIL_OTP_REQUIRED" ||
                    (status == 403 && EMAIL_OTP_RE.containsMatchIn(combined)) -> LoginOutcome.EmailOtpRequired
                errorCode == "MFA_REQUIRED" ||
                    (status == 403 && MFA_RE.containsMatchIn(combined)) -> LoginOutcome.MfaRequired
                else -> LoginOutcome.Failed(
                    detail.ifBlank { errorCode.ifBlank { "Sign-in failed (HTTP $status)" } }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "login failed: ${e.message}")
            LoginOutcome.Failed(e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Runs a GraphQL operation with the stored session. Returns the `data` object. Throws
     * [AuthException] when Monarch rejects the session (so the caller can flip auth_ok=false rather
     * than retry forever), and a plain exception for transient/server errors.
     */
    fun graphql(
        session: MonarchSessionStore.Session,
        operationName: String,
        query: String,
        variables: JSONObject
    ): JSONObject {
        val requestBody = JSONObject()
            .put("operationName", operationName)
            .put("query", query)
            .put("variables", variables)

        val conn = (URL(GRAPHQL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            applyBaseHeaders(this, session.deviceUuid)
            setRequestProperty("Content-Type", "application/json")
            applyAuth(this, session)
        }
        try {
            conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status == 401 || status == 403) throw AuthException("HTTP $status")
            val body = readBody(conn)
            if (status !in 200..299) throw IllegalStateException("HTTP $status: ${body.take(200)}")
            val json = JSONObject(body)
            json.optJSONArray("errors")?.let { errs ->
                if (errs.length() > 0) {
                    val msg = errs.optJSONObject(0)?.optString("message").orEmpty().ifBlank { "GraphQL error" }
                    // An auth failure can surface as HTTP 200 with an errors[] payload.
                    if (msg.contains("authenticat", true) || msg.contains("login", true) ||
                        msg.contains("credential", true)
                    ) {
                        throw AuthException(msg)
                    }
                    throw IllegalStateException(msg)
                }
            }
            return json.optJSONObject("data") ?: throw IllegalStateException("No data in GraphQL response")
        } finally {
            conn.disconnect()
        }
    }

    private fun applyBaseHeaders(conn: HttpURLConnection, deviceUuid: String) {
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Origin", ORIGIN)
        conn.setRequestProperty("device-uuid", deviceUuid)
        conn.setRequestProperty("monarch-client", CLIENT)
        conn.setRequestProperty("monarch-client-version", CLIENT_VERSION)
        conn.setRequestProperty("User-Agent", USER_AGENT)
    }

    private fun applyAuth(conn: HttpURLConnection, session: MonarchSessionStore.Session) {
        if (session.authMode == MonarchSessionStore.AuthMode.COOKIE && session.cookies.isNotEmpty()) {
            conn.setRequestProperty(
                "Cookie",
                session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            )
            conn.setRequestProperty("Referer", "$ORIGIN/")
            session.cookies["csrftoken"]?.let { conn.setRequestProperty("X-Csrftoken", it) }
        } else if (session.token != null) {
            conn.setRequestProperty("Authorization", "Token ${session.token}")
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val ok = conn.responseCode in 200..299
        val stream = if (ok) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }

    private val EMAIL_OTP_RE = Regex("email.*(?:code|otp)|(?:code|otp).*email", RegexOption.IGNORE_CASE)
    private val MFA_RE = Regex("mfa|multi.?factor|two.?factor|2fa|totp", RegexOption.IGNORE_CASE)
}
