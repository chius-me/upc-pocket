package me.chius.upcpocket.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import me.chius.upcpocket.core.model.ColorSeed
import me.chius.upcpocket.core.model.ThemePreference

@Composable
fun UpcPocketTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    colorSeed: ColorSeed = ColorSeed.OCEAN_BLUE,
    dynamicColorEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = if (dynamicColorEnabled) {
        if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        colorSchemeForSeed(seed = colorSeed, dark = useDarkTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UpcTypography,
        shapes = UpcShapes,
        content = content,
    )
}
