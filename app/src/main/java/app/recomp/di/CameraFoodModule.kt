package app.recomp.di

import app.recomp.BuildConfig
import app.recomp.data.food.OpenFoodFactsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** M18, camera food: Open Food Facts' address and the User-Agent its terms ask for (TRD §10 M18). */
@Module
@InstallIn(SingletonComponent::class)
object CameraFoodModule {
    @Provides
    @Named(OpenFoodFactsClient.USER_AGENT)
    fun userAgent(): String = OpenFoodFactsClient.userAgent(BuildConfig.VERSION_NAME, BuildConfig.OFF_CONTACT)

    @Provides
    @Named(OpenFoodFactsClient.BASE_URL)
    fun baseUrl(): String = OpenFoodFactsClient.DEFAULT_BASE_URL
}
