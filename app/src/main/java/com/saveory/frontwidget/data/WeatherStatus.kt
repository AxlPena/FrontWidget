package com.saveory.frontwidget.data

/**
 * Canonical weather condition: the union of every OpenWeatherMap condition id and every
 * Open-Meteo WMO interpretation code. Each status has a stable [key] used to resolve its
 * day/night icon drawable (res/drawable/wx_<key>_day.xml and wx_<key>_night.xml) and a
 * human [label] shown next to the temperature.
 *
 * References:
 *  - OpenWeatherMap condition codes: https://openweathermap.org/weather-conditions
 *  - Open-Meteo WMO codes: https://open-meteo.com/en/docs
 */
enum class WeatherStatus(val key: String, val label: String) {
    // Clear / clouds
    CLEAR("clear", "Clear"),
    MAINLY_CLEAR("mainly_clear", "Mainly Clear"),
    PARTLY_CLOUDY("partly_cloudy", "Partly Cloudy"),
    BROKEN_CLOUDS("broken_clouds", "Broken Clouds"),
    OVERCAST("overcast", "Overcast"),

    // Atmosphere
    MIST("mist", "Mist"),
    SMOKE("smoke", "Smoke"),
    HAZE("haze", "Haze"),
    DUST_WHIRLS("dust_whirls", "Dust Whirls"),
    FOG("fog", "Fog"),
    RIME_FOG("rime_fog", "Rime Fog"),
    SAND("sand", "Sand"),
    DUST("dust", "Dust"),
    VOLCANIC_ASH("volcanic_ash", "Volcanic Ash"),
    SQUALL("squall", "Squalls"),
    TORNADO("tornado", "Tornado"),

    // Tropical cyclones. Weather APIs have no condition code for these; they're derived from the
    // numeric sustained-wind field (see [fromWind]).
    TROPICAL_STORM("tropical_storm", "Tropical Storm"),
    HURRICANE("hurricane", "Hurricane"),

    // Drizzle
    LIGHT_DRIZZLE("light_drizzle", "Light Drizzle"),
    DRIZZLE("drizzle", "Drizzle"),
    HEAVY_DRIZZLE("heavy_drizzle", "Heavy Drizzle"),
    LIGHT_DRIZZLE_RAIN("light_drizzle_rain", "Light Drizzle Rain"),
    DRIZZLE_RAIN("drizzle_rain", "Drizzle Rain"),
    HEAVY_DRIZZLE_RAIN("heavy_drizzle_rain", "Heavy Drizzle Rain"),
    SHOWER_DRIZZLE("shower_drizzle", "Shower Drizzle"),
    FREEZING_DRIZZLE("freezing_drizzle", "Freezing Drizzle"),

    // Rain
    LIGHT_RAIN("light_rain", "Light Rain"),
    MODERATE_RAIN("moderate_rain", "Moderate Rain"),
    HEAVY_RAIN("heavy_rain", "Heavy Rain"),
    VERY_HEAVY_RAIN("very_heavy_rain", "Very Heavy Rain"),
    EXTREME_RAIN("extreme_rain", "Extreme Rain"),
    FREEZING_RAIN("freezing_rain", "Freezing Rain"),
    LIGHT_SHOWER_RAIN("light_shower_rain", "Light Showers"),
    SHOWER_RAIN("shower_rain", "Showers"),
    HEAVY_SHOWER_RAIN("heavy_shower_rain", "Heavy Showers"),
    RAGGED_SHOWER_RAIN("ragged_shower_rain", "Ragged Showers"),

    // Snow
    LIGHT_SNOW("light_snow", "Light Snow"),
    SNOW("snow", "Snow"),
    HEAVY_SNOW("heavy_snow", "Heavy Snow"),
    SLEET("sleet", "Sleet"),
    LIGHT_SHOWER_SLEET("light_shower_sleet", "Light Shower Sleet"),
    SHOWER_SLEET("shower_sleet", "Shower Sleet"),
    LIGHT_RAIN_SNOW("light_rain_snow", "Light Rain & Snow"),
    RAIN_SNOW("rain_snow", "Rain & Snow"),
    LIGHT_SHOWER_SNOW("light_shower_snow", "Light Snow Showers"),
    SHOWER_SNOW("shower_snow", "Snow Showers"),
    HEAVY_SHOWER_SNOW("heavy_shower_snow", "Heavy Snow Showers"),
    SNOW_GRAINS("snow_grains", "Snow Grains"),

    // Thunderstorm
    THUNDERSTORM_LIGHT_RAIN("thunderstorm_light_rain", "Thunderstorm, Light Rain"),
    THUNDERSTORM_RAIN("thunderstorm_rain", "Thunderstorm & Rain"),
    THUNDERSTORM_HEAVY_RAIN("thunderstorm_heavy_rain", "Thunderstorm, Heavy Rain"),
    LIGHT_THUNDERSTORM("light_thunderstorm", "Light Thunderstorm"),
    THUNDERSTORM("thunderstorm", "Thunderstorm"),
    HEAVY_THUNDERSTORM("heavy_thunderstorm", "Heavy Thunderstorm"),
    RAGGED_THUNDERSTORM("ragged_thunderstorm", "Ragged Thunderstorm"),
    THUNDERSTORM_LIGHT_DRIZZLE("thunderstorm_light_drizzle", "Thunderstorm, Light Drizzle"),
    THUNDERSTORM_DRIZZLE("thunderstorm_drizzle", "Thunderstorm & Drizzle"),
    THUNDERSTORM_HEAVY_DRIZZLE("thunderstorm_heavy_drizzle", "Thunderstorm, Heavy Drizzle"),
    THUNDERSTORM_HAIL("thunderstorm_hail", "Thunderstorm & Hail"),

    UNKNOWN("unknown", "Unknown");

    companion object {
        // Sustained-wind thresholds (metres/second) for tropical-cyclone classification, using the
        // standard tropical-storm (~34 kn) and Saffir-Simpson hurricane (~64 kn) cutoffs:
        //   >= 32.7 m/s (64 kn / 118 km/h) -> HURRICANE
        //   >= 17.2 m/s (34 kn /  62 km/h) -> TROPICAL_STORM
        const val TROPICAL_STORM_WIND_MS = 17.2
        const val HURRICANE_WIND_MS = 32.7

        fun fromKey(key: String?): WeatherStatus =
            entries.firstOrNull { it.key == key } ?: UNKNOWN

        /**
         * Upgrade an observed [base] condition to a tropical-cyclone class from the sustained wind
         * speed in metres/second. Neither OpenWeatherMap nor Open-Meteo emits a hurricane/typhoon
         * condition code, so this is the only way to surface those systems. Below tropical-storm
         * force the original observed condition is kept unchanged.
         */
        fun fromWind(base: WeatherStatus, windMs: Double?): WeatherStatus {
            if (windMs == null || windMs.isNaN()) return base
            return when {
                windMs >= HURRICANE_WIND_MS -> HURRICANE
                windMs >= TROPICAL_STORM_WIND_MS -> TROPICAL_STORM
                else -> base
            }
        }

        /** OpenWeatherMap weather[0].id -> canonical status. */
        fun fromOwmId(id: Int): WeatherStatus = when (id) {
            200 -> THUNDERSTORM_LIGHT_RAIN
            201 -> THUNDERSTORM_RAIN
            202 -> THUNDERSTORM_HEAVY_RAIN
            210 -> LIGHT_THUNDERSTORM
            211 -> THUNDERSTORM
            212 -> HEAVY_THUNDERSTORM
            221 -> RAGGED_THUNDERSTORM
            230 -> THUNDERSTORM_LIGHT_DRIZZLE
            231 -> THUNDERSTORM_DRIZZLE
            232 -> THUNDERSTORM_HEAVY_DRIZZLE
            300 -> LIGHT_DRIZZLE
            301 -> DRIZZLE
            302 -> HEAVY_DRIZZLE
            310 -> LIGHT_DRIZZLE_RAIN
            311 -> DRIZZLE_RAIN
            312 -> HEAVY_DRIZZLE_RAIN
            313, 314, 321 -> SHOWER_DRIZZLE
            500 -> LIGHT_RAIN
            501 -> MODERATE_RAIN
            502 -> HEAVY_RAIN
            503 -> VERY_HEAVY_RAIN
            504 -> EXTREME_RAIN
            511 -> FREEZING_RAIN
            520 -> LIGHT_SHOWER_RAIN
            521 -> SHOWER_RAIN
            522 -> HEAVY_SHOWER_RAIN
            531 -> RAGGED_SHOWER_RAIN
            600 -> LIGHT_SNOW
            601 -> SNOW
            602 -> HEAVY_SNOW
            611 -> SLEET
            612 -> LIGHT_SHOWER_SLEET
            613 -> SHOWER_SLEET
            615 -> LIGHT_RAIN_SNOW
            616 -> RAIN_SNOW
            620 -> LIGHT_SHOWER_SNOW
            621 -> SHOWER_SNOW
            622 -> HEAVY_SHOWER_SNOW
            701 -> MIST
            711 -> SMOKE
            721 -> HAZE
            731 -> DUST_WHIRLS
            741 -> FOG
            751 -> SAND
            761 -> DUST
            762 -> VOLCANIC_ASH
            771 -> SQUALL
            781 -> TORNADO
            800 -> CLEAR
            801 -> MAINLY_CLEAR
            802 -> PARTLY_CLOUDY
            803 -> BROKEN_CLOUDS
            804 -> OVERCAST
            else -> UNKNOWN
        }

        /** Open-Meteo WMO interpretation code -> canonical status. */
        fun fromWmoCode(code: Int): WeatherStatus = when (code) {
            0 -> CLEAR
            1 -> MAINLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45 -> FOG
            48 -> RIME_FOG
            51 -> LIGHT_DRIZZLE
            53 -> DRIZZLE
            55 -> HEAVY_DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61 -> LIGHT_RAIN
            63 -> MODERATE_RAIN
            65 -> HEAVY_RAIN
            66, 67 -> FREEZING_RAIN
            71 -> LIGHT_SNOW
            73 -> SNOW
            75 -> HEAVY_SNOW
            77 -> SNOW_GRAINS
            80 -> LIGHT_SHOWER_RAIN
            81 -> SHOWER_RAIN
            82 -> HEAVY_SHOWER_RAIN
            85 -> SHOWER_SNOW
            86 -> HEAVY_SHOWER_SNOW
            95 -> THUNDERSTORM
            96, 99 -> THUNDERSTORM_HAIL
            else -> UNKNOWN
        }
    }
}
