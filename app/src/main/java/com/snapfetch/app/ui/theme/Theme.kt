package com.snapfetch.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PrimarySienna,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerWarm,
    onPrimaryContainer = OnPrimaryFixedWarm,
    secondary = OnSurfaceVariantWarm,
    onSecondary = Color.White,
    secondaryContainer = SurfaceContainerHighLinen,
    onSecondaryContainer = OnSurfaceEspresso,
    tertiary = TertiaryRose,
    onTertiary = Color.White,
    background = BackgroundLinen,
    onBackground = OnSurfaceEspresso,
    surface = SurfaceBrightLinen,
    onSurface = OnSurfaceEspresso,
    surfaceVariant = SurfaceContainerLinen,
    onSurfaceVariant = OnSurfaceVariantWarm,
    surfaceContainerLowest = SurfaceContainerLowestWhite,
    surfaceContainerLow = SurfaceContainerLowLinen,
    surfaceContainer = SurfaceContainerLinen,
    surfaceContainerHigh = SurfaceContainerHighLinen,
    surfaceContainerHighest = SurfaceContainerHighestLinen,
    outline = OutlineWarm,
    outlineVariant = OutlineVariantWarm,
    scrim = ScrimDimmed
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDimWarm,
    onPrimary = OnPrimaryFixedWarm,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = PrimaryFixedWarm,
    secondary = OutlineDarkWarm,
    onSecondary = Color.White,
    secondaryContainer = SurfaceContainerHighDark,
    onSecondaryContainer = OnSurfaceDarkLinen,
    tertiary = TertiaryRoseDark,
    onTertiary = Color.White,
    background = BackgroundDarkEspresso,
    onBackground = OnSurfaceDarkLinen,
    surface = SurfaceDarkEspresso,
    onSurface = OnSurfaceDarkLinen,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = OnSurfaceVariantDarkLinen,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = OutlineDarkWarm,
    outlineVariant = OutlineVariantDarkWarm,
    scrim = ScrimDimmedDark
)

@Composable
fun SnapFetchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isTranslucentOverlay: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            if (isTranslucentOverlay) {
                // Ensure transparent status bar and nav bar for floating overlay
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            } else {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
