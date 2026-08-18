package com.saveory.frontwidget

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp

/**
 * Uses WorkManager's on-demand initialization (the default [androidx.work.WorkManagerInitializer]
 * is removed in the manifest). This is required because Proton Core's androidx.startup initializers
 * (e.g. DeviceRecoveryInitializer) run during ContentProvider creation — before Hilt field injection
 * in [onCreate] — and reach WorkManager.getInstance(). On-demand init lets that first call build
 * WorkManager from this always-available config instead of crashing on an ordering/late-init issue.
 *
 * The config is a plain default: our workers ([WeatherWorker], [EventsWorker]) are constructed by the
 * default factory (they resolve dependencies via a Hilt EntryPoint, not a HiltWorkerFactory).
 */
@HiltAndroidApp
class FrontWidgetApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
