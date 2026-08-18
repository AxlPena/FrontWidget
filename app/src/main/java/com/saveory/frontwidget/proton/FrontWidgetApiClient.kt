package com.saveory.frontwidget.proton

import android.os.Build
import me.proton.core.network.domain.ApiClient
import javax.inject.Inject

/**
 * Minimal ApiClient. We masquerade as the Proton Calendar client version so the API
 * accepts our requests (Proton validates the x-pm-appversion header product/version).
 */
class FrontWidgetApiClient @Inject constructor() : ApiClient {

    override val appVersionHeader: String = "android-calendar@2.29.0"

    override val enableDebugLogging: Boolean = true

    override val userAgent: String
        get() = "FrontWidget/1.0 (Android ${Build.VERSION.RELEASE}; ${Build.BRAND} ${Build.MODEL})"

    override suspend fun shouldUseDoh(): Boolean = false

    override fun forceUpdate(errorMessage: String) {
        // No force-update UI in a widget host; requests will simply fail if truly outdated.
    }
}
