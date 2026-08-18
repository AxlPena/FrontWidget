package com.saveory.frontwidget.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.network.data.ApiProvider
import me.proton.core.user.domain.UserManager

/**
 * Lets plain (non-Hilt) WorkManager workers pull the Proton Core singletons they need.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProtonEntryPoint {
    fun accountManager(): AccountManager
    fun userManager(): UserManager
    fun apiProvider(): ApiProvider
    fun cryptoContext(): CryptoContext
}
