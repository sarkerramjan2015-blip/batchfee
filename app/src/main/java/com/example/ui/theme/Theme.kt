package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.domain.ThemePreferences

private val DarkColorScheme =
  darkColorScheme(
      primary = PrimaryLight,
      onPrimary = SurfaceDarker,
      secondary = SecondaryLight,
      onSecondary = SurfaceDarker,
      surface = SurfaceDark,
      background = SurfaceDarker,
      onSurface = TextPrimaryDark,
      onBackground = TextPrimaryDark,
      surfaceVariant = SurfaceDark.copy(alpha = 0.8f),
      onSurfaceVariant = TextSecondaryDark
  )

private val LightColorScheme =
  lightColorScheme(
      primary = PrimaryBlue,
      onPrimary = SurfaceLight,
      secondary = SecondaryTeal,
      onSecondary = SurfaceLight,
      surface = SurfaceLight,
      background = SurfaceLight,
      onSurface = TextPrimaryLight,
      onBackground = TextPrimaryLight,
      surfaceVariant = SurfaceVariantLight,
      onSurfaceVariant = TextSecondaryLight
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disabling dynamicColor by default to enforce BatchFee visual branding
  dynamicColor: Boolean = false,
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
