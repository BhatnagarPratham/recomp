package app.recomp.di

import app.recomp.data.backup.BackupStorage
import app.recomp.data.backup.SafBackupStorage
import app.recomp.data.health.HealthWriter
import app.recomp.healthconnect.HealthConnectWriter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** M16, data out: where the automatic backup writes, and Health Connect's write side. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds
    abstract fun bindBackupStorage(storage: SafBackupStorage): BackupStorage

    @Binds
    abstract fun bindHealthWriter(writer: HealthConnectWriter): HealthWriter
}
