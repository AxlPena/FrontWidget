package com.saveory.frontwidget.data

/**
 * Which weather backend the widget fetches from. Both are queried with the device's own GPS
 * coordinates and return the current temperature in Celsius, so switching providers is a pure
 * A/B swap with no other behavior change.
 */
object WeatherProviders {
    const val PREF_KEY = "weather_provider"

    const val OPEN_WEATHER = "openweather"
    const val OPEN_METEO = "openmeteo"

    const val DEFAULT = OPEN_WEATHER

    /** Short label for the settings toggle. */
    fun label(id: String): String = when (id) {
        OPEN_METEO -> "Open-Meteo"
        else -> "OpenWeather"
    }
}
