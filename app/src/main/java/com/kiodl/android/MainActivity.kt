package com.kiodl.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kiodl.android.ui.KioskDownloaderApp
import com.kiodl.android.ui.theme.KioskDownloaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.kiodl.android.data.settings.AppSettingsRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: AppSettingsRepository
    private val incomingUrl = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            val sharedUrl by incomingUrl.collectAsStateWithLifecycle()
            KioskDownloaderTheme(settings.themeMode) {
                KioskDownloaderApp(
                    incomingUrl = sharedUrl,
                    onIncomingUrlConsumed = { incomingUrl.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent?) {
        val value = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!value.isNullOrBlank()) incomingUrl.value = value
    }
}
