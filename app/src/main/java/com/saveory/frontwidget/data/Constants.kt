package com.saveory.frontwidget.data

import com.saveory.frontwidget.BuildConfig

object Constants {
    const val WEATHER_BASE_URL = "https://api.openweathermap.org/data/2.5/"

    // Injected at build time from local.properties / env (see app/build.gradle). Never hardcode the
    // key in source — it ships in the APK and is trivially extractable. Rotate the old committed key.
    val OPEN_WEATHER_API_KEY: String = BuildConfig.OPEN_WEATHER_API_KEY
}
