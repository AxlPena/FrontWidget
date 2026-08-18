package com.saveory.frontwidget.data

data class WeatherResponse(
    val main: Main,
    val weather: List<Weather>,
    val wind: Wind?,
    val name: String,
    val sys: Sys,
    val timezone: Int
)

data class Wind(
    // Sustained wind speed. With units=metric this is metres/second; used to classify tropical
    // storms/hurricanes, which have no dedicated condition code.
    val speed: Float?,
    val gust: Float?
)

data class Main(
    val temp: Float,
    val humidity: Int
)

data class Weather(
    // OpenWeatherMap condition id (e.g. 500 = light rain). This is the most precise status
    // discriminator and drives the exact icon selection.
    val id: Int,
    val main: String,
    val description: String,
    // OWM icon code; only its "d"/"n" suffix is used, to decide day vs night art.
    val icon: String
)

data class Sys(
    val country: String,
    // Unix UTC seconds for today's sunrise/sunset at the queried coordinates. Used to pick
    // day vs night art by comparing against the current time.
    val sunrise: Long?,
    val sunset: Long?
)

data class GeocodingResponse(
    val name: String,
    val country: String,
    val state: String?
)

data class OpenMeteoResponse(
    val current: OpenMeteoCurrent?,
    val daily: OpenMeteoDaily?,
    // With timezone=auto, Open-Meteo echoes the location's IANA zone ("Europe/Paris") and its
    // current UTC offset in seconds. Used to render the widget clock in the location's local time.
    val timezone: String?,
    val utc_offset_seconds: Int?
)

data class OpenMeteoCurrent(
    val temperature_2m: Float?,
    val weather_code: Int?,
    // 1 = day, 0 = night at the queried coordinates; used to pick day vs night art.
    val is_day: Int?,
    // Sustained wind speed in metres/second (requested via wind_speed_unit=ms); used to classify
    // tropical storms/hurricanes, which have no WMO condition code.
    val wind_speed_10m: Float?
)

data class OpenMeteoDaily(
    // With timeformat=unixtime these are Unix UTC seconds; [0] is today at the location.
    val sunrise: List<Long>?,
    val sunset: List<Long>?
)
