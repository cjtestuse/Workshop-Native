package com.slay.workshopnative.data.local

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)

    @TypeConverter
    fun fromDownloadAuthMode(value: DownloadAuthMode): String = value.name

    @TypeConverter
    fun toDownloadAuthMode(value: String): DownloadAuthMode = DownloadAuthMode.valueOf(value)
}
