package com.saveory.frontwidget.data

import java.util.Locale

/**
 * Resolves the temperature unit the user prefers and formats values accordingly.
 *
 * Weather is always fetched and stored in Celsius (the single source of truth);
 * the display unit is decided here so changing the device setting only requires a
 * widget refresh, not a re-fetch.
 */
object TemperatureUnit {

    private val FAHRENHEIT_REGIONS = setOf("US", "BS", "BZ", "KY", "PW", "FM", "MH", "LR")

    /** True if the device prefers Celsius, false for Fahrenheit. */
    fun prefersCelsius(): Boolean {
        // Android 14+ exposes an explicit regional temperature preference through the
        // resolved FORMAT locale's Unicode "mu" (measurement unit) extension.
        when (Locale.getDefault(Locale.Category.FORMAT).getUnicodeLocaleType("mu")) {
            "celsius", "kelvin" -> return true
            "fahrenhe" -> return false
        }
        // Older versions have no explicit setting: infer from region. Fahrenheit is
        // used by the US and a few territories; everyone else uses Celsius.
        val region = Locale.getDefault().country.uppercase(Locale.ROOT)
        return region !in FAHRENHEIT_REGIONS
    }

    /** Formats a Celsius value for display using the preferred unit (e.g. "21°C" / "70°F"). */
    fun format(celsius: Float, useCelsius: Boolean = prefersCelsius()): String =
        if (useCelsius) "${Math.round(celsius)}°C"
        else "${Math.round(celsius * 9f / 5f + 32f)}°F"
}
