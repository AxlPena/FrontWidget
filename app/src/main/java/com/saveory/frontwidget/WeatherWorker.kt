package com.saveory.frontwidget

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.saveory.frontwidget.data.Constants
import com.saveory.frontwidget.data.WeatherApi
import com.saveory.frontwidget.data.WeatherProviders
import com.saveory.frontwidget.data.WeatherStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

import kotlin.time.Duration.Companion.seconds

class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val TAG = "WeatherWorker"

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting weather update...")
        return try {
            val prefs = applicationContext.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)

            // Prefer a fresh high-accuracy device GPS fix; fall back to last known.
            val location: Location? = withTimeoutOrNull(10.seconds) {
                getCurrentLocation(fusedClient) ?: getLastLocation(fusedClient)
            }

            Log.d(TAG, "Location found: ${location?.latitude}, ${location?.longitude}")

            // Strictly device-based: if no GPS fix, reuse the last real fix; never fake a location.
            val cachedLat = prefs.getFloat("location_lat", Float.NaN)
            val cachedLon = prefs.getFloat("location_lon", Float.NaN)
            val lat = location?.latitude ?: cachedLat.takeIf { !it.isNaN() }?.toDouble()
            val lon = location?.longitude ?: cachedLon.takeIf { !it.isNaN() }?.toDouble()

            if (lat == null || lon == null) {
                Log.w(TAG, "No device GPS location available; skipping update.")
                return Result.retry()
            }

            // Use system Geocoder for more accurate Locality and Region (State/Prefecture)
            val address = getAddress(lat, lon)
            Log.d(TAG, "Full Address: $address")

            val retrofit = Retrofit.Builder()
                .baseUrl(Constants.WEATHER_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val api = retrofit.create(WeatherApi::class.java)

            // Fetch from whichever backend the user selected (defaults to OpenWeather). Both take
            // the same device coordinates and return Celsius, so this is a clean A/B swap.
            val provider = prefs.getString(WeatherProviders.PREF_KEY, WeatherProviders.DEFAULT)
                ?: WeatherProviders.DEFAULT
            val reading = when (provider) {
                WeatherProviders.OPEN_METEO -> fetchOpenMeteo(api, lat, lon)
                else -> fetchOpenWeather(api, lat, lon)
            }
            if (reading.tempC.isNaN()) {
                Log.w(TAG, "Provider '$provider' returned no usable reading; will retry.")
                return Result.retry()
            }
            Log.d(TAG, "[$provider] ${reading.tempC}C ${reading.condition} (${reading.status.key}, day=${reading.isDay})")

            // All location fields come from the SAME coordinates. If the system Geocoder
            // is missing a field, fall back to the provider's own values (same coords),
            // never a hardcoded place, so every part stays consistent.
            val locality = (address?.locality
                ?: address?.subLocality
                ?: address?.subAdminArea
                ?: address?.adminArea
                ?: reading.fallbackName.takeIf { it.isNotBlank() }
                ?: "Unknown")
            val region = (address?.adminArea?.takeIf { !it.equals(locality, ignoreCase = true) } ?: "")
            val country = (address?.countryCode
                ?: reading.fallbackCountry.takeIf { it.isNotBlank() }
                ?: "")

            Log.d(TAG, "Resolved Address: $locality, $region, $country")

            // Save weather to SharedPreferences for the widget to read
            prefs.edit().apply {
                // Store raw Celsius; the widget formats to °C/°F per device settings.
                putFloat("weather_temp_c", reading.tempC)
                putString("weather_cond", reading.condition)
                putString("weather_locality", locality)
                putString("weather_region", region)
                putString("weather_country", country)
                // Canonical status key + day/night pick the exact icon in the widget.
                putString("weather_status", reading.status.key)
                putBoolean("weather_is_day", reading.isDay)
                // Sun times (epoch millis) let the widget re-decide day/night at render time.
                putLong("weather_sunrise_ms", reading.sunriseMs)
                putLong("weather_sunset_ms", reading.sunsetMs)
                putFloat("location_lat", lat.toFloat())
                putFloat("location_lon", lon.toFloat())
                putInt("location_tz_offset", reading.tzOffsetSeconds)
                putString("location_tz_id", reading.tzId)
                // Clear stale keys from older builds so no mismatched data lingers.
                remove("weather_city")
                remove("weather_state")
                remove("weather_temp")
                apply()
            }

            // Update all widgets
            FrontWidget().updateAll(applicationContext)
            Log.d(TAG, "Widgets updated successfully")
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Weather update failed", e)
            Result.retry()
        }
    }

    /** Provider-agnostic current-conditions reading. Temperature is always Celsius. */
    private data class WeatherReading(
        val tempC: Float,
        val condition: String,
        val status: WeatherStatus,
        val isDay: Boolean,
        val fallbackName: String,
        val fallbackCountry: String,
        val tzOffsetSeconds: Int,
        // IANA zone id for the location (e.g. "Europe/Paris") when the provider supplies it;
        // empty when only the raw offset is known. The widget prefers this for DST-correct time.
        val tzId: String,
        // Today's sunrise/sunset in epoch millis (0 if the provider didn't return them). The
        // widget uses these to flip day/night art at render time as the sun actually rises/sets.
        val sunriseMs: Long,
        val sunsetMs: Long
    )

    /** True if [nowMs] falls between sunrise and sunset; null if sun times are unavailable. */
    private fun isDaytime(sunriseMs: Long, sunsetMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean? {
        if (sunriseMs <= 0L || sunsetMs <= 0L) return null
        return nowMs in sunriseMs until sunsetMs
    }

    private suspend fun fetchOpenWeather(api: WeatherApi, lat: Double, lon: Double): WeatherReading {
        val response = api.getCurrentWeather(lat, lon, Constants.OPEN_WEATHER_API_KEY)
        val weather0 = response.weather.firstOrNull()
        val baseStatus = weather0?.id?.let { WeatherStatus.fromOwmId(it) } ?: WeatherStatus.UNKNOWN
        // OWM returns wind.speed in m/s with units=metric. Upgrade to tropical storm/hurricane
        // when sustained wind crosses the thresholds (no OWM condition code exists for those).
        val status = WeatherStatus.fromWind(baseStatus, response.wind?.speed?.toDouble())
        // Prefer OWM's specific description ("overcast clouds", "light rain") over the coarse
        // group ("Clouds", "Rain") so the status reads accurately; title-case for display. When
        // the wind override kicks in, show the cyclone label instead so text matches the icon.
        val condition = if (status != baseStatus) {
            status.label
        } else {
            weather0?.description
                ?.split(" ")
                ?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                ?.takeIf { it.isNotBlank() }
                ?: weather0?.main
                ?: "Unknown"
        }
        // Prefer real sun times; fall back to OWM's "d"/"n" icon suffix if they're missing.
        val sunriseMs = (response.sys.sunrise ?: 0L) * 1000L
        val sunsetMs = (response.sys.sunset ?: 0L) * 1000L
        val isDay = isDaytime(sunriseMs, sunsetMs) ?: (weather0?.icon?.endsWith("n")?.not() ?: true)
        return WeatherReading(
            tempC = response.main.temp,
            condition = condition,
            status = status,
            isDay = isDay,
            fallbackName = response.name,
            fallbackCountry = response.sys.country,
            tzOffsetSeconds = response.timezone,
            // OpenWeather only returns the raw offset, not an IANA zone id.
            tzId = "",
            sunriseMs = sunriseMs,
            sunsetMs = sunsetMs
        )
    }

    private suspend fun fetchOpenMeteo(api: WeatherApi, lat: Double, lon: Double): WeatherReading {
        val response = api.getOpenMeteoWeather(lat, lon)
        val current = response.current
        val baseStatus = WeatherStatus.fromWmoCode(current?.weather_code ?: -1)
        // wind_speed_10m is m/s (wind_speed_unit=ms). Upgrade to tropical storm/hurricane past the
        // thresholds, since WMO codes have no tropical-cyclone value.
        val status = WeatherStatus.fromWind(baseStatus, current?.wind_speed_10m?.toDouble())
        // timeformat=unixtime -> sunrise/sunset are UTC epoch seconds; [0] is today.
        val sunriseMs = (response.daily?.sunrise?.firstOrNull() ?: 0L) * 1000L
        val sunsetMs = (response.daily?.sunset?.firstOrNull() ?: 0L) * 1000L
        // Prefer real sun times; fall back to Open-Meteo's current.is_day flag (1=day, 0=night).
        val isDay = isDaytime(sunriseMs, sunsetMs) ?: ((current?.is_day ?: 1) == 1)
        return WeatherReading(
            tempC = current?.temperature_2m ?: Float.NaN,
            condition = status.label,
            status = status,
            isDay = isDay,
            // Open-Meteo doesn't return place names; the system Geocoder supplies those.
            fallbackName = "",
            fallbackCountry = "",
            // With timezone=auto the response carries the location's zone id + current offset.
            tzOffsetSeconds = response.utc_offset_seconds ?: 0,
            tzId = response.timezone ?: "",
            sunriseMs = sunriseMs,
            sunsetMs = sunsetMs
        )
    }

    private suspend fun getAddress(lat: Double, lon: Double): Address? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(applicationContext, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        continuation.resume(addresses.firstOrNull())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(client: FusedLocationProviderClient): Location? =
        suspendCancellableCoroutine { continuation ->
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(60_000L)
                .build()

            client.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    @SuppressLint("MissingPermission")
    private suspend fun getLastLocation(client: FusedLocationProviderClient): Location? =
        suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    companion object {
        fun enqueue(context: Context, force: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<WeatherWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "weather_update",
                if (force) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            // Also enqueue a one-time work for immediate update
            if (force) {
                val oneTimeRequest = OneTimeWorkRequestBuilder<WeatherWorker>().build()
                WorkManager.getInstance(context).enqueue(oneTimeRequest)
            }
        }
    }
}
