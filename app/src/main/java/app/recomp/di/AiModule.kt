package app.recomp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.recomp.data.ai.AndroidConnectivity
import app.recomp.data.ai.ClaudeMealEstimator
import app.recomp.data.ai.Connectivity
import app.recomp.data.ai.SecretCipher
import app.recomp.data.ai.TinkSecretCipher
import app.recomp.domain.ai.MealEstimator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The encrypted API key's own store (TRD §6.2), apart from the settings so no export can include it, and
 * excluded from backups by res/xml/data_extraction_rules.xml.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsStore

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds
    abstract fun bindEstimator(estimator: ClaudeMealEstimator): MealEstimator

    @Binds
    abstract fun bindCipher(cipher: TinkSecretCipher): SecretCipher

    @Binds
    abstract fun bindConnectivity(connectivity: AndroidConnectivity): Connectivity

    companion object {
        /** Also named in res/xml/data_extraction_rules.xml. */
        const val SECRETS_FILE = "secrets"

        @Provides
        @Singleton
        @SecretsStore
        fun provideSecretsStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SECRETS_FILE) }

        @Provides
        fun provideClock(): Clock = Clock.systemDefaultZone()
    }
}
