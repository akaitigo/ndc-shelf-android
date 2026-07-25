package dev.ndcshelf.app.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F1DC),
    onPrimaryContainer = Color(0xFF002119),
    secondary = Color(0xFF4D635B),
    secondaryContainer = Color(0xFFD0E8DF),
    tertiary = Color(0xFF3F6374),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFF8FAF7),
    surfaceVariant = Color(0xFFDDE5E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD5C2),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFB9F1DC),
    secondary = Color(0xFFB5CCC3),
    tertiary = Color(0xFFA7CDDF),
)

@Composable
fun NdcShelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
