package com.slay.workshopnative.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.work.WorkManager
import com.slay.workshopnative.BuildConfig
import com.slay.workshopnative.core.logging.AppLog
import com.slay.workshopnative.core.logging.SupportDiagnosticsStore
import com.slay.workshopnative.core.util.sanitizeOkHttpLogMessage
import com.slay.workshopnative.data.local.AppDatabase
import com.slay.workshopnative.data.local.DownloadTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        supportDiagnosticsStore: SupportDiagnosticsStore,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            AppLog.i("OkHttp", sanitizeOkHttpLogMessage(message))
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .cache(Cache(context.cacheDir.resolve("http-cache"), 64L * 1024L * 1024L))
            .addInterceptor { chain ->
                val request = chain.request()
                supportDiagnosticsStore.recordHttpRequest(request)
                try {
                    chain.proceed(request)
                } catch (throwable: Throwable) {
                    supportDiagnosticsStore.recordHttpFailure(request)
                    throw throwable
                }
            }
            .addInterceptor(logging)
            .retryOnConnectionFailure(true)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun providePreferencesStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("workshop_native.preferences_pb") },
        )
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "workshop_native.db",
        )
            .addMigrations(
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideDownloadTaskDao(database: AppDatabase): DownloadTaskDao = database.downloadTaskDao()

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}
