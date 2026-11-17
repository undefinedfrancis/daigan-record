package com.daigan.record.data.di

import com.daigan.record.core.time.BoundaryWaiter
import com.daigan.record.core.time.RealBoundaryWaiter
import com.daigan.record.core.time.ReportingTimeZoneProvider
import com.daigan.record.core.time.SystemReportingTimeZoneProvider
import com.daigan.record.core.time.SystemTimeProvider
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.repository.AttachmentFileCleanup
import com.daigan.record.data.repository.AttachmentRepository
import com.daigan.record.data.repository.DefaultAttachmentRepository
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.data.repository.DefaultGameTemplateRepository
import com.daigan.record.data.repository.AccountCategoryRepository
import com.daigan.record.data.repository.DefaultAccountCategoryRepository
import com.daigan.record.data.repository.DefaultOrderRepository
import com.daigan.record.data.repository.DefaultOwnerRepository
import com.daigan.record.data.repository.DefaultReceiptRepository
import com.daigan.record.data.repository.DefaultServerOptionRepository
import com.daigan.record.data.repository.DefaultStatisticsRepository
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.data.repository.ReceiptRepository
import com.daigan.record.data.repository.ServerOptionRepository
import com.daigan.record.data.repository.StatisticsRepository
import com.daigan.record.data.template.AssetBuiltInTemplateSource
import com.daigan.record.data.template.BuiltInTemplateSource
import com.daigan.record.data.template.ContentResolverTemplateFileRepository
import com.daigan.record.data.template.TemplateFileRepository
import com.daigan.record.backup.BackupDocumentStore
import com.daigan.record.backup.ContentResolverBackupDocumentStore
import com.daigan.record.notifications.AndroidReminderNotifier
import com.daigan.record.notifications.DefaultReminderSettingsRepository
import com.daigan.record.notifications.ReminderNotifier
import com.daigan.record.notifications.ReminderScheduler
import com.daigan.record.notifications.ReminderSettingsRepository
import com.daigan.record.notifications.WorkManagerReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Singleton
    @Binds
    fun bindsOwnerRepository(repository: DefaultOwnerRepository): OwnerRepository

    @Singleton
    @Binds
    fun bindsGameAccountRepository(repository: DefaultGameAccountRepository): GameAccountRepository

    @Singleton
    @Binds
    fun bindsAccountCategoryRepository(
        repository: DefaultAccountCategoryRepository,
    ): AccountCategoryRepository

    @Singleton
    @Binds
    fun bindsServerOptionRepository(repository: DefaultServerOptionRepository): ServerOptionRepository

    @Singleton
    @Binds
    fun bindsOrderRepository(repository: DefaultOrderRepository): OrderRepository

    /** One count-session write/recompute boundary shared by every order/progress command. */
    @Singleton
    @Binds
    fun bindsCountSessionService(
        service: com.daigan.record.data.repository.DefaultCountSessionService,
    ): com.daigan.record.data.repository.CountSessionService

    @Singleton
    @Binds
    fun bindsAttachmentRepository(repository: DefaultAttachmentRepository): AttachmentRepository

    /** Same singleton serves the post-commit file cleanup used by deletions. */
    @Singleton
    @Binds
    fun bindsAttachmentFileCleanup(repository: DefaultAttachmentRepository): AttachmentFileCleanup

    @Singleton
    @Binds
    fun bindsReceiptRepository(repository: DefaultReceiptRepository): ReceiptRepository

    @Singleton
    @Binds
    fun bindsStatisticsRepository(repository: DefaultStatisticsRepository): StatisticsRepository

    @Binds
    fun bindsTimeProvider(timeProvider: SystemTimeProvider): TimeProvider

    @Singleton
    @Binds
    fun bindsProgressCommandService(
        repository: com.daigan.record.data.repository.CumulativeProgressRepository,
    ): com.daigan.record.data.repository.ProgressCommandService

    @Binds
    fun bindsReportingTimeZoneProvider(
        provider: SystemReportingTimeZoneProvider,
    ): ReportingTimeZoneProvider

    @Singleton
    @Binds
    fun bindsBoundaryWaiter(waiter: RealBoundaryWaiter): BoundaryWaiter

    @Singleton
    @Binds
    fun bindsGameTemplateRepository(
        repository: DefaultGameTemplateRepository,
    ): GameTemplateRepository

    @Singleton
    @Binds
    fun bindsTemplateFileRepository(
        repository: ContentResolverTemplateFileRepository,
    ): TemplateFileRepository

    @Singleton
    @Binds
    fun bindsBuiltInTemplateSource(source: AssetBuiltInTemplateSource): BuiltInTemplateSource

    @Singleton
    @Binds
    fun bindsReminderSettingsRepository(
        repository: DefaultReminderSettingsRepository,
    ): ReminderSettingsRepository

    @Singleton
    @Binds
    fun bindsReminderScheduler(scheduler: WorkManagerReminderScheduler): ReminderScheduler

    @Singleton
    @Binds
    fun bindsReminderNotifier(notifier: AndroidReminderNotifier): ReminderNotifier

    @Singleton
    @Binds
    fun bindsBackupDocumentStore(store: ContentResolverBackupDocumentStore): BackupDocumentStore
}
