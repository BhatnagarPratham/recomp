package app.recomp.di

import app.recomp.data.health.HealthSource
import app.recomp.healthconnect.HealthConnectSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {
    @Binds
    abstract fun bindHealthSource(source: HealthConnectSource): HealthSource
}
