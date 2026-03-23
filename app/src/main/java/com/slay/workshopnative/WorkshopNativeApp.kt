package com.slay.workshopnative

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import androidx.work.Configuration
import com.slay.workshopnative.core.logging.AppLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okio.Path.Companion.toOkioPath

@HiltAndroidApp
class WorkshopNativeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .serviceLoaderEnabled(false)
                .components {
                    add(OkHttpNetworkFetcherFactory())
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.2)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("coil-image-cache").toOkioPath())
                        .maxSizeBytes(256L * 1024L * 1024L)
                        .build()
                }
                .build()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
