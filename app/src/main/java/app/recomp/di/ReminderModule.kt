package app.recomp.di

import app.recomp.reminders.AndroidReminderNotifier
import app.recomp.reminders.ReminderNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds
    abstract fun notifier(notifier: AndroidReminderNotifier): ReminderNotifier
}
