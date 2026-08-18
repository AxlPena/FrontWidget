package com.saveory.frontwidget.data

import retrofit2.http.GET
import retrofit2.http.Query

interface WeatherApi {
    @GET("weather")
    suspend fun getCurrentWeather(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): WeatherResponse

    @GET("https://api.openweathermap.org/geo/1.0/reverse")
    suspend fun getReverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("limit") limit: Int = 1,
        @Query("appid") apiKey: String
    ): List<GeocodingResponse>

    // Open-Meteo: free, no API key. Absolute URL so it ignores the OpenWeatherMap base URL.
    // temperature_2m is returned in Celsius by default; weather_code is a WMO code.
    // daily=sunrise,sunset with timeformat=unixtime returns absolute UTC epoch seconds;
    // timezone=auto aligns the daily arrays to the location's local calendar day.
    @GET("https://api.open-meteo.com/v1/forecast")
    suspend fun getOpenMeteoWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,weather_code,is_day,wind_speed_10m",
        @Query("daily") daily: String = "sunrise,sunset",
        // Request wind in m/s so the tropical-cyclone thresholds compare against a single unit.
        @Query("wind_speed_unit") windSpeedUnit: String = "ms",
        @Query("timeformat") timeformat: String = "unixtime",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse
}
