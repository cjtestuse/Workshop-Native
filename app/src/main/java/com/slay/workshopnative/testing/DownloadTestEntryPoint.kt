package com.slay.workshopnative.testing

import com.slay.workshopnative.data.local.DownloadTaskDao
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.SteamRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadTestEntryPoint {
    fun steamRepository(): SteamRepository
    fun downloadsRepository(): DownloadsRepository
    fun preferencesStore(): UserPreferencesStore
    fun downloadTaskDao(): DownloadTaskDao
}
