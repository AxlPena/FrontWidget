package com.saveory.frontwidget.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.util.UUID

/**
 * Encrypted at-rest store for the phone's Monarch session — the piece that replaces the laptop
 * tunnel (see docs/weekly-spend-widget.md, "Can the widget authenticate to Monarch itself?").
 *
 * Mirrors the desktop server's `secure_session.save_session_blob` shape so the two agree on what a
 * session is: `{ auth_mode, token?, device_uuid?, cookies? }`. Backed by EncryptedSharedPreferences
 * (AES-256 GCM, Android Keystore master key) — never the world-readable widget_prefs file.
 *
 * Login (an Activity) writes here; the WorkManager sync only ever READS. The `device_uuid` is
 * generated once and reused: Monarch rejects a token presented with a different device-uuid than
 * the one used at login, so it must be stable across the token's whole life.
 */
object MonarchSessionStore {

    private const val TAG = "MonarchSession"
    private const val FILE = "monarch_session"
    private const val KEY_BLOB = "session_blob"
    private const val KEY_DEVICE_UUID = "device_uuid"

    enum class AuthMode { TOKEN, COOKIE }

    data class Session(
        val authMode: AuthMode,
        val token: String?,
        val deviceUuid: String,
        val cookies: Map<String, String>
    )

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Stable per-install device id. The SAME value must be presented at login and on every later
     * token call or Monarch invalidates the token. Generated once, then persisted.
     */
    fun deviceUuid(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_UUID, null)?.let { if (it.isNotBlank()) return it }
        val fresh = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_UUID, fresh).apply()
        return fresh
    }

    /** Token mode: long-lived token + the device-uuid it was minted with. */
    fun saveToken(context: Context, token: String, deviceUuid: String) {
        write(
            context,
            JSONObject()
                .put("auth_mode", "token")
                .put("token", token)
                .put("device_uuid", deviceUuid)
        )
    }

    /** Cookie mode (CAPTCHA fallback): session_id + csrftoken captured from a signed-in WebView. */
    fun saveCookies(
        context: Context,
        cookies: Map<String, String>,
        token: String?,
        deviceUuid: String
    ) {
        val cookieObj = JSONObject()
        cookies.forEach { (k, v) -> cookieObj.put(k, v) }
        val blob = JSONObject()
            .put("auth_mode", "cookie")
            .put("device_uuid", deviceUuid)
            .put("cookies", cookieObj)
        if (!token.isNullOrBlank()) blob.put("token", token)
        write(context, blob)
    }

    private fun write(context: Context, blob: JSONObject) {
        prefs(context).edit().putString(KEY_BLOB, blob.toString()).apply()
        Log.d(TAG, "Saved session (auth_mode=${blob.optString("auth_mode")})")
    }

    fun load(context: Context): Session? {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val cookies = mutableMapOf<String, String>()
            o.optJSONObject("cookies")?.let { c ->
                val keys = c.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    cookies[k] = c.optString(k)
                }
            }
            val mode = if (o.optString("auth_mode") == "cookie") AuthMode.COOKIE else AuthMode.TOKEN
            val token = o.optString("token").takeIf { it.isNotBlank() }
            val uuid = o.optString("device_uuid").takeIf { it.isNotBlank() } ?: deviceUuid(context)
            when {
                mode == AuthMode.TOKEN && token == null -> null
                mode == AuthMode.COOKIE && cookies.isEmpty() -> null
                else -> Session(mode, token, uuid, cookies)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse session blob: ${e.message}")
            null
        }
    }

    fun hasSession(context: Context): Boolean = load(context) != null

    /** Sign out. Keeps the device-uuid so a re-login reuses the same device identity. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_BLOB).apply()
    }
}
