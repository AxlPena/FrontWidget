package com.saveory.frontwidget.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import me.proton.core.auth.domain.usecase.PostLoginAccountSetup
import me.proton.core.auth.presentation.DefaultHelpOptionHandler
import me.proton.core.auth.presentation.HelpOptionHandler
import me.proton.core.configuration.EnvironmentConfiguration
import me.proton.core.contact.data.ContactEmailEventListener
import me.proton.core.contact.data.ContactEventListener
import me.proton.core.eventmanager.domain.EventListener
import me.proton.core.featureflag.domain.FeatureFlagOverrider
import me.proton.core.humanverification.presentation.HumanVerificationApiHost
import me.proton.core.humanverification.presentation.utils.HumanVerificationVersion
import me.proton.core.notification.data.NotificationEventListener
import me.proton.core.plan.domain.ClientPlanFilter
import me.proton.core.plan.domain.ProductOnlyPaidPlans
import me.proton.core.plan.domain.SupportSignupPaidPlans
import me.proton.core.plan.domain.SupportUpgradePaidPlans
import me.proton.core.push.data.PushEventListener
import me.proton.core.user.data.UserEventListener
import me.proton.core.user.domain.entity.User
import me.proton.core.usersettings.data.UserSettingsEventListener
import javax.inject.Singleton

/**
 * App-level bindings that Proton Core expects the host app to supply.
 * FrontWidget only reads data, so plan/payments support is disabled and the
 * post-login user check always succeeds.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProtonBindingsModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideUserCheck(): PostLoginAccountSetup.UserCheck =
        object : PostLoginAccountSetup.UserCheck {
            override suspend fun invoke(user: User): PostLoginAccountSetup.UserCheckResult =
                PostLoginAccountSetup.UserCheckResult.Success
        }

    @Provides
    @Singleton
    fun provideHelpOptionHandler(): HelpOptionHandler = DefaultHelpOptionHandler()

    @Provides
    fun provideHumanVerificationVersion(): HumanVerificationVersion = HumanVerificationVersion.HV3

    @Provides
    @HumanVerificationApiHost
    fun provideHumanVerificationApiHost(config: EnvironmentConfiguration): String = config.hv3Url

    @Provides
    @SupportSignupPaidPlans
    fun provideSupportSignupPaidPlans(): Boolean = false

    @Provides
    @SupportUpgradePaidPlans
    fun provideSupportUpgradePaidPlans(): Boolean = false

    @Provides
    @ProductOnlyPaidPlans
    fun provideProductOnlyPaidPlans(): Boolean = false

    @Provides
    fun provideClientPlanFilter(): ClientPlanFilter? = null

    @Provides
    fun provideFeatureFlagOverrider(): FeatureFlagOverrider? = null

    @Provides
    @Singleton
    @ElementsIntoSet
    @JvmSuppressWildcards
    fun provideEventListenerSet(
        userEventListener: UserEventListener,
        userSettingsEventListener: UserSettingsEventListener,
        contactEventListener: ContactEventListener,
        contactEmailEventListener: ContactEmailEventListener,
        notificationEventListener: NotificationEventListener,
        pushEventListener: PushEventListener
    ): Set<EventListener<*, *>> = setOf(
        userEventListener,
        userSettingsEventListener,
        contactEventListener,
        contactEmailEventListener,
        notificationEventListener,
        pushEventListener
    )
}
