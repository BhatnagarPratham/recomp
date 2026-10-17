package app.recomp.di

import app.recomp.data.food.AssetFoodFiles
import app.recomp.data.food.FoodFiles
import app.recomp.data.seed.AssetSeedSource
import app.recomp.data.seed.SeedSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SeedModule {
    @Binds
    abstract fun bindSeedSource(source: AssetSeedSource): SeedSource

    @Binds
    abstract fun bindFoodFiles(files: AssetFoodFiles): FoodFiles
}
