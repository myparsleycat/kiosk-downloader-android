package com.kiodl.android.data.settings

import android.content.Context
import com.kiodl.android.domain.model.AppSettings
import com.kiodl.android.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings = mutableSettings.asStateFlow()

    fun setSegmentPoolSize(value: Int) =
        update(settings.value.copy(segmentPoolSize = value.coerceIn(SEGMENT_POOL_SIZE_MIN, SEGMENT_POOL_SIZE_MAX)))

    fun setDownloadBandwidthMiBps(value: Int) =
        update(settings.value.copy(downloadBandwidthMiBps = value.coerceIn(0, BANDWIDTH_LIMIT_MAX)))

    fun setUploadBandwidthMiBps(value: Int) =
        update(settings.value.copy(uploadBandwidthMiBps = value.coerceIn(0, BANDWIDTH_LIMIT_MAX)))

    fun setDownloadMaxRetries(value: Int) =
        update(settings.value.copy(downloadMaxRetries = value.coerceIn(DOWNLOAD_RETRY_MIN, DOWNLOAD_RETRY_MAX)))

    fun setUploadMaxRetries(value: Int) =
        update(settings.value.copy(uploadMaxRetries = value.coerceIn(UPLOAD_RETRY_MIN, UPLOAD_RETRY_MAX)))

    fun setLastDownloadDestinationUri(value: String) =
        update(settings.value.copy(lastDownloadDestinationUri = value.trim()))

    fun setCreateCollectionSubfolder(value: Boolean) =
        update(settings.value.copy(createCollectionSubfolder = value))

    fun setAsciiFilenames(value: Boolean) = update(settings.value.copy(asciiFilenames = value))

    fun setAutoTryCollectionPasswords(value: Boolean) =
        update(settings.value.copy(autoTryCollectionPasswords = value))

    fun setCollectionPasswordList(value: List<String>) = update(
        settings.value.copy(collectionPasswordList = value.map(String::trim).filter(String::isNotEmpty).distinct().take(10)),
    )

    fun setStreamWriteBatchBytes(value: Int) = update(
        settings.value.copy(streamWriteBatchBytes = STREAM_WRITE_OPTIONS.minBy { kotlin.math.abs(it - value) }),
    )

    fun setInflateBufferBytes(value: Int) = update(
        settings.value.copy(inflateBufferBytes = INFLATE_BUFFER_OPTIONS.minBy { kotlin.math.abs(it - value) }),
    )

    fun setThemeMode(value: ThemeMode) = update(settings.value.copy(themeMode = value))

    private fun update(value: AppSettings) {
        preferences.edit()
            .putInt("segmentPoolSize", value.segmentPoolSize)
            .putInt("downloadBandwidthMiBps", value.downloadBandwidthMiBps)
            .putInt("uploadBandwidthMiBps", value.uploadBandwidthMiBps)
            .putInt("downloadMaxRetries", value.downloadMaxRetries)
            .putInt("uploadMaxRetries", value.uploadMaxRetries)
            .putString("lastDownloadDestinationUri", value.lastDownloadDestinationUri)
            .putBoolean("createCollectionSubfolder", value.createCollectionSubfolder)
            .putBoolean("asciiFilenames", value.asciiFilenames)
            .putBoolean("autoTryCollectionPasswords", value.autoTryCollectionPasswords)
            .putString("collectionPasswordListJson", JSONArray(value.collectionPasswordList).toString())
            .remove("collectionPasswordList")
            .putInt("streamWriteBatchBytes", value.streamWriteBatchBytes)
            .putInt("inflateBufferBytes", value.inflateBufferBytes)
            .putString("themeMode", value.themeMode.name)
            .remove("fileConcurrency")
            .remove("uploadFileConcurrency")
            .apply()
        mutableSettings.value = value
    }

    private fun read() = AppSettings(
        segmentPoolSize = preferences.getInt("segmentPoolSize", SEGMENT_POOL_SIZE_DEFAULT)
            .coerceIn(SEGMENT_POOL_SIZE_MIN, SEGMENT_POOL_SIZE_MAX),
        downloadBandwidthMiBps = preferences.getInt("downloadBandwidthMiBps", 0).coerceIn(0, BANDWIDTH_LIMIT_MAX),
        uploadBandwidthMiBps = preferences.getInt("uploadBandwidthMiBps", 0).coerceIn(0, BANDWIDTH_LIMIT_MAX),
        downloadMaxRetries = preferences.getInt("downloadMaxRetries", DOWNLOAD_RETRY_DEFAULT)
            .coerceIn(DOWNLOAD_RETRY_MIN, DOWNLOAD_RETRY_MAX),
        uploadMaxRetries = preferences.getInt("uploadMaxRetries", UPLOAD_RETRY_DEFAULT)
            .coerceIn(UPLOAD_RETRY_MIN, UPLOAD_RETRY_MAX),
        lastDownloadDestinationUri = preferences.getString("lastDownloadDestinationUri", "").orEmpty(),
        createCollectionSubfolder = preferences.getBoolean("createCollectionSubfolder", true),
        asciiFilenames = preferences.getBoolean("asciiFilenames", false),
        autoTryCollectionPasswords = preferences.getBoolean("autoTryCollectionPasswords", false),
        collectionPasswordList = readCollectionPasswords(),
        streamWriteBatchBytes = preferences.getInt("streamWriteBatchBytes", 2 * 1024 * 1024)
            .let { value -> STREAM_WRITE_OPTIONS.minBy { kotlin.math.abs(it - value) } },
        inflateBufferBytes = preferences.getInt("inflateBufferBytes", 8 * 1024 * 1024)
            .let { value -> INFLATE_BUFFER_OPTIONS.minBy { kotlin.math.abs(it - value) } },
        themeMode = runCatching {
            ThemeMode.valueOf(preferences.getString("themeMode", null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM),
    )

    private fun readCollectionPasswords(): List<String> {
        val json = preferences.getString("collectionPasswordListJson", null)
        if (json != null) {
            return runCatching {
                val array = JSONArray(json)
                List(array.length()) { array.getString(it) }.take(10)
            }.getOrDefault(emptyList())
        }
        return runCatching { preferences.getStringSet("collectionPasswordList", emptySet()).orEmpty().take(10) }
            .getOrDefault(emptyList())
    }

    private companion object {
        const val SEGMENT_POOL_SIZE_MIN = 2
        const val SEGMENT_POOL_SIZE_MAX = 64
        const val SEGMENT_POOL_SIZE_DEFAULT = 8
        const val BANDWIDTH_LIMIT_MAX = 1024
        const val DOWNLOAD_RETRY_MIN = 3
        const val DOWNLOAD_RETRY_MAX = 10
        const val DOWNLOAD_RETRY_DEFAULT = 5
        const val UPLOAD_RETRY_MIN = 1
        const val UPLOAD_RETRY_MAX = 3
        const val UPLOAD_RETRY_DEFAULT = 2
        val STREAM_WRITE_OPTIONS = intArrayOf(
            256 * 1024, 512 * 1024, 1024 * 1024, 2 * 1024 * 1024,
            4 * 1024 * 1024, 8 * 1024 * 1024,
        )
        val INFLATE_BUFFER_OPTIONS = intArrayOf(
            1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024,
            8 * 1024 * 1024, 16 * 1024 * 1024,
        )
    }
}
