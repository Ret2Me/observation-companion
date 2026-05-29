package pl.put.observationcompanion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = Indigo500,
    onPrimary = Color.White,
    primaryContainer = Indigo950,
    onPrimaryContainer = Indigo300,
    secondary = Indigo400,
    onSecondary = Color.White,
    tertiary = Emerald500,
    onTertiary = Color.White,
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate900,
    onSurfaceVariant = Slate300,
    outline = Slate800,
    error = Rose500,
    onError = Color.White
  )

private val LightColorScheme = DarkColorScheme // Force sophisticated dark mode design globally for the dark-slate radio experience

@Composable
fun ObservationCompanionTheme(
  darkTheme: Boolean = true, // Default to true for Sophisticated Dark design theme
  dynamicColor: Boolean = false, // Disable dynamic colors to preserve Sophisticated Dark branding
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
