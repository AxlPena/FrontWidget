package com.saveory.frontwidget.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.compose.theme.AppTheme
import me.proton.core.compose.theme.ProtonTheme
import me.proton.core.domain.entity.AppStore
import me.proton.core.domain.entity.Product
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProtonAppModule {

    @Provides
    @Singleton
    fun provideProduct(): Product = Product.Calendar

    @Provides
    @Singleton
    fun provideAppStore(): AppStore = AppStore.GooglePlay

    @Provides
    @Singleton
    fun provideRequiredAccountType(): AccountType = AccountType.Internal

    @Provides
    fun provideAppTheme(): AppTheme = AppTheme { content -> ProtonTheme { content() } }
}
