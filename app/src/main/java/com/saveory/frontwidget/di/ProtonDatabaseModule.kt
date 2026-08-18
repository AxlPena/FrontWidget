package com.saveory.frontwidget.di

import android.content.Context
import com.saveory.frontwidget.proton.AppDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.proton.core.account.data.db.AccountDatabase
import me.proton.core.auth.data.db.AuthDatabase
import me.proton.core.challenge.data.db.ChallengeDatabase
import me.proton.core.contact.data.local.db.ContactDatabase
import me.proton.core.eventmanager.data.db.EventMetadataDatabase
import me.proton.core.featureflag.data.db.FeatureFlagDatabase
import me.proton.core.humanverification.data.db.HumanVerificationDatabase
import me.proton.core.key.data.db.KeySaltDatabase
import me.proton.core.key.data.db.PublicAddressDatabase
import me.proton.core.keytransparency.data.local.KeyTransparencyDatabase
import me.proton.core.mailsettings.data.db.MailSettingsDatabase
import me.proton.core.notification.data.local.db.NotificationDatabase
import me.proton.core.observability.data.db.ObservabilityDatabase
import me.proton.core.payment.data.local.db.PaymentDatabase
import me.proton.core.push.data.local.db.PushDatabase
import me.proton.core.telemetry.data.db.TelemetryDatabase
import me.proton.core.user.data.db.AddressDatabase
import me.proton.core.user.data.db.UserDatabase
import me.proton.core.userrecovery.data.db.DeviceRecoveryDatabase
import me.proton.core.usersettings.data.db.OrganizationDatabase
import me.proton.core.usersettings.data.db.UserSettingsDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProtonDatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.buildDatabase(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProtonDatabaseBindsModule {
    @Binds
    abstract fun provideAccountDatabase(db: AppDatabase): AccountDatabase

    @Binds
    abstract fun provideUserDatabase(db: AppDatabase): UserDatabase

    @Binds
    abstract fun provideAddressDatabase(db: AppDatabase): AddressDatabase

    @Binds
    abstract fun provideKeySaltDatabase(db: AppDatabase): KeySaltDatabase

    @Binds
    abstract fun providePublicAddressDatabase(db: AppDatabase): PublicAddressDatabase

    @Binds
    abstract fun provideHumanVerificationDatabase(db: AppDatabase): HumanVerificationDatabase

    @Binds
    abstract fun provideMailSettingsDatabase(db: AppDatabase): MailSettingsDatabase

    @Binds
    abstract fun provideUserSettingsDatabase(db: AppDatabase): UserSettingsDatabase

    @Binds
    abstract fun provideOrganizationDatabase(db: AppDatabase): OrganizationDatabase

    @Binds
    abstract fun provideContactDatabase(db: AppDatabase): ContactDatabase

    @Binds
    abstract fun provideEventMetadataDatabase(db: AppDatabase): EventMetadataDatabase

    @Binds
    abstract fun provideFeatureFlagDatabase(db: AppDatabase): FeatureFlagDatabase

    @Binds
    abstract fun provideChallengeDatabase(db: AppDatabase): ChallengeDatabase

    @Binds
    abstract fun providePaymentDatabase(db: AppDatabase): PaymentDatabase

    @Binds
    abstract fun provideObservabilityDatabase(db: AppDatabase): ObservabilityDatabase

    @Binds
    abstract fun provideKeyTransparencyDatabase(db: AppDatabase): KeyTransparencyDatabase

    @Binds
    abstract fun provideNotificationDatabase(db: AppDatabase): NotificationDatabase

    @Binds
    abstract fun providePushDatabase(db: AppDatabase): PushDatabase

    @Binds
    abstract fun provideTelemetryDatabase(db: AppDatabase): TelemetryDatabase

    @Binds
    abstract fun provideDeviceRecoveryDatabase(db: AppDatabase): DeviceRecoveryDatabase

    @Binds
    abstract fun provideAuthDatabase(db: AppDatabase): AuthDatabase
}
