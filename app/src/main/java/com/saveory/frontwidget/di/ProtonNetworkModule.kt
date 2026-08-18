package com.saveory.frontwidget.di

import com.saveory.frontwidget.proton.FrontWidgetApiClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.configuration.EnvironmentConfiguration
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.data.di.AlternativeApiPins
import me.proton.core.network.data.di.BaseProtonApiUrl
import me.proton.core.network.data.di.CertificatePins
import me.proton.core.network.data.di.Constants
import me.proton.core.network.data.di.DohProviderUrls
import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.client.ExtraHeaderProvider
import me.proton.core.network.domain.serverconnection.DohAlternativesListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProtonNetworkModule {

    @Provides
    @Singleton
    fun provideEnvironmentConfiguration(): EnvironmentConfiguration =
        EnvironmentConfiguration.fromMap(mapOf("host" to "proton.me"))

    // Proton's production API host. Note the EnvironmentConfiguration default ("api.<host>")
    // resolves to the non-existent "api.proton.me"; the real API is served at mail-api.proton.me
    // (shared mail/calendar backend). Default SPKI pins apply to all *.proton.me hosts.
    @Provides
    @BaseProtonApiUrl
    fun provideProtonApiUrl(): HttpUrl = "https://mail-api.proton.me/".toHttpUrl()

    @Provides
    @DohProviderUrls
    fun provideDohProviderUrls(): Array<String> = Constants.DOH_PROVIDERS_URLS

    @Provides
    @CertificatePins
    fun provideCertificatePins(config: EnvironmentConfiguration): Array<String> =
        if (config.useDefaultPins) Constants.DEFAULT_SPKI_PINS else emptyArray()

    @Provides
    @AlternativeApiPins
    fun provideAlternativeApiPins(config: EnvironmentConfiguration): List<String> =
        if (config.useDefaultPins) Constants.ALTERNATIVE_API_SPKI_PINS else emptyList()

    @Provides
    @Singleton
    fun provideDohAlternativesListener(): DohAlternativesListener? = null

    @Provides
    @Singleton
    fun provideExtraHeaderProvider(): ExtraHeaderProvider = ExtraHeaderProviderImpl()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProtonNetworkBindsModule {
    @Binds
    abstract fun bindApiClient(impl: FrontWidgetApiClient): ApiClient
}
