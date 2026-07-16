package com.kiodl.android.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val segmentPoolSize: Int = 8,
    val downloadBandwidthMiBps: Int = 0,
    val uploadBandwidthMiBps: Int = 0,
    val downloadMaxRetries: Int = 5,
    val uploadMaxRetries: Int = 2,
    val lastDownloadDestinationUri: String = "",
    val createCollectionSubfolder: Boolean = true,
    val asciiFilenames: Boolean = false,
    val autoTryCollectionPasswords: Boolean = false,
    val collectionPasswordList: List<String> = emptyList(),
    val streamWriteBatchBytes: Int = 2 * 1024 * 1024,
    val inflateBufferBytes: Int = 8 * 1024 * 1024,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)
