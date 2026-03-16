package com.slay.workshopnative.di

import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.DownloadsRepositoryImpl
import com.slay.workshopnative.data.repository.SteamRepository
import com.slay.workshopnative.data.repository.SteamRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSteamRepository(
        impl: SteamRepositoryImpl,
    ): SteamRepository

    @Binds
    @Singleton
    abstract fun bindDownloadsRepository(
        impl: DownloadsRepositoryImpl,
    ): DownloadsRepository
}
