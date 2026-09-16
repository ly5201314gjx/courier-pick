package com.courier.pick.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = iOSBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF),
    onPrimaryContainer = Color(0xFF00315E),
    secondary = iOSBlueDark,
    background = iOSBackground,
    onBackground = iOSSystemLabel,
    surface = iOSSurface,
    onSurface = iOSSystemLabel,
    surfaceVariant = iOSFill,
    onSurfaceVariant = iOSSecondaryLabel,
    outline = iOSSeparator,
    error = iOSRed,
    onError = Color.White
)

@Composable
fun CourierPickTheme(
    content: @Composable () -> Unit
) {
    // The app is intentionally always light (iOS-like, no dark mode).
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}