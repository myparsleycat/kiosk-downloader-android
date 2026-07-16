package com.kiodl.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kiodl.android.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2559A8),
    secondary = Color(0xFF4D607C),
    tertiary = Color(0xFF67587A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    secondary = Color(0xFFB5C8E8),
    tertiary = Color(0xFFD1BEE7),
)

@Composable
fun KioskDownloaderTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = KioskTypography,
        content = content,
    )
}
