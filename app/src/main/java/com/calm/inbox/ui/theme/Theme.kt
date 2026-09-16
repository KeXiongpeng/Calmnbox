package com.calm.inbox.ui.theme

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

private val GreenPrimary = Color(0xFF3F6C51)
private val GreenContainer = Color(0xFFC8EBD2)
private val DarkGreenPrimary = Color(0xFFA5D3B1)
private val DarkGreenContainer = Color(0xFF285037)

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    primaryContainer = GreenContainer,
    secondary = Color(0xFF526350),
    secondaryContainer = Color(0xFFD5E8D1),
    background = Color(0xFFFCFDF7),
    surface = Color(0xFFFCFDF7),
    surfaceVariant = Color(0xFFDEE5D9)
)

private val DarkColors = darkColorScheme(
    primary = DarkGreenPrimary,
    primaryContainer = DarkGreenContainer,
    secondary = Color(0xFFBACCB6),
    secondaryContainer = Color(0xFF3A4B38),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
    surfaceVariant = Color(0xFF414940)
)

@Composable
fun CalmInboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
